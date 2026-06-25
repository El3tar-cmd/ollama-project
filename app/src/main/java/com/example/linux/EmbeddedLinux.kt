package com.example.linux

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the embedded Alpine Linux environment running inside the app via PRoot.
 *
 * Directory layout (inside context.filesDir):
 *   embedded_linux/
 *     bin/proot           ← Static PRoot binary
 *     rootfs/             ← Debian arm64 minimal rootfs
 *
 * No root required — PRoot implements chroot in userspace.
 */
object EmbeddedLinux {

    // ── Architecture detection ────────────────────────────────────────────────
    val arch: String by lazy {
        when {
            Build.SUPPORTED_ABIS.any { it.contains("arm64") || it == "aarch64" } -> "aarch64"
            Build.SUPPORTED_ABIS.any { it.contains("x86_64") }                  -> "x86_64"
            Build.SUPPORTED_ABIS.any { it.contains("arm") }                     -> "arm"
            else -> "aarch64"
        }
    }

    // ── Download URLs ─────────────────────────────────────────────────────────
    // Termux-patched PRoot — uses a loader binary instead of ptrace,
    // which bypasses Android 14's ptrace restrictions entirely.
    val prootDebUrl: String get() = when (arch) {
        "aarch64" -> "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.81_aarch64.deb"
        "x86_64"  -> "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.81_x86_64.deb"
        "arm"     -> "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.81_arm.deb"
        else      -> "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.81_aarch64.deb"
    }
    val tallocDebUrl: String get() = when (arch) {
        "aarch64" -> "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
        "x86_64"  -> "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_x86_64.deb"
        "arm"     -> "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_arm.deb"
        else      -> "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
    }
    val shmemDebUrl: String get() = when (arch) {
        "aarch64" -> "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
        "x86_64"  -> "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb"
        "arm"     -> "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_arm.deb"
        else      -> "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
    }

    // Alpine 3.18 rootfs — ~3.1MB. v3.18 is the last version confirmed stable
    // with PRoot on Android kernels (newer musl builds cause SIGBUS/signal 7).
    val debianRootfsUrl: String get() = when (arch) {
        "aarch64" -> "https://dl-cdn.alpinelinux.org/alpine/v3.18/releases/aarch64/alpine-minirootfs-3.18.9-aarch64.tar.gz"
        "x86_64"  -> "https://dl-cdn.alpinelinux.org/alpine/v3.18/releases/x86_64/alpine-minirootfs-3.18.9-x86_64.tar.gz"
        "arm"     -> "https://dl-cdn.alpinelinux.org/alpine/v3.18/releases/armv7/alpine-minirootfs-3.18.9-armv7.tar.gz"
        else      -> "https://dl-cdn.alpinelinux.org/alpine/v3.18/releases/aarch64/alpine-minirootfs-3.18.9-aarch64.tar.gz"
    }

    // ── Paths ─────────────────────────────────────────────────────────────────
    fun baseDir(context: Context)    = File(context.filesDir, "embedded_linux")
    fun prootBin(context: Context)   = File(baseDir(context), "bin/proot")
    fun loaderBin(context: Context)  = File(baseDir(context), "bin/loader")
    fun loader32Bin(context: Context)= File(baseDir(context), "bin/loader32")
    fun libsDir(context: Context)    = File(baseDir(context), "libs")
    fun packagedProotBin(context: Context): File? {
        // PRoot release binaries are standalone ELF executables, not Android
        // shared libraries. Bundling them as lib*.so can make Android's linker
        // reject or mis-run them, so runtime execution uses filesDir/bin/proot.
        return null
    }
    fun executableProotBin(context: Context): File = prootBin(context)
    fun rootfsDir(context: Context)  = File(baseDir(context), "rootfs")
    fun setupDone(context: Context)  = File(baseDir(context), ".setup_complete")
    fun runtimesFile(context: Context) = File(baseDir(context), ".runtimes_installed")
    fun rootfsVersionFile(context: Context) = File(baseDir(context), ".rootfs_version")
    const val ROOTFS_VERSION = "alpine-3.18-minimal"

    fun rootfsHealthy(context: Context): Boolean {
        val rootfs = rootfsDir(context)
        return rootfsEntryExists(rootfs, "bin/sh") &&
            rootfsEntryExists(rootfs, "etc")
    }

    private fun rootfsEntryExists(rootfs: File, relativePath: String): Boolean {
        val entry = File(rootfs, relativePath)
        if (entry.exists()) return true
        return try {
            val target = Os.readlink(entry.absolutePath)
            val resolved = if (target.startsWith("/")) {
                File(rootfs, target.removePrefix("/"))
            } else {
                File(entry.parentFile, target)
            }
            resolved.exists()
        } catch (_: Exception) {
            false
        }
    }

