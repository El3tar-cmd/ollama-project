package com.example.terminal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Handles Termux integration for executing Python, Node.js, and other runtimes.
 *
 * Strategy (tried in order):
 * 1. Direct execution — works when Termux has allow-external-apps=true
 * 2. Shell PATH injection — run via /system/bin/sh with Termux PATH
 * 3. Termux:API intent — fire-and-forget if above fail
 *
 * Setup requirements:
 *   In Termux, run:  echo "allow-external-apps=true" >> ~/.termux/termux.properties
 *   Then restart Termux.
 */
object TermuxBridge {

    private const val TERMUX_PKG       = "com.termux"
    private const val TERMUX_PREFIX    = "/data/data/com.termux/files/usr"
    private const val TERMUX_BIN       = "$TERMUX_PREFIX/bin"
    private const val TERMUX_LIB       = "$TERMUX_PREFIX/lib"
    private const val TERMUX_HOME      = "/data/data/com.termux/files/home"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    fun isTermuxAccessible(): Boolean {
        val bin = File(TERMUX_BIN)
        return bin.exists() && bin.canRead()
    }

    /**
     * True if external apps can execute Termux binaries
     * (i.e. allow-external-apps=true is configured).
     */
    fun canExecuteTermuxBinaries(): Boolean {
        val py = File("$TERMUX_BIN/python3")
        if (!py.exists()) return false
        return try {
            val pb = ProcessBuilder(py.absolutePath, "--version")
            pb.environment()["LD_LIBRARY_PATH"] = TERMUX_LIB
            val p = pb.start()
            p.waitFor(3, TimeUnit.SECONDS)
            p.exitValue() == 0
        } catch (_: Exception) { false }
    }

    /**
     * Build the environment map needed to run Termux binaries.
     */
    fun buildTermuxEnv(context: Context, extraBinDir: String? = null): Map<String, String> = buildMap {
        val appNativeLib = context.applicationInfo.nativeLibraryDir
        put("HOME",   TERMUX_HOME.takeIf { File(it).exists() } ?: context.filesDir.absolutePath)
        put("TMPDIR", context.cacheDir.absolutePath)
        put("PREFIX", TERMUX_PREFIX)
        put("PATH", listOfNotNull(TERMUX_BIN, extraBinDir, "/system/bin", "/system/usr/bin", "/usr/bin")
            .distinct().joinToString(":"))
        put("LD_LIBRARY_PATH", listOfNotNull(TERMUX_LIB, appNativeLib)
            .filter { File(it).exists() }.distinct().joinToString(":"))
        put("PYTHONIOENCODING",        "utf-8")
        put("PYTHONDONTWRITEBYTECODE", "1")
        put("NODE_PATH", "$TERMUX_PREFIX/lib/node_modules")
    }

    /**
     * Try to run a quick command via /system/bin/sh with the Termux PATH injected.
     * This works as a fallback even without allow-external-apps in some ROM configs.
     */
    fun executeViaShell(
        context: Context,
        command: String,
        workingDir: String,
        timeoutSec: Long = 60
    ): Pair<Int, String> {
        return try {
            val env = buildTermuxEnv(context)
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            pb.directory(File(workingDir.takeIf { File(it).exists() } ?: context.filesDir.absolutePath))
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val proc   = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val ok     = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!ok) { proc.destroy(); return -1 to "timed out after ${timeoutSec}s\n${output.take(200)}" }
            proc.exitValue() to output.take(4000).trimEnd()
        } catch (e: Exception) { -1 to "exec error: ${e.message}" }
    }

    /**
     * Find Python — tries app-local first, then Termux.
     */
    fun findPython(context: Context): String? {
        val local = File(context.filesDir, "runtimes/bin/python3")
        if (local.exists() && local.canExecute()) return local.absolutePath
        return listOf("$TERMUX_BIN/python3", "$TERMUX_BIN/python").firstOrNull { File(it).exists() }
    }

    /**
     * Find Node.js — tries app-local first, then Termux.
     */
    fun findNode(context: Context): String? {
        val local = File(context.filesDir, "runtimes/bin/node")
        if (local.exists() && local.canExecute()) return local.absolutePath
        return listOf("$TERMUX_BIN/node", "$TERMUX_BIN/nodejs").firstOrNull { File(it).exists() }
    }

    /**
     * Returns a human-readable setup guide for the user to enable integration.
     */
    fun setupGuide(): String = """
        To enable Python/Node.js in DevHive without Termux permission issues:
        
        1. Open Termux and run:
           echo "allow-external-apps=true" >> ~/.termux/termux.properties
        
        2. Install the runtimes you need:
           pkg install python nodejs
        
        3. Restart Termux completely (close and reopen).
        
        4. Restart DevHive.
        
        After this, run_python and run_node tools will work seamlessly.
    """.trimIndent()

    /**
     * Send a fire-and-forget command to Termux via its RUN_COMMAND intent.
     * Requires Termux:API and TERMUX_RUN_COMMAND permission.
     * Does NOT return output — use for side-effects only.
     */
    fun sendRunCommandIntent(context: Context, path: String, args: Array<String>): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(TERMUX_PKG, "$TERMUX_PKG.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH",        path)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS",   args)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND",  true)
            }
            context.startService(intent)
            true
        } catch (_: Exception) { false }
    }
}
