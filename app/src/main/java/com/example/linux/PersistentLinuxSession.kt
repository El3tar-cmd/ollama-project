package com.example.linux

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File

/**
 * Keeps a PRoot/Alpine process alive indefinitely so long-running servers
 * (python3 -m http.server, node server.js, flask run, etc.) stay up.
 *
 * Lifecycle:
 *   startServer(cmd)  → spawns PRoot, streams output, returns immediately
 *   stopServer()      → destroys the process
 *   isRunning         → observable state
 */
class PersistentLinuxSession(private val context: Context) {

    var isRunning  by mutableStateOf(false)
    var serverCmd  by mutableStateOf("")
    var serverPort by mutableStateOf<Int?>(null)
    val output     = mutableStateListOf<String>()

    private var process:   Process? = null
    private var readerJob: Job?     = null

    // ── Start a background server ─────────────────────────────────────────────
    fun startServer(
        cmd: String,
        hostCwd: String? = null,
        scope: CoroutineScope,
        onLine: (String) -> Unit = {}
    ): Boolean {
        if (!EmbeddedLinux.isReady(context)) {
            pushLine("❌ Embedded Linux not installed — set it up first.")
            return false
        }
        stopServer()
        output.clear()

        return try {
            val fullCmd = if (hostCwd != null) "cd /workspace && $cmd" else cmd
            val prootCmd = EmbeddedLinux.buildProotCommand(context, fullCmd).toMutableList()

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
                put("PROOT_NO_SECCOMP",  "1")
                put("PROOT_TMP_DIR",     prootTmpDir().absolutePath)
                put("PROOT_LOADER",      EmbeddedLinux.loaderBin(context).absolutePath)
                put("PROOT_LOADER_32",   EmbeddedLinux.loader32Bin(context).absolutePath)
                put("LD_LIBRARY_PATH",   EmbeddedLinux.libsDir(context).absolutePath)
                put("TMPDIR",            context.cacheDir.absolutePath)
                put("HOME",              "/root")
                put("USER",              "root")
                put("TERM",              "xterm-256color")
                put("PATH",              "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("LANG",              "C.UTF-8")
                put("PYTHONHASHSEED",    "0")
                put("PYTHONDONTWRITEBYTECODE", "1")
                put("PYTHONNOUSERSITE",  "1")
            }
            pb.redirectErrorStream(true)

            process   = pb.start()
            isRunning = true
            serverCmd = cmd
            serverPort = extractPort(cmd)

            pushLine("🚀 Server started: $cmd")
            serverPort?.let { pushLine("🌐 Listening on port $it — open Browser tab to view") }

            readerJob = scope.launch(Dispatchers.IO) {
                try {
                    process!!.inputStream.bufferedReader().forEachLine { line ->
                        withContext(Dispatchers.Main) { pushLine(line); onLine(line) }
                    }
                } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    isRunning = false
                    pushLine("⏹ Server process exited.")
                }
            }

            scope.launch(Dispatchers.IO) {
                val code = try { process!!.waitFor() } catch (_: Exception) { -1 }
                Log.d("PersistentSession", "Server exited with code $code")
            }

            true
        } catch (e: Exception) {
            pushLine("❌ Failed to start: ${e.message}")
            isRunning = false
            false
        }
    }

    // ── Stop the running server ───────────────────────────────────────────────
    fun stopServer() {
        readerJob?.cancel()
        readerJob = null
        try { process?.destroy() } catch (_: Exception) {}
        process   = null
        isRunning = false
        serverPort = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun pushLine(line: String) {
        output.add(line)
        if (output.size > 600) output.removeAt(0)
    }

    private fun prootTmpDir() =
        File(context.cacheDir, "proot-tmp").also { it.mkdirs() }

    private fun extractPort(cmd: String): Int? {
        // Common port flags: --port 8080 / -p 3000 / http.server 8080
        val patterns = listOf(
            Regex("""(?:--port|-p)\s+(\d{2,5})"""),
            Regex("""http\.server\s+(\d{2,5})"""),
            Regex(""":(\d{2,5})\b""")
        )
        for (rx in patterns) {
            rx.find(cmd)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return when {
            "http.server" in cmd                -> 8000
            "flask" in cmd || "fastapi" in cmd  -> 5000
            "node" in cmd || "npm" in cmd       -> 3000
            "django" in cmd                     -> 8000
            else                                -> null
        }
    }
}