    // ── State checks ──────────────────────────────────────────────────────────
    fun isReady(context: Context, requireSetupDone: Boolean = true): Boolean {
        repairProotPermissions(context)
        val proot = executableProotBin(context)
        val rootfs = rootfsDir(context)
        val done = setupDone(context)
        
        Log.d("EmbeddedLinux", ">>> isReady() check:")
        Log.d("EmbeddedLinux", "    proot exists: ${proot.exists()} (${proot.absolutePath})")
        Log.d("EmbeddedLinux", "    packaged proot: disabled for standalone PRoot executable")
        Log.d("EmbeddedLinux", "    proot canExecute: ${proot.canExecute()}")
        Log.d("EmbeddedLinux", "    rootfs exists: ${rootfs.exists()} (${rootfs.absolutePath})")
        Log.d("EmbeddedLinux", "    rootfs healthy: ${rootfsHealthy(context)}")
        Log.d("EmbeddedLinux", "    setupDone exists: ${done.exists()} (${done.absolutePath})")
        
        return proot.exists() &&
        proot.canExecute() &&
        loaderBin(context).exists() &&
        rootfs.exists() &&
        rootfsHealthy(context) &&
        (!requireSetupDone || done.exists())
    }

    fun runtimesInstalled(context: Context): Boolean = runtimesFile(context).exists()

    fun installedRootfsVersion(context: Context): String =
        rootfsVersionFile(context).takeIf { it.exists() }?.readText()?.trim().orEmpty()

    private fun prootTmpDir(context: Context): File =
        File(context.cacheDir, "proot-tmp").also { it.mkdirs() }

    fun repairProotPermissions(context: Context): Boolean {
        val downloaded = prootBin(context)
        if (!downloaded.exists()) return false
        return try {
            downloaded.setReadable(true, false)
            downloaded.setExecutable(true, false)
            Os.chmod(downloaded.absolutePath, 0b111_101_101)
            downloaded.canExecute()
        } catch (e: Exception) {
            Log.w("EmbeddedLinux", "Could not chmod downloaded PRoot: ${e.message}")
            downloaded.canExecute()
        }
    }

    fun diskUsageMb(context: Context): Long {
        fun dirSize(d: File): Long = d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return dirSize(baseDir(context)) / (1024 * 1024)
    }

    private fun prepareRuntimeDirs(context: Context) {
        prootTmpDir(context)
        File(rootfsDir(context), "tmp").also {
            it.mkdirs()
            try { Os.chmod(it.absolutePath, 0b111_111_111) } catch (_: Exception) {}
        }
        File(rootfsDir(context), "root").mkdirs()
        File(rootfsDir(context), "workspace").also {
            it.mkdirs()
            try { Os.chmod(it.absolutePath, 0b111_111_111) } catch (_: Exception) {}
        }
        File(rootfsDir(context), "proc").mkdirs()
        File(rootfsDir(context), "dev").mkdirs()
        File(rootfsDir(context), "sys").mkdirs()
    }

    // ── PRoot command builder ─────────────────────────────────────────────────
    fun buildProotCommand(context: Context, innerCmd: String): List<String> {
        val proot  = executableProotBin(context).absolutePath
        val rootfs = rootfsDir(context).absolutePath
        return listOf(
            proot,
            "--kill-on-exit",
            "-0",                  // Fake root uid=0 (required for apk)
            "-r", rootfs,
            "-b", "/dev:/dev",
            "-b", "/sys:/sys",
            "-b", "/proc:/proc",
            "-b", "/dev/urandom:/dev/random",
            "-w", "/root",
            "/bin/sh", "-c", innerCmd
        )
    }

