package com.example

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit
import java.io.FileOutputStream
import java.io.IOException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class OllamaExecutor(private val context: Context) {
    private val TAG = "OllamaExecutor"

    val binDir    = File(context.filesDir, "bin")
    val modelsDir = File(context.filesDir, "ollama_models")
    
    // Track background threads for proper cleanup
    private val activeThreads = mutableSetOf<Thread>()
    private val activeProcesses = mutableSetOf<Process>()

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
            connection.inputStream.use { inputStream ->
                BufferedInputStream(inputStream).use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(32_768)
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                            downloaded += n
                            if (total > 0) onProgress((downloaded * 100 / total).toInt())
                        }
                        output.flush()
                    }
                }
            }
            connection.disconnect()
            if (!tmpFile.renameTo(ollamaFile)) {
                throw IOException("Failed to rename temporary file to ${ollamaFile.absolutePath}")
            }
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
        Log.d(TAG, ">>> startAndStream() called")
        Log.d(TAG, "    command: $command")
        
        val pb = ProcessBuilder(command)
        pb.environment().putAll(envMap)
        pb.redirectErrorStream(true)
        
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "!!! Process start failed: ${e.message}", e)
            onLog("!!! Process start failed: ${e.message}")
            throw e
        }
        
        Log.d(TAG, "    Process started successfully, pid: ${proc.pid}")
        synchronized(activeProcesses) { activeProcesses.add(proc) }
        
        val thread = Thread {
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) onLog(line ?: "")
                }
                Log.d(TAG, "    Process output stream closed (EOF)")
            } catch (e: Exception) { 
                Log.e(TAG, "    Log pipe error: ${e.message}", e)
                onLog("Log pipe closed: ${e.message}") 
            }
            finally {
                synchronized(activeProcesses) { activeProcesses.remove(proc) }
            }
        }
        synchronized(activeThreads) { activeThreads.add(thread) }
        thread.start()
        
        return proc
    }

    fun startOllamaService(
        host: String = "127.0.0.1:11434",
        origins: String = "*",
        apiKey: String = "",
        onLog: (String) -> Unit
    ): Process? {
        Log.d(TAG, ">>> startOllamaService() called")
        
        setupEnvironment()

        val binary = resolveBinary()
        if (binary == null) {
            Log.e(TAG, "!!! Binary not found - resolveBinary() returned null")
            onLog("Error: Ollama binary not found. Tap Install to download it.")
            return null
        }
        Log.d(TAG, "    Binary resolved: ${binary.absolutePath}, exists: ${binary.exists()}")

        val envMap = buildEnv(host, origins, apiKey)
        Log.d(TAG, "    Environment built, OLLAMA_HOST: $host")

        // ── Strategy 0: native lib (always executable — no SELinux restriction) ──
        if (binary == nativeBinary) {
            Log.d(TAG, "    Strategy 0: Using native lib")
            onLog("Starting via nativeLibraryDir: ${binary.absolutePath}")
            try {
                return startAndStream(listOf(binary.absolutePath, "serve"), envMap, onLog)
            } catch (e: Exception) {
                Log.e(TAG, "    Strategy 0 failed: ${e.message}", e)
                onLog("Native lib exec failed: ${e.message}")
            }
        }

        // ── Strategy 1: Direct execution of downloaded binary ──
        Log.d(TAG, "    Strategy 1: Direct execution")
        try {
            return startAndStream(listOf(binary.absolutePath, "serve"), envMap, onLog)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            Log.e(TAG, "    Strategy 1 failed: $msg")
            if (!msg.contains("error=13") && !msg.contains("Permission denied")) {
                onLog("Failed to start Ollama: $msg")
                return null
            }
            onLog("Direct exec denied (SELinux/noexec). Trying fallbacks...")
        }

        // ── Strategy 2: Copy to externalCacheDir (may be on exec-allowed fs) ──
        context.externalCacheDir?.let { extCache ->
            Log.d(TAG, "    Strategy 2: External cache copy")
            try {
                val extBin = File(extCache, "ollama")
                binary.copyTo(extBin, overwrite = true)
                extBin.setExecutable(true, false)
                onLog("Trying external cache: ${extBin.absolutePath}")
                return startAndStream(listOf(extBin.absolutePath, "serve"), envMap, onLog)
            } catch (e2: Exception) {
                Log.e(TAG, "    Strategy 2 failed: ${e2.message}", e2)
                onLog("External cache fallback failed: ${e2.message}")
            }
        }

        // ── Strategy 3: Android dynamic linker ──
        Log.d(TAG, "    Strategy 3: Android linker")
        for (linker in listOf("/system/bin/linker64", "/apex/com.android.runtime/bin/linker64")) {
            if (File(linker).exists()) {
                try {
                    onLog("Trying linker64: $linker")
                    return startAndStream(listOf(linker, binary.absolutePath, "serve"), envMap, onLog)
                } catch (e3: Exception) {
                    Log.e(TAG, "    Strategy 3 ($linker) failed: ${e3.message}", e3)
                    onLog("linker64 fallback failed: ${e3.message}")
                }
            }
        }

        Log.e(TAG, "!!! All strategies failed!")
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
            process?.waitFor(2, TimeUnit.SECONDS)
            if (process?.isAlive == true) {
                process.destroyForcibly()
            }
            synchronized(activeProcesses) { activeProcesses.remove(process) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping process", e)
        }
    }
    
    /**
     * Clean up all active threads and processes - call this when app is shutting down
     */
    fun cleanup() {
        synchronized(activeProcesses) {
            activeProcesses.toList().forEach { proc ->
                try {
                    if (proc.isAlive) {
                        proc.destroy()
                        proc.waitFor(1, TimeUnit.SECONDS)
                        if (proc.isAlive) proc.destroyForcibly()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up process", e)
                }
            }
            activeProcesses.clear()
        }
        
        synchronized(activeThreads) {
            activeThreads.toList().forEach { thread ->
                try {
                    if (thread.isAlive) thread.interrupt()
                } catch (e: Exception) {
                    Log.e(TAG, "Error interrupting thread", e)
                }
            }
            activeThreads.clear()
        }
    }

    /**
     * Run an arbitrary ollama sub-command and stream its output.
     * Uses the same environment as the daemon (including HOME and OLLAMA_API_KEY).
     */
    fun execOllamaCommand(vararg args: String, apiKey: String = "", onLog: (String) -> Unit) {
        Log.d(TAG, ">>> execOllamaCommand() called with args: ${args.toList()}")
        
        setupEnvironment()
        val binary = resolveBinary()
        if (binary == null) {
            Log.e(TAG, "!!! Binary not found in execOllamaCommand")
            onLog("Error: Ollama binary not found.")
            return
        }
        Log.d(TAG, "    Binary: ${binary.absolutePath}")
        
        val envMap = buildEnv("127.0.0.1:11434", "*", apiKey)
        val thread = Thread {
            try {
                val cmd = listOf(binary.absolutePath) + args.toList()
                Log.d(TAG, "    Running command: $cmd")
                
                val proc = ProcessBuilder(cmd)
                    .apply {
                        environment().putAll(envMap)
                        redirectErrorStream(true)
                    }.start()
                
                Log.d(TAG, "    Process started, pid: ${proc.pid}")
                synchronized(activeProcesses) { activeProcesses.add(proc) }
                
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "    [OUT] $line")
                            onLog(line ?: "")
                        }
                    }
                    val exitCode = proc.waitFor()
                    Log.d(TAG, "    Process exited with code: $exitCode")
                } catch (e: Exception) {
                    Log.e(TAG, "    Command execution error: ${e.message}", e)
                    onLog("Command failed: ${e.message}")
                } finally {
                    synchronized(activeProcesses) { activeProcesses.remove(proc) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "!!! Command failed to start: ${e.message}", e)
                onLog("Command failed: ${e.message}")
            }
        }
        synchronized(activeThreads) { activeThreads.add(thread) }
        thread.start()
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
        Log.d(TAG, ">>> execLogin() called")
        
        setupEnvironment()
        val binary = resolveBinary() ?: run { 
            Log.e(TAG, "!!! Binary not found in execLogin")
            onLog("Error: binary not found."); return null 
        }
        Log.d(TAG, "    Binary: ${binary.absolutePath}")
        
        val envMap = buildEnv("127.0.0.1:11434", "*")
        return try {
            Log.d(TAG, "    Starting login process...")
            val proc = ProcessBuilder(listOf(binary.absolutePath, "login"))
                .apply {
                    environment().putAll(envMap)
                    redirectErrorStream(true)
                }.start()
            
            Log.d(TAG, "    Process started, pid: ${proc.pid}")
            synchronized(activeProcesses) { activeProcesses.add(proc) }
            
            val thread = Thread {
                try {
                    proc.inputStream.use { stream ->
                        val sb     = StringBuilder()
                        val buf    = ByteArray(1)
                        while (stream.read(buf) != -1) {
                            val ch = buf[0].toInt().toChar()
                            if (ch == '\n' || ch == '\r') {
                                val line = sb.toString().trim()
                                if (line.isNotBlank()) {
                                    Log.d(TAG, "    [LOGIN] $line")
                                    onLog(line)
                                }
                                sb.clear()
                            } else {
                                sb.append(ch)
                                // Emit immediately when we see a full connect URL
                                // (binary may not print a trailing newline)
                                val partial = sb.toString()
                                if (partial.contains("https://ollama.com/connect") &&
                                    partial.length > 40 &&
                                    !partial.endsWith("connect")) {
                                    Log.d(TAG, "    [LOGIN URL] $partial")
                                    onLog(partial.trim())
                                    sb.clear()
                                }
                            }
                        }
                        if (sb.toString().isNotBlank()) {
                            Log.d(TAG, "    [LOGIN] ${sb.toString()}")
                            onLog(sb.toString().trim())
                        }
                    }
                } catch (e: Exception) { 
                    Log.e(TAG, "    Login stream error: ${e.message}", e)
                    onLog("Login stream: ${e.message}") 
                }
                finally {
                    synchronized(activeProcesses) { activeProcesses.remove(proc) }
                }
            }
            synchronized(activeThreads) { activeThreads.add(thread) }
            thread.start()
            
            proc
        } catch (e: Exception) {
            Log.e(TAG, "!!! Login failed: ${e.message}", e)
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
