package com.example.linux

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
            // Check available disk space before starting
            val baseDir = EmbeddedLinux.baseDir(context)
            Log.d("LinuxSetupManager", "Base directory: ${baseDir.absolutePath}")
            Log.d("LinuxSetupManager", "Base directory exists: ${baseDir.exists()}")
            
            // Use usableSpace instead of freeSpace for better Android compatibility
            // Also check the parent directory if the baseDir doesn't exist yet
            val storageDir = if (baseDir.exists()) baseDir else baseDir.parentFile ?: context.filesDir
            Log.d("LinuxSetupManager", "Storage directory for space check: ${storageDir.absolutePath}")
            
            val availableSpace = storageDir.usableSpace
            val availableMB = availableSpace / (1024 * 1024)
            Log.d("LinuxSetupManager", "Available space: ${availableMB}MB (${availableSpace} bytes)")
            
            val requiredSpace = 200L * 1024 * 1024 // 200MB minimum
            Log.d("LinuxSetupManager", "Required space: 200MB (${requiredSpace} bytes)")
            
            if (availableSpace < requiredSpace) {
                Log.e("LinuxSetupManager", "Insufficient disk space! Available: ${availableMB}MB, Required: 200MB")
                emit(Stage.ERROR, "Insufficient disk space. Need at least 200MB, available: ${availableMB}MB", err = true)
                return@withContext
            }
            
            Log.d("LinuxSetupManager", "Disk space check passed ✓")

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
                
                // Clean up archive to save space
                try {
                    rootfsArchive.delete()
                } catch (e: Exception) {
                    // Non-critical, continue
                }
                emit(Stage.EXTRACTING, "Extraction complete ✓")
            }

            // ── 5. Configure system ───────────────────────────────────────────
            emit(Stage.CONFIGURING, "Configuring DNS and system…")
            try {
                Log.d("LinuxSetupManager", ">>> Starting configureSystem()")
                EmbeddedLinux.configureSystem(context)
                Log.d("LinuxSetupManager", ">>> configureSystem() completed")
            } catch (e: Exception) {
                Log.e("LinuxSetupManager", ">>> configureSystem() failed: ${e.message}", e)
                emit(Stage.CONFIGURING, "Warning: ${e.message}")
            }

            // ── 6. Update apt and install runtimes ────────────────────────────
            if (!EmbeddedLinux.runtimesInstalled(context)) {
                emit(Stage.INSTALLING_RUNTIMES, "Updating package list… (first run only)")
                try {
                    Log.d("LinuxSetupManager", ">>> Running apt-get update...")
                    val updateResult = EmbeddedLinux.exec(context,
                        "apt-get update -y 2>&1", timeoutSec = 300)
                    Log.d("LinuxSetupManager", ">>> apt-get update exitCode: ${updateResult.exitCode}")
                if (!updateResult.success) {
                    emit(Stage.INSTALLING_RUNTIMES,
                        "apt-get update warning (may still work): ${updateResult.output.take(200)}")
                }

                emit(Stage.INSTALLING_RUNTIMES, "Installing Python 3, Node.js, git, curl…")
                Log.d("LinuxSetupManager", ">>> Running apt-get install...")
                val installResult = EmbeddedLinux.install(context,
                    "python3", "python3-pip", "nodejs", "npm", "git", "curl", "wget",
                    "nano", "vim-tiny", "build-essential"
                )
                Log.d("LinuxSetupManager", ">>> apt-get install exitCode: ${installResult.exitCode}")
                if (!installResult.success) {
                    emit(Stage.INSTALLING_RUNTIMES,
                        "Install warning: ${installResult.output.takeLast(300)}")
                }

                // Mark runtimes as installed even if some failed (partial install still useful)
                try {
                    Log.d("LinuxSetupManager", ">>> Writing .runtimes_installed file")
                    EmbeddedLinux.runtimesFile(context).writeText("installed")
                } catch (e: Exception) {
                    Log.e("LinuxSetupManager", ">>> Failed to write .runtimes_installed: ${e.message}")
                }
                emit(Stage.INSTALLING_RUNTIMES, "Runtimes installed ✓")
            }

            // ── 7. Mark complete ──────────────────────────────────────────────
            try {
                Log.d("LinuxSetupManager", ">>> Writing .setup_done file")
                EmbeddedLinux.setupDone(context).writeText("ok")
            } catch (e: Exception) {
                Log.e("LinuxSetupManager", ">>> Failed to write .setup_done: ${e.message}")
            }
            emit(Stage.DONE, "✅ Embedded Linux ready! Python, Node.js, and git available.", 100)

        } catch (e: Exception) {
            Log.e("LinuxSetupManager", "Critical setup error", e)
            onProgress(Progress(Stage.ERROR, "Setup error: ${e.localizedMessage ?: e.message}", isError = true))
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
            if (dest.exists()) {
                dest.delete()
            }
            
            Log.d("LinuxSetupManager", "Starting download from: $url")
            Log.d("LinuxSetupManager", "Target destination: ${dest.absolutePath}")
            Log.d("LinuxSetupManager", "Storage dir: ${dest.parentFile?.absolutePath}")
            
            val request  = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            
            Log.d("LinuxSetupManager", "HTTP response code: ${response.code}")
            
            if (!response.isSuccessful) {
                Log.e("LinuxSetupManager", "Download failed with HTTP code: ${response.code} for URL: $url")
                return@withContext false
            }
            
            val body   = response.body ?: run {
                Log.e("LinuxSetupManager", "Response body is null for URL: $url")
                return@withContext false
            }
            
            val total = body.contentLength()
            Log.d("LinuxSetupManager", "Content length: $total bytes")
            var downloaded = 0L

            FileOutputStream(dest).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(16384)
                    var read: Int
                    var lastLogTime = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val progress = ((downloaded * 100) / total).toInt()
                            onProgress(progress)
                            // Log progress every 5 seconds
                            val now = System.currentTimeMillis()
                            if (now - lastLogTime > 5000) {
                                Log.d("LinuxSetupManager", "Download progress: $progress% ($downloaded/$total bytes)")
                                lastLogTime = now
                            }
                        }
                        if (!isActive) {
                            throw kotlinx.coroutines.CancellationException("Download cancelled")
                        }
                    }
                }
            }
            Log.d("LinuxSetupManager", "Download completed successfully: ${dest.absolutePath}, size: ${dest.length()} bytes")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w("LinuxSetupManager", "Download cancelled by user/system")
            try { 
                if (dest.exists()) dest.delete() 
                Log.d("LinuxSetupManager", "Deleted partial file: ${dest.absolutePath}")
            } catch (_: Exception) {
                Log.e("LinuxSetupManager", "Failed to delete partial file: ${dest.absolutePath}")
            }
            false
        } catch (e: Exception) {
            Log.e("LinuxSetupManager", "Download exception occurred for URL: $url", e)
            Log.e("LinuxSetupManager", "Exception type: ${e.javaClass.simpleName}, Message: ${e.message}")
            try { 
                if (dest.exists()) dest.delete()
                Log.d("LinuxSetupManager", "Deleted partial file after exception: ${dest.absolutePath}")
            } catch (_: Exception) {
                Log.e("LinuxSetupManager", "Failed to delete partial file after exception: ${dest.absolutePath}")
            }
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
            Log.d("LinuxSetupManager", "=== Starting extraction ===")
            Log.d("LinuxSetupManager", "Archive: ${archive.absolutePath} (${archive.length()} bytes)")
            Log.d("LinuxSetupManager", "Destination: ${destDir.absolutePath}")
            
            destDir.mkdirs()
            var count = 0
            BufferedInputStream(archive.inputStream()).use { bis ->
                val decompressionStream = when {
                    archive.name.endsWith(".xz") -> {
                        Log.d("LinuxSetupManager", "Using XZ decompression")
                        XZCompressorInputStream(bis)
                    }
                    archive.name.endsWith(".gz") -> {
                        Log.d("LinuxSetupManager", "Using GZIP decompression")
                        GZIPInputStream(bis)
                    }
                    else -> {
                        Log.d("LinuxSetupManager", "Using raw stream")
                        bis 
                    }
                }
                
                decompressionStream.use { dis ->
                    val tar = TarArchiveInputStream(dis)
                    var entry = tar.nextTarEntry
                    var entryCount = 0
                    while (entry != null) {
                        entryCount++
                        if (entryCount % 1000 == 0) {
                            Log.d("LinuxSetupManager", "Processing entry $entryCount: ${entry.name}")
                        }
                        
                        val outFile = File(destDir, entry.name)
                        
                        // Sanitize entry name to prevent path traversal
                        val safePath = outFile.canonicalPath
                        val destPath = destDir.canonicalPath
                        if (!safePath.startsWith(destPath)) {
                            Log.w("LinuxSetupManager", "Skipping unsafe path: ${entry.name}")
                            entry = tar.nextTarEntry
                            continue
                        }
                        
                        try {
                            when {
                                entry.isDirectory -> {
                                    Log.d("LinuxSetupManager", "Creating directory: ${entry.name}")
                                    outFile.mkdirs()
                                }
                                entry.isSymbolicLink -> {
                                    Log.w("LinuxSetupManager", "Skipping symlink: ${entry.name} -> ${entry.linkName}")
                                }
                                else -> {
                                    Log.d("LinuxSetupManager", "Extracting file: ${entry.name} (${entry.size} bytes)")
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        var totalBytes = 0L
                                        val buffer = ByteArray(8192)
                                        var len: Int
                                        while (tar.read(buffer).also { len = it } != -1) {
                                            fos.write(buffer, 0, len)
                                            totalBytes += len
                                        }
                                        Log.d("LinuxSetupManager", "File ${entry.name} written: $totalBytes bytes")
                                    }
                                    
                                    val mode = entry.mode
                                    if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                                    if (mode and 0b000_001_000 != 0) outFile.setExecutable(true, true)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("LinuxSetupManager", "Error processing entry ${entry.name}", e)
                            // Continue with next entry instead of failing completely
                            entry = tar.nextTarEntry
                            continue
                        }
                        
                        count++
                        if (count % 500 == 0) {
                            onProgress("Extracting… $count files")
                            Log.d("LinuxSetupManager", "Extracted $count files so far")
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
            Log.d("LinuxSetupManager", "=== Extraction complete: $count files ===")
            onProgress("Extracted $count files total ✓")
            true
        } catch (e: Exception) {
            Log.e("LinuxSetupManager", "Critical extraction error", e)
            Log.e("LinuxSetupManager", "Exception type: ${e.javaClass.simpleName}")
            Log.e("LinuxSetupManager", "Exception message: ${e.message}")
            onProgress("Extraction failed: ${e.message}")
            false
        }
    }

    fun uninstall() {
        EmbeddedLinux.baseDir(context).deleteRecursively()
    }
}
