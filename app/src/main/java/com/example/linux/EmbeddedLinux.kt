package com.example.linux

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages the embedded Debian Linux environment running inside the app via PRoot.
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
    // PRoot static binary — from proot-me/proot official GitHub releases
    val prootUrl: String get() = when (arch) {
        "aarch64" -> "https://github.com/proot-me/proot/releases/download/v5.2.0/proot-v5.2.0-aarch64-static"
        "x86_64"  -> "https://github.com/proot-me/proot/releases/download/v5.2.0/proot-v5.2.0-x86_64-static"
        else      -> "https://github.com/proot-me/proot/releases/download/v5.2.0/proot-v5.2.0-aarch64-static"
    }

    // Debian minimal rootfs — from Andronix (proven, widely used by Android Linux apps)
    val debianRootfsUrl: String get() = when (arch) {
        "aarch64" -> "https://github.com/AndronixApp/AndronixOrigin/raw/master/Rootfs/Debian/arm64/debian-rootfs-arm64.tar.xz"
        "x86_64"  -> "https://github.com/AndronixApp/AndronixOrigin/raw/master/Rootfs/Debian/x86_64/debian-rootfs-x86_64.tar.xz"
        else      -> "https://github.com/AndronixApp/AndronixOrigin/raw/master/Rootfs/Debian/arm64/debian-rootfs-arm64.tar.xz"
    }

    // ── Paths ─────────────────────────────────────────────────────────────────
    fun baseDir(context: Context)    = File(context.filesDir, "embedded_linux")
    fun prootBin(context: Context)   = File(baseDir(context), "bin/proot")
    fun rootfsDir(context: Context)  = File(baseDir(context), "rootfs")
    fun setupDone(context: Context)  = File(baseDir(context), ".setup_complete")
    fun runtimesFile(context: Context) = File(baseDir(context), ".runtimes_installed")

    // ── State checks ──────────────────────────────────────────────────────────
    fun isReady(context: Context): Boolean =
        prootBin(context).exists() &&
        prootBin(context).canExecute() &&
        rootfsDir(context).exists() &&
        setupDone(context).exists()

    fun runtimesInstalled(context: Context): Boolean = runtimesFile(context).exists()

    fun diskUsageMb(context: Context): Long {
        fun dirSize(d: File): Long = d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return dirSize(baseDir(context)) / (1024 * 1024)
    }

    // ── PRoot command builder ─────────────────────────────────────────────────
    fun buildProotCommand(context: Context, innerCmd: String): List<String> {
        val proot  = prootBin(context).absolutePath
        val rootfs = rootfsDir(context).absolutePath
        return listOf(
            proot,
            "--kill-on-exit",
            "-r", rootfs,
            "-b", "/dev:/dev",
            "-b", "/sys:/sys",
            "-b", "/proc:/proc",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            // Bind app working dir so agent files are accessible
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "/bin/bash", "--login", "-c", innerCmd
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
        timeoutSec: Long = 120L
    ): ExecResult {
        if (!isReady(context)) return ExecResult(-1, "Embedded Linux not ready. Please set it up first.")
        return try {
            val fullCmd = if (hostCwd != null) "cd /workspace && $cmd" else cmd
            val prootCmd = buildProotCommand(context, fullCmd).toMutableList()

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
                put("PROOT_NO_SECCOMP", "1")  // Required on some Android kernels
                put("PROOT_LOADER", prootBin(context).absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
            }
            pb.redirectErrorStream(true)
            val proc    = pb.start()
            val output  = proc.inputStream.bufferedReader().readText()
            val timeout = !proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (timeout) { proc.destroy(); return ExecResult(-1, "Command timed out after ${timeoutSec}s\n${output.take(300)}") }
            ExecResult(proc.exitValue(), output.take(8000).trimEnd())
        } catch (e: Exception) { ExecResult(-1, "PRoot exec error: ${e.message}") }
    }

    /**
     * Install packages inside Debian via apt-get.
     */
    fun install(context: Context, vararg packages: String): ExecResult {
        val pkgList = packages.joinToString(" ")
        return exec(context,
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgList 2>&1",
            timeoutSec = 300L
        )
    }

    /**
     * Run the post-setup DNS and hostname configuration.
     */
    fun configureSystem(context: Context) {
        // DNS
        val resolvConf = File(rootfsDir(context), "etc/resolv.conf")
        if (!resolvConf.exists() || resolvConf.readText().isBlank()) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        // Hostname
        val hostsFile = File(rootfsDir(context), "etc/hosts")
        if (!hostsFile.exists()) {
            hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }
    }

    data class ExecResult(val exitCode: Int, val output: String) {
        val success get() = exitCode == 0
    }
}
