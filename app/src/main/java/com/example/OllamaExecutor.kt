package com.example

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class OllamaExecutor(private val context: Context) {
    private val TAG = "OllamaExecutor"

    val binDir    = File(context.filesDir, "bin")
    val modelsDir = File(context.filesDir, "ollama_models")

    /**
     * PRIMARY binary: libollama.so extracted by the package installer into
     * nativeLibraryDir.  That directory lives on a dedicated exec-allowed
     * partition and is never affected by noexec / SELinux app_data_file policy.
     */
    private val nativeBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libollama.so")

    /**
     * FALLBACK binary: user-downloaded copy in internal filesDir.
     * May be blocked by SELinux on Samsung/Knox devices.
     */
    val ollamaFile: File
        get() = File(binDir, "ollama")

    /** Returns the binary that actually exists and should be used. */
    private fun resolveBinary(): File? {
        if (nativeBinary.exists()) return nativeBinary
        if (ollamaFile.exists())   return ollamaFile
        return null
    }

    fun setupEnvironment() {
        if (!binDir.exists()) binDir.mkdirs()
        if (!modelsDir.exists()) modelsDir.mkdirs()
        // chmod only needed for the fallback copy in filesDir
        if (ollamaFile.exists()) {
            ollamaFile.setExecutable(true, false)
        }
    }

    /** Check whether a usable binary is present (native lib or downloaded). */
    fun isBinaryInstalled(): Boolean = resolveBinary() != null

    fun downloadBinary(
        url: String = "https://github.com/El3tar-cmd/ollama-project/raw/main/app/src/main/jniLibs/arm64-v8a/libollama.so",
        onProgress: (Int) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): Boolean {
        setupEnvironment()
        // If the native lib is already present, skip download entirely
        if (nativeBinary.exists()) {
            onLog("Native binary ready at: ${nativeBinary.absolutePath}")
            onProgress(100)
            return true
        }
        return try {
            onLog("Downloading Ollama binary...")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout    = 60_000
            connection.connect()
            val total = connection.contentLengthLong
            var downloaded = 0L
            val tmpFile = File(binDir, "ollama.tmp")
            val input  = BufferedInputStream(connection.inputStream)
            val output = FileOutputStream(tmpFile)
            val buffer = ByteArray(32_768)
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                output.write(buffer, 0, n)
                downloaded += n
                if (total > 0) onProgress((downloaded * 100 / total).toInt())
            }
            output.flush(); output.close(); input.close(); connection.disconnect()
            tmpFile.renameTo(ollamaFile)
            ollamaFile.setExecutable(true, false)
            onLog("Download complete: ${ollamaFile.absolutePath}")
            true
        } catch (e: Exception) {
            onLog("Download failed: ${e.message}")
            Log.e(TAG, "Download failed", e)
            false
        }
    }

    /**
     * Build env vars for the Ollama process.
     * @param apiKey  Ollama cloud API key — passed as OLLAMA_API_KEY so the
     *                daemon can authenticate with ollama.com for cloud models.
     */
    fun buildEnv(
        host: String,
        origins: String,
        apiKey: String = "",
        extraEnv: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val ldPath = listOf(
            context.applicationInfo.nativeLibraryDir,
            "/system/lib64",
            "/apex/com.android.runtime/lib64",
            "/apex/com.android.art/lib64",
            "/vendor/lib64"
        ).joinToString(":")
        val base = mutableMapOf(
            "OLLAMA_HOST"     to host,
            "OLLAMA_MODELS"   to modelsDir.absolutePath,
            "OLLAMA_ORIGINS"  to origins,
            "HOME"            to context.filesDir.absolutePath,
            "TMPDIR"          to context.cacheDir.absolutePath,
            "LD_LIBRARY_PATH" to ldPath
        )
        if (apiKey.isNotBlank()) {
            base["OLLAMA_API_KEY"] = apiKey
        }
        return base + extraEnv
    }

    private fun startAndStream(
        command: List<String>,
        envMap: Map<String, String>,
        onLog: (String) -> Unit
    ): Process {
        val pb = ProcessBuilder(command)
        pb.environment().putAll(envMap)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        Thread {
            try {
                val reader = proc.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) onLog(line ?: "")
            } catch (e: Exception) { onLog("Log pipe closed: ${e.message}") }
        }.start()
        return proc
    }

    fun startOllamaService(
        host: String = "127.0.0.1:11434",
        origins: String = "*",
        apiKey: String = "",
        onLog: (String) -> Unit
    ): Process? {
        setupEnvironment()

        val binary = resolveBinary()
        if (binary == null) {
            onLog("Error: Ollama binary not found. Tap Install to download it.")
            return null
        }

        val envMap = buildEnv(host, origins, apiKey)

        // ── Strategy 0: native lib (always executable — no SELinux restriction) ──
        if (binary == nativeBinary) {
            onLog("Starting via nativeLibraryDir: ${binary.absolutePath}")
            try {
                return startAndStream(listOf(binary.absolutePath, "serve"), envMap, onLog)
            } catch (e: Exception) {
                onLog("Native lib exec failed: ${e.message}")
            }
        }

        // ── Strategy 1: Direct execution of downloaded binary ──
        try {
            return startAndStream(listOf(binary.absolutePath, "serve"), envMap, onLog)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (!msg.contains("error=13") && !msg.contains("Permission denied")) {
                onLog("Failed to start Ollama: $msg")
                return null
            }
            onLog("Direct exec denied (SELinux/noexec). Trying fallbacks...")
        }

        // ── Strategy 2: Copy to externalCacheDir (may be on exec-allowed fs) ──
        context.externalCacheDir?.let { extCache ->
            try {
                val extBin = File(extCache, "ollama")
                binary.copyTo(extBin, overwrite = true)
                extBin.setExecutable(true, false)
                onLog("Trying external cache: ${extBin.absolutePath}")
                return startAndStream(listOf(extBin.absolutePath, "serve"), envMap, onLog)
            } catch (e2: Exception) {
                onLog("External cache fallback failed: ${e2.message}")
            }
        }

        // ── Strategy 3: Android dynamic linker ──
        for (linker in listOf("/system/bin/linker64", "/apex/com.android.runtime/bin/linker64")) {
            if (File(linker).exists()) {
                try {
                    onLog("Trying linker64: $linker")
                    return startAndStream(listOf(linker, binary.absolutePath, "serve"), envMap, onLog)
                } catch (e3: Exception) {
                    onLog("linker64 fallback failed: ${e3.message}")
                }
            }
        }

        onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        onLog("All execution strategies failed.")
        onLog("Device SELinux policy blocks binary execution from user storage.")
        onLog("• Reinstall the APK — the bundled libollama.so should fix this")
        onLog("• Or try on a non-Knox Android device")
        onLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        return null
    }

    fun stopOllamaService(process: Process?) {
        try {
            process?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping process", e)
        }
    }

    /**
     * Run an arbitrary ollama sub-command and stream its output.
     * Uses the same environment as the daemon (including HOME and OLLAMA_API_KEY).
     */
    fun execOllamaCommand(vararg args: String, apiKey: String = "", onLog: (String) -> Unit) {
        setupEnvironment()
        val binary = resolveBinary()
        if (binary == null) {
            onLog("Error: Ollama binary not found.")
            return
        }
        val envMap = buildEnv("127.0.0.1:11434", "*", apiKey)
        Thread {
            try {
                val proc = ProcessBuilder(listOf(binary.absolutePath) + args.toList())
                    .apply {
                        environment().putAll(envMap)
                        redirectErrorStream(true)
                    }.start()
                val reader = proc.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) onLog(line ?: "")
                proc.waitFor()
            } catch (e: Exception) {
                onLog("Command failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Run `ollama login` and stream output character-by-character.
     *
     * Why not readLine(): Go binaries buffer stdout when writing to a pipe,
     * so the connect URL line may never reach readLine() until the process exits.
     * Reading one char at a time + checking for the URL in partial content
     * ensures we surface the URL as soon as the binary writes it.
     */
    fun execLogin(onLog: (String) -> Unit): Process? {
        setupEnvironment()
        val binary = resolveBinary() ?: run { onLog("Error: binary not found."); return null }
        val envMap = buildEnv("127.0.0.1:11434", "*")
        return try {
            val proc = ProcessBuilder(listOf(binary.absolutePath, "login"))
                .apply {
                    environment().putAll(envMap)
                    redirectErrorStream(true)
                }.start()
            Thread {
                try {
                    val stream = proc.inputStream
                    val sb     = StringBuilder()
                    val buf    = ByteArray(1)
                    while (stream.read(buf) != -1) {
                        val ch = buf[0].toInt().toChar()
                        if (ch == '\n' || ch == '\r') {
                            val line = sb.toString().trim()
                            if (line.isNotBlank()) onLog(line)
                            sb.clear()
                        } else {
                            sb.append(ch)
                            // Emit immediately when we see a full connect URL
                            // (binary may not print a trailing newline)
                            val partial = sb.toString()
                            if (partial.contains("https://ollama.com/connect") &&
                                partial.length > 40 &&
                                !partial.endsWith("connect")) {
                                onLog(partial.trim())
                                sb.clear()
                            }
                        }
                    }
                    if (sb.isNotBlank()) onLog(sb.toString().trim())
                } catch (e: Exception) { onLog("Login stream: ${e.message}") }
            }.start()
            proc
        } catch (e: Exception) {
            onLog("login failed: ${e.message}")
            null
        }
    }

    /**
     * Run `ollama logout` — clears stored credentials from the binary.
     */
    fun execLogout(apiKey: String = "", onLog: (String) -> Unit) {
        execOllamaCommand("logout", apiKey = apiKey, onLog = onLog)
    }

    /**
     * Scan $HOME/.ollama/ for a credential/token file written by `ollama signin`.
     * Returns the raw content of the first plausible candidate, or null if nothing found.
     */
    fun readStoredCredential(): String? {
        val ollamaDir = File(context.filesDir, ".ollama")
        if (!ollamaDir.exists()) return null
        // Candidate file names the Ollama binary might write
        val candidates = listOf("api_key", "credentials", "token", "auth", ".credentials", "config")
        for (name in candidates) {
            val f = File(ollamaDir, name)
            if (f.exists() && f.isFile && f.length() < 4096) {
                val content = f.readText().trim()
                if (content.isNotBlank()) return content
            }
        }
        // Fallback: any small non-key file created recently in .ollama
        ollamaDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
            if (f.isFile && f.length() < 4096 &&
                !f.name.endsWith(".pub") && f.name != "id_ed25519") {
                val content = f.readText().trim()
                if (content.isNotBlank()) return content
            }
        }
        return null
    }
}
