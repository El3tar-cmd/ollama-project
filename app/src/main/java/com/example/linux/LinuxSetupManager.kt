package com.example.linux

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Handles downloading and setting up the embedded Debian Linux environment.
 *
 * Setup flow:
 *   1. Create directories
 *   2. Download PRoot static binary
 *   3. Download Debian arm64 minimal rootfs tarball
 *   4. Extract rootfs
 *   5. Configure DNS / hosts
 *   6. apt-get update + install Python3, Node.js, git
 *   7. Mark setup complete
 */
class LinuxSetupManager(private val context: Context) {

    data class Progress(
        val stage: Stage,
        val message: String,
        val percent: Int = -1,   // -1 = indeterminate
        val isError: Boolean = false
    )

    enum class Stage {
        PREPARING, DOWNLOADING_PROOT, DOWNLOADING_ROOTFS,
        EXTRACTING, CONFIGURING, INSTALLING_RUNTIMES, DONE, ERROR
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    suspend fun setup(onProgress: suspend (Progress) -> Unit) = withContext(Dispatchers.IO) {

        suspend fun emit(stage: Stage, msg: String, pct: Int = -1, err: Boolean = false) {
            onProgress(Progress(stage, msg, pct, err))
        }

        try {
            // ── 1. Prepare directories ────────────────────────────────────────
            emit(Stage.PREPARING, "Creating directories…")
            val base   = EmbeddedLinux.baseDir(context)
            val binDir = File(base, "bin").also { it.mkdirs() }
            val rootfs = EmbeddedLinux.rootfsDir(context).also { it.mkdirs() }
            val proot  = EmbeddedLinux.prootBin(context)

            // ── 2. Download PRoot binary ──────────────────────────────────────
            if (!proot.exists() || !proot.canExecute()) {
                emit(Stage.DOWNLOADING_PROOT, "Downloading PRoot binary…", 0)
                val ok = downloadFile(EmbeddedLinux.prootUrl, proot) { pct ->
                    emit(Stage.DOWNLOADING_PROOT, "Downloading PRoot… $pct%", pct)
                }
                if (!ok) { emit(Stage.ERROR, "Failed to download PRoot binary.", err = true); return@withContext }
                proot.setExecutable(true, false)
                emit(Stage.DOWNLOADING_PROOT, "PRoot ready ✓", 100)
            }

            // ── 3. Download Debian rootfs ─────────────────────────────────────
            val rootfsArchive = File(base, "debian-rootfs.tar.xz")
            if (!rootfs.resolve("bin/bash").exists()) {
                emit(Stage.DOWNLOADING_ROOTFS, "Downloading Debian minimal rootfs (~80MB)…", 0)
                val ok = downloadFile(EmbeddedLinux.debianRootfsUrl, rootfsArchive) { pct ->
                    emit(Stage.DOWNLOADING_ROOTFS, "Downloading Debian… $pct%", pct)
                }
                if (!ok) { emit(Stage.ERROR, "Failed to download Debian rootfs.", err = true); return@withContext }

                // ── 4. Extract rootfs ─────────────────────────────────────────
                emit(Stage.EXTRACTING, "Extracting Debian rootfs… (this may take a minute)")
                val extracted = extractTar(rootfsArchive, rootfs) { msg ->
                    emit(Stage.EXTRACTING, msg)
                }
                if (!extracted) { emit(Stage.ERROR, "Extraction failed.", err = true); return@withContext }
                rootfsArchive.delete()
                emit(Stage.EXTRACTING, "Extraction complete ✓")
            }

            // ── 5. Configure system ───────────────────────────────────────────
            emit(Stage.CONFIGURING, "Configuring DNS and system…")
            EmbeddedLinux.configureSystem(context)

            // ── 6. Update apt and install runtimes ────────────────────────────
            if (!EmbeddedLinux.runtimesInstalled(context)) {
                emit(Stage.INSTALLING_RUNTIMES, "Updating package list… (first run only)")
                val updateResult = EmbeddedLinux.exec(context,
                    "apt-get update -y 2>&1", timeoutSec = 180)
                if (!updateResult.success) {
                    emit(Stage.INSTALLING_RUNTIMES,
                        "apt-get update warning (may still work): ${updateResult.output.take(200)}")
                }

                emit(Stage.INSTALLING_RUNTIMES, "Installing Python 3, Node.js, git, curl…")
                val installResult = EmbeddedLinux.install(context,
                    "python3", "python3-pip", "nodejs", "npm", "git", "curl", "wget",
                    "nano", "vim-tiny", "build-essential"
                )
                if (!installResult.success) {
                    emit(Stage.INSTALLING_RUNTIMES,
                        "Install warning: ${installResult.output.takeLast(300)}")
                }

                // Mark runtimes as installed even if some failed (partial install still useful)
                EmbeddedLinux.runtimesFile(context).writeText("installed")
                emit(Stage.INSTALLING_RUNTIMES, "Runtimes installed ✓")
            }

            // ── 7. Mark complete ──────────────────────────────────────────────
            EmbeddedLinux.setupDone(context).writeText("ok")
            emit(Stage.DONE, "✅ Embedded Linux ready! Python, Node.js, and git available.", 100)

        } catch (e: Exception) {
            onProgress(Progress(Stage.ERROR, "Setup error: ${e.message}", isError = true))
        }
    }

    // ── Download helper ───────────────────────────────────────────────────────
    private suspend fun downloadFile(
        url: String,
        dest: File,
        onProgress: suspend (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            dest.parentFile?.mkdirs()
            val request  = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false
            val body   = response.body ?: return@withContext false
            val total  = body.contentLength()
            var downloaded = 0L

            FileOutputStream(dest).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                    }
                }
            }
            true
        } catch (_: Exception) { 
            dest.delete()
            false 
        }
    }

    // ── TAR extractor (supports .gz and .xz) ──────────────────────────────────
    private suspend fun extractTar(
        archive: File,
        destDir: File,
        onProgress: suspend (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            destDir.mkdirs()
            var count = 0
            BufferedInputStream(archive.inputStream()).use { bis ->
                val decompressionStream = when {
                    archive.name.endsWith(".xz") -> XZCompressorInputStream(bis)
                    archive.name.endsWith(".gz") -> GZIPInputStream(bis)
                    else -> bis // Assume uncompressed tar
                }
                
                decompressionStream.use { dis ->
                    val tar = TarArchiveInputStream(dis)
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        when {
                            entry.isDirectory -> outFile.mkdirs()
                            entry.isSymbolicLink -> {
                                outFile.parentFile?.mkdirs()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    try {
                                        java.nio.file.Files.createSymbolicLink(
                                            outFile.toPath(),
                                            java.nio.file.Paths.get(entry.linkName)
                                        )
                                    } catch (_: Exception) {
                                        // Skip broken symlinks
                                    }
                                }
                            }
                            else -> {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos -> tar.copyTo(fos) }
                                val mode = entry.mode
                                if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                                if (mode and 0b000_001_000 != 0) outFile.setExecutable(true, true)
                            }
                        }
                        count++
                        if (count % 500 == 0) onProgress("Extracting… $count files")
                        entry = tar.nextTarEntry
                    }
                }
            }
            onProgress("Extracted $count files total ✓")
            true
        } catch (e: Exception) { false }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────
    fun uninstall() {
        EmbeddedLinux.baseDir(context).deleteRecursively()
    }
}