    /**
     * Execute a command inside the Debian container.
     * @param hostCwd  Bind this host path into container at /workspace
     */
    fun exec(
        context: Context,
        cmd: String,
        hostCwd: String? = null,
        timeoutSec: Long = 120L,
        allowIncompleteSetup: Boolean = false
    ): ExecResult {
        Log.d("EmbeddedLinux", ">>> exec() called with cmd: $cmd")
        Log.d("EmbeddedLinux", ">>> isReady check: ${isReady(context, requireSetupDone = !allowIncompleteSetup)}")
        if (!isReady(context, requireSetupDone = !allowIncompleteSetup)) {
            Log.e("EmbeddedLinux", ">>> isReady returned false!")
            return ExecResult(-1, "Embedded Linux not ready. Please set it up first.")
        }
        return try {
            prepareRuntimeDirs(context)
            val fullCmd = if (hostCwd != null) "cd /workspace && $cmd" else cmd
            val prootCmd = buildProotCommand(context, fullCmd).toMutableList()
            Log.d("EmbeddedLinux", ">>> prootCmd: $prootCmd")

            // Bind host working dir into container if provided
            if (hostCwd != null && File(hostCwd).exists()) {
                val bindIdx = prootCmd.indexOfFirst { it == "-w" }
                if (bindIdx >= 0) {
                    prootCmd.add(bindIdx, "$hostCwd:/workspace")
                    prootCmd.add(bindIdx, "-b")
                }
            }

            val pb = ProcessBuilder(prootCmd)
            pb.directory(context.filesDir)
            pb.environment().apply {
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_TMP_DIR", prootTmpDir(context).absolutePath)
                // Termux proot loader — intercepts syscalls without ptrace (Android 14 safe)
                put("PROOT_LOADER", loaderBin(context).absolutePath)
                put("PROOT_LOADER_32", loader32Bin(context).absolutePath)
                // Let proot binary find its shared libs (libtalloc, libandroid-shmem)
                put("LD_LIBRARY_PATH", libsDir(context).absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                put("HOME", "/root")
                put("USER", "root")
                put("TERM", "xterm-256color")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("LANG", "C.UTF-8")
            }
            pb.redirectErrorStream(true)
            Log.d("EmbeddedLinux", ">>> Starting process...")
            val proc = pb.start()
            val (outputThread, outputRef) = proc.readOutputAsync()
            val timeout = !proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (timeout) {
                proc.destroy()
                outputThread.join(1000)
                val output = outputRef.get()
                return ExecResult(-1, "Command timed out after ${timeoutSec}s\n${output.take(300)}")
            }
            outputThread.join(3000)
            val output = outputRef.get()
            Log.d("EmbeddedLinux", ">>> Process finished, exitCode: ${proc.exitValue()}")
            ExecResult(proc.exitValue(), output.take(8000).trimEnd())
        } catch (e: Exception) { 
            Log.e("EmbeddedLinux", ">>> exec() exception: ${e.message}", e)
            val proot = executableProotBin(context)
            val hint = if ((e.message ?: "").contains("Permission denied", ignoreCase = true)) {
                " Android denied execution for ${proot.absolutePath}. Reinstall Embedded Linux from the Server tab so the Termux PRoot files are downloaded again with executable permissions."
            } else ""
            ExecResult(-1, "PRoot exec error: ${e.message}$hint")
        }
    }

    /**
     * Install packages inside Alpine via apk.
     */
    fun install(context: Context, vararg packages: String): ExecResult {
        val pkgList = packages.joinToString(" ")
        return exec(context,
            "apt-get update && apt-get install -y $pkgList 2>&1",
            timeoutSec = 300L
        )
    }

    /**
     * Run the post-setup DNS and hostname configuration.
     */
    fun configureSystem(context: Context) {
        Log.d("EmbeddedLinux", ">>> configureSystem() called")
        
        val rootfs = rootfsDir(context)
        Log.d("EmbeddedLinux", ">>> rootfsDir: ${rootfs.absolutePath}")
        Log.d("EmbeddedLinux", ">>> rootfs exists: ${rootfs.exists()}")
        
        // DNS
        val resolvConf = File(rootfs, "etc/resolv.conf")
        Log.d("EmbeddedLinux", ">>> resolvConf: ${resolvConf.absolutePath}")
        if (!resolvConf.exists() || resolvConf.readText().isBlank()) {
            Log.d("EmbeddedLinux", ">>> Creating resolv.conf...")
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            Log.d("EmbeddedLinux", ">>> resolv.conf created")
        }
        
        // Hostname
        val hostsFile = File(rootfs, "etc/hosts")
        Log.d("EmbeddedLinux", ">>> hostsFile: ${hostsFile.absolutePath}")
        if (!hostsFile.exists()) {
            Log.d("EmbeddedLinux", ">>> Creating hosts file...")
            hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
            Log.d("EmbeddedLinux", ">>> hosts file created")
        }
        
        Log.d("EmbeddedLinux", ">>> configureSystem() finished")
    }

    data class ExecResult(val exitCode: Int, val output: String) {
        val success get() = exitCode == 0
    }

    private fun Process.readOutputAsync(): Pair<Thread, AtomicReference<String>> {
        val output = AtomicReference("")
        val thread = Thread {
            output.set(
                try {
                    inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    "Failed to read process output: ${e.message}"
                }
            )
        }
        thread.isDaemon = true
        thread.start()
        return thread to output
    }
}
