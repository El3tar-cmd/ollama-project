package com.example.linux

import android.content.Context
import android.os.Build
import android.system.Os
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
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Handles downloading and setting up the embedded Ubuntu Linux environment.
 * Switched from Alpine to Ubuntu Base to use a glibc rootfs with apt.
 */
class LinuxSetupManager(private val context: Context) {

    data class Progress(
        val stage: Stage,
        val message: String,
        val percent: Int = -1,
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
            val baseDir = EmbeddedLinux.baseDir(context)
            val storageDir = if (baseDir.exists()) baseDir else baseDir.parentFile ?: context.filesDir
            val availableSpace = storageDir.usableSpace
            val availableMB = availableSpace / (1024 * 1024)
            val requiredSpace = 80L * 1024 * 1024

            if (availableSpace < requiredSpace) {
                emit(Stage.ERROR, "Insufficient disk space. Need at least 80MB, available: ${availableMB}MB", err = true)
                return@withContext
            }

            emit(Stage.PREPARING, "Creating directories…")
            val base = EmbeddedLinux.baseDir(context)
            val binDir = File(base, "bin").also { it.mkdirs() }
            val rootfs = EmbeddedLinux.rootfsDir(context).also { it.mkdirs() }
            val proot = EmbeddedLinux.prootBin(context)

            val loaderBin  = EmbeddedLinux.loaderBin(context)
            val loader32Bin = EmbeddedLinux.loader32Bin(context)
            val libsDir    = EmbeddedLinux.libsDir(context).also { it.mkdirs() }

            if (!proot.exists() || !proot.canExecute() || !loaderBin.exists()) {
                emit(Stage.DOWNLOADING_PROOT, "Downloading Termux PRoot (Android 14 compatible)…", 0)
                val prootDeb = File(base, "proot.deb")
                val dlOk = downloadFile(EmbeddedLinux.prootDebUrl, prootDeb) { pct ->
                    emit(Stage.DOWNLOADING_PROOT, "Downloading PRoot… $pct%", pct)
                }
                if (!dlOk) { emit(Stage.ERROR, "Failed to download PRoot package.", err = true); return@withContext }

                emit(Stage.DOWNLOADING_PROOT, "Extracting PRoot…")
                val extracted = extractFromDeb(prootDeb, mapOf(
                    "usr/bin/proot"          to proot,
                    "usr/libexec/proot/loader"   to loaderBin,
                    "usr/libexec/proot/loader32" to loader32Bin
                ))
                try { prootDeb.delete() } catch (_: Exception) {}
                if (!extracted) { emit(Stage.ERROR, "Failed to extract PRoot.", err = true); return@withContext }

                emit(Stage.DOWNLOADING_PROOT, "Downloading PRoot libraries…")
                val tallocDeb = File(base, "talloc.deb")
                val shmemDeb  = File(base, "shmem.deb")
                val libsOk = downloadFile(EmbeddedLinux.tallocDebUrl, tallocDeb) { _ -> } &&
                             downloadFile(EmbeddedLinux.shmemDebUrl, shmemDeb)  { _ -> }
                if (!libsOk) { emit(Stage.ERROR, "Failed to download PRoot libraries.", err = true); return@withContext }

                // Extract the actual versioned file (.so.2.4.3) — libtalloc.so.2 in the deb is a
                // symlink (0 bytes). The real ELF binary is .so.2.4.3; we copy it as libtalloc.so.2
                // so proot's dynamic linker finds it by the SONAME it expects.
                val tallocOk = extractFromDeb(tallocDeb, mapOf("usr/lib/libtalloc.so.2.4.3" to File(libsDir, "libtalloc.so.2")))
                val shmemOk = extractFromDeb(shmemDeb, mapOf("usr/lib/libandroid-shmem.so" to File(libsDir, "libandroid-shmem.so")))
                try { tallocDeb.delete(); shmemDeb.delete() } catch (_: Exception) {}
                if (!tallocOk || !shmemOk) {
                    emit(Stage.ERROR, "Failed to extract PRoot libraries.", err = true)
                    return@withContext
                }

                EmbeddedLinux.repairProotPermissions(context)
                loaderBin.setExecutable(true, false)
                loader32Bin.setExecutable(true, false)
                emit(Stage.DOWNLOADING_PROOT, "PRoot ready ✓", 100)
            } else {
                EmbeddedLinux.repairProotPermissions(context)
                emit(Stage.DOWNLOADING_PROOT, "PRoot ready ✓", 100)
            }

            val rootfsArchive = File(base, "ubuntu-rootfs.tar.gz")
            val rootfsNeedsReinstall = rootfs.exists() &&
                (!EmbeddedLinux.rootfsHealthy(context) ||
                    EmbeddedLinux.installedRootfsVersion(context) != EmbeddedLinux.ROOTFS_VERSION)
            if (rootfsNeedsReinstall) {
                emit(Stage.PREPARING, "Existing rootfs is incompatible; reinstalling…")
                rootfs.deleteRecursively()
                rootfs.mkdirs()
                EmbeddedLinux.setupDone(context).delete()
                EmbeddedLinux.runtimesFile(context).delete()
                EmbeddedLinux.rootfsVersionFile(context).delete()
            }

            if (!EmbeddedLinux.rootfsHealthy(context)) {
                emit(Stage.DOWNLOADING_ROOTFS, "Downloading Ubuntu Linux rootfs…", 0)
                val ok = downloadFile(EmbeddedLinux.ubuntuRootfsUrl, rootfsArchive) { pct ->
                    emit(Stage.DOWNLOADING_ROOTFS, "Downloading Ubuntu… $pct%", pct)
                }
                if (!ok) { emit(Stage.ERROR, "Failed to download Ubuntu rootfs.", err = true); return@withContext }

                emit(Stage.EXTRACTING, "Extracting Ubuntu rootfs…")
                val extracted = extractTar(rootfsArchive, rootfs) { msg ->
                    emit(Stage.EXTRACTING, msg)
                }
                if (!extracted) { emit(Stage.ERROR, "Extraction failed.", err = true); return@withContext }
                
                try { rootfsArchive.delete() } catch (e: Exception) {}
                try { EmbeddedLinux.rootfsVersionFile(context).writeText(EmbeddedLinux.ROOTFS_VERSION) } catch (_: Exception) {}
                emit(Stage.EXTRACTING, "Extraction complete ✓")
            }

            emit(Stage.CONFIGURING, "Configuring DNS and system…")
            try {
                EmbeddedLinux.configureSystem(context)
            } catch (e: Exception) {
                emit(Stage.CONFIGURING, "Warning: ${e.message}")
            }

            emit(Stage.CONFIGURING, "Testing Ubuntu shell under PRoot…")
            val smokeTest = EmbeddedLinux.exec(
                context,
                "echo ubuntu-ready",
                timeoutSec = 30,
                allowIncompleteSetup = true
            )
            if (!smokeTest.success || !smokeTest.output.contains("ubuntu-ready")) {
                EmbeddedLinux.setupDone(context).delete()
                EmbeddedLinux.runtimesFile(context).delete()
                emit(
                    Stage.ERROR,
                    "PRoot failed to start Ubuntu: ${smokeTest.output.ifBlank { "exit ${smokeTest.exitCode}" }.take(300)}",
                    err = true
                )
                return@withContext
            }

            if (!EmbeddedLinux.runtimesInstalled(context)) {
                emit(Stage.INSTALLING_RUNTIMES, "Updating package list (apt)… (first run only)")
                try {
                    val updateResult = EmbeddedLinux.exec(context, "apt-get update 2>&1", timeoutSec = 120)
                    if (!updateResult.success) {
                        emit(Stage.INSTALLING_RUNTIMES, "apt update warning: ${updateResult.output.take(200)}")
                    }

                    emit(Stage.INSTALLING_RUNTIMES, "Installing Python 3, Node.js, git, curl…")
                    val installResult = EmbeddedLinux.exec(context, "DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-pip nodejs npm git curl wget nano vim build-essential 2>&1", timeoutSec = 300)
                    if (!installResult.success) {
                        emit(Stage.INSTALLING_RUNTIMES, "Install warning: ${installResult.output.takeLast(300)}")
                    }

                    try {
                        EmbeddedLinux.runtimesFile(context).writeText("installed")
                    } catch (e: Exception) {}
                    emit(Stage.INSTALLING_RUNTIMES, "Runtimes installed ✓")
                } catch (e: Exception) {
                    emit(Stage.INSTALLING_RUNTIMES, "Runtime installation error: ${e.message}")
                }
            }

            try {
                EmbeddedLinux.setupDone(context).writeText("ok")
            } catch (e: Exception) {}
            emit(Stage.DONE, "✅ Embedded Linux ready! Python, Node.js, and git available.", 100)

        } catch (e: Exception) {
            Log.e("LinuxSetupManager", "Critical setup error", e)
            onProgress(Progress(Stage.ERROR, "Setup error: ${e.localizedMessage ?: e.message}", isError = true))
        }
    }

    /**
     * Extracts specific files from a Debian .deb package (AR archive containing data.tar.xz/gz).
     * @param debFile  The downloaded .deb file
     * @param targets  Map of "path/inside/tar" → destination File on disk
     */
    private suspend fun extractFromDeb(debFile: File, targets: Map<String, File>): Boolean = withContext(Dispatchers.IO) {
        try {
            val remaining = targets.toMutableMap()
            ArArchiveInputStream(java.io.BufferedInputStream(debFile.inputStream())).use { ar ->
                var arEntry = ar.nextEntry
                while (arEntry != null && remaining.isNotEmpty()) {
                    val name = arEntry.name.trimEnd('/')
                    if (name.startsWith("data.tar")) {
                        val decompressed = when {
                            name.endsWith(".xz") -> XZCompressorInputStream(ar)
                            name.endsWith(".gz")  -> GZIPInputStream(ar)
                            else -> ar
                        }
                        val tar = TarArchiveInputStream(decompressed)
                        var tarEntry = tar.nextTarEntry
                        while (tarEntry != null && remaining.isNotEmpty()) {
                            // Normalize: strip leading "./" from tar entry names
                            val tarName = tarEntry.name.removePrefix("./").trimEnd('/')
                            val destFile = remaining.entries.find { (k, _) ->
                                tarName == k || tarName.endsWith("/$k")
                            }
                            if (destFile != null && !tarEntry.isDirectory && !tarEntry.isSymbolicLink) {
                                destFile.value.parentFile?.mkdirs()
                                FileOutputStream(destFile.value).use { fos ->
                                    val buf = ByteArray(32768)
                                    var len: Int
                                    while (tar.read(buf).also { len = it } != -1) fos.write(buf, 0, len)
                                }
                                remaining.remove(destFile.key)
                            }
                            tarEntry = tar.nextTarEntry
                        }
                        break
                    }
                    arEntry = ar.nextEntry
                }
            }
            if (remaining.isNotEmpty()) {
                Log.e("LinuxSetupManager", "extractFromDeb missing entries: ${remaining.keys.joinToString()}")
            }
            remaining.isEmpty()
        } catch (e: Exception) {
            Log.e("LinuxSetupManager", "extractFromDeb failed: ${e.message}", e)
            false
        }
    }

    private suspend fun downloadFile(url: String, dest: File, onProgress: suspend (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false
            val body = response.body ?: return@withContext false
            val total = body.contentLength()
            var downloaded = 0L
            FileOutputStream(dest).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(16384)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val progress = ((downloaded * 100) / total).toInt()
                            onProgress(progress)
                        }
                        if (!isActive) throw kotlinx.coroutines.CancellationException("Download cancelled")
                    }
                }
            }
            true
        } catch (e: Exception) {
            if (dest.exists()) dest.delete()
            false
        }
    }

    private suspend fun extractTar(archive: File, destDir: File, onProgress: suspend (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            destDir.mkdirs()
            var count = 0
            var lastUpdateTime = System.currentTimeMillis()
            val pendingHardLinks = mutableListOf<Pair<File, String>>()
            
            BufferedInputStream(archive.inputStream()).use { bis ->
                val decompressionStream = when {
                    archive.name.endsWith(".xz") -> XZCompressorInputStream(bis)
                    archive.name.endsWith(".gz") -> GZIPInputStream(bis)
                    else -> bis
                }
                decompressionStream.use { dis ->
                    val tar = TarArchiveInputStream(dis)
                    var entry = tar.nextTarEntry
                    
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        val safePath = outFile.canonicalPath
                        val destPath = destDir.canonicalPath
                        
                        if (!safePath.startsWith(destPath)) {
                            entry = tar.nextTarEntry
                            continue
                        }
                        
                        try {
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                outFile.parentFile?.mkdirs()
                                if (outFile.exists()) outFile.delete()
                                try {
                                    Os.symlink(entry.linkName, outFile.absolutePath)
                                } catch (e: Exception) {
                                    Log.w("LinuxSetupManager", "Could not create symlink ${entry.name}: ${e.message}")
                                }
                            } else if (entry.isLink) {
                                outFile.parentFile?.mkdirs()
                                pendingHardLinks.add(outFile to entry.linkName)
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    val buffer = ByteArray(32768) 
                                    var len: Int
                                    while (tar.read(buffer).also { len = it } != -1) {
                                        fos.write(buffer, 0, len)
                                    }
                                }
                                
                                val mode = entry.mode
                                if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                                if (mode and 0b000_001_000 != 0) outFile.setExecutable(true, true)
                            }
                        } catch (e: Exception) {
                            Log.e("LinuxSetupManager", "Error extracting ${entry.name}: ${e.message}")
                        }
                        
                        count++
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime > 500 && count % 100 == 0) {
                            onProgress("Extracting… $count files")
                            lastUpdateTime = currentTime
                        }
                        entry = tar.nextTarEntry
                    }

                    pendingHardLinks.forEach { (outFile, linkName) ->
                        val source = File(destDir, linkName)
                        try {
                            if (source.exists() && !outFile.exists()) {
                                source.copyTo(outFile, overwrite = true)
                                outFile.setExecutable(source.canExecute(), false)
                                outFile.setReadable(true, false)
                            }
                        } catch (e: Exception) {
                            Log.w("LinuxSetupManager", "Could not restore hard link ${outFile.name}: ${e.message}")
                        }
                    }
                }
            }
            onProgress("Extracted $count files total ✓")
            EmbeddedLinux.rootfsHealthy(context)
        } catch (e: Exception) {
            Log.e("LinuxSetupManager", "Critical extraction failure", e)
            onProgress("Extraction failed: ${e.localizedMessage ?: "Unknown error"}")
            false
        }
    }

    fun uninstall() {
        EmbeddedLinux.baseDir(context).deleteRecursively()
    }
}
