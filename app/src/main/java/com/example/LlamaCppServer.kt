package com.example

import android.content.Context
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

// ─────────────────────────────────────────────────────────────────────────────
// Manages the llama-server binary and process lifecycle
// ─────────────────────────────────────────────────────────────────────────────
class LlamaCppServer(private val context: Context) {

    companion object {
        const val BINARY_RELEASE = "b9744"
        const val BINARY_URL =
            "https://github.com/ggml-org/llama.cpp/releases/download/$BINARY_RELEASE/llama-$BINARY_RELEASE-bin-android-arm64.tar.gz"

        val CURATED_GGUF_MODELS = listOf(
            GGUFModel("Qwen2.5-Coder 1.5B Q4",
                "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
                "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", "~1.0 GB"),
            GGUFModel("Qwen2.5-Coder 3B Q4",
                "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
                "qwen2.5-coder-3b-instruct-q4_k_m.gguf", "~2.0 GB"),
            GGUFModel("Qwen2.5-Coder 7B Q4",
                "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
                "qwen2.5-coder-7b-instruct-q4_k_m.gguf", "~4.7 GB"),
            GGUFModel("DeepSeek-Coder-V2-Lite Q4",
                "https://huggingface.co/bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF/resolve/main/DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf",
                "DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf", "~9.7 GB"),
            GGUFModel("Phi-3.5-mini Q4",
                "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
                "Phi-3.5-mini-instruct-Q4_K_M.gguf", "~2.5 GB"),
            GGUFModel("SmolLM2 1.7B Q4",
                "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
                "SmolLM2-1.7B-Instruct-Q4_K_M.gguf", "~1.1 GB"),
            GGUFModel("Granite-3.1-2B Q4",
                "https://huggingface.co/bartowski/granite-3.1-2b-instruct-GGUF/resolve/main/granite-3.1-2b-instruct-Q4_K_M.gguf",
                "granite-3.1-2b-instruct-Q4_K_M.gguf", "~1.6 GB"),
        )
    }

    private var serverProcess: Process? = null
    val logBuffer = mutableListOf<String>()

    val binaryFile: File get() = File(context.filesDir, "bin/llama-server")

    val isBinaryInstalled: Boolean get() = binaryFile.exists() && binaryFile.canExecute()

    val modelsDir: File get() = File(
        context.getExternalFilesDir(null) ?: context.filesDir, "gguf_models"
    ).also { it.mkdirs() }

    // ── Find all GGUF files ───────────────────────────────────────────────────
    fun scanLocalGGUFs(): List<File> {
        val found = mutableListOf<File>()
        found.addAll(modelsDir.walkTopDown().filter { it.extension == "gguf" }.toList())
        val extPaths = listOf(
            Environment.getExternalStorageDirectory().absolutePath + "/Download",
            Environment.getExternalStorageDirectory().absolutePath + "/Documents",
        )
        for (path in extPaths) {
            val dir = File(path)
            if (dir.exists() && dir.canRead()) {
                found.addAll(dir.walkTopDown().maxDepth(3).filter { it.extension == "gguf" }.toList())
            }
        }
        return found.distinctBy { it.absolutePath }
    }

    // ── Download binary (tar.gz) ──────────────────────────────────────────────
    fun downloadBinary(
        url: String = BINARY_URL,
        onProgress: (Int, String) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                onProgress(0, "Connecting…")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 300_000
                conn.connect()

                val total = conn.contentLengthLong
                val binDir = File(context.filesDir, "bin").also { it.mkdirs() }

                // Stream the .tar.gz and parse manually (no Apache Commons needed)
                val gzip = GZIPInputStream(conn.inputStream.buffered(65536))

                var serverFound = false
                var libFound = false

                // Parse TAR format manually
                val headerBuf = ByteArray(512)
                while (true) {
                    var read = 0
                    while (read < 512) {
                        val n = gzip.read(headerBuf, read, 512 - read)
                        if (n == -1) break
                        read += n
                    }
                    if (read < 512) break

                    // Check for end-of-archive (two zero blocks)
                    if (headerBuf.all { it == 0.toByte() }) break

                    // Parse name (bytes 0-99) and size (bytes 124-135, octal)
                    val name = String(headerBuf, 0, 100).trimEnd('\u0000').trim()
                    val sizeStr = String(headerBuf, 124, 12).trimEnd('\u0000').trim()
                    val fileSize = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)

                    val isLlamaServer = name.endsWith("llama-server") && !name.endsWith("/")
                    val isLibImpl    = name.contains("libllama-server-impl.so")

                    if (isLlamaServer || isLibImpl) {
                        val dest = if (isLlamaServer) binaryFile else File(binDir, "libllama-server-impl.so")
                        onProgress(0, "Extracting ${dest.name}…")
                        var written = 0L
                        val buf = ByteArray(65536)
                        val out = dest.outputStream()
                        while (written < fileSize) {
                            val toRead = minOf(buf.size.toLong(), fileSize - written).toInt()
                            val n = gzip.read(buf, 0, toRead)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) onProgress((written * 100 / total).toInt(), "Extracting ${dest.name}…")
                        }
                        out.flush(); out.close()
                        dest.setExecutable(true, false)

                        // Skip padding to next 512-byte block
                        val pad = ((fileSize + 511) / 512 * 512 - fileSize).toInt()
                        if (pad > 0) gzip.skip(pad.toLong())

                        if (isLlamaServer) serverFound = true
                        if (isLibImpl)     libFound = true
                    } else {
                        // Skip file data + padding
                        val toSkip = (fileSize + 511) / 512 * 512
                        var skipped = 0L
                        while (skipped < toSkip) {
                            val s = gzip.skip(toSkip - skipped)
                            if (s <= 0) break
                            skipped += s
                        }
                    }

                    if (serverFound && libFound) break
                }

                gzip.close()
                conn.disconnect()

                when {
                    serverFound -> onDone(true, "llama-server $BINARY_RELEASE installed ✅" +
                        if (!libFound) " (libllama-server-impl.so not found — may still work)" else "")
                    else        -> onDone(false, "llama-server binary not found in archive")
                }
            } catch (e: Exception) {
                onDone(false, "Download failed: ${e.message}")
            }
        }.start()
    }

    // ── Download GGUF model ───────────────────────────────────────────────────
    fun downloadGGUF(
        model: GGUFModel,
        onProgress: (Int, String) -> Unit,
        onDone: (Boolean, String, File?) -> Unit
    ) {
        Thread {
            try {
                onProgress(0, "Starting download…")
                val dest = File(modelsDir, model.fileName)
                val conn = URL(model.downloadUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connect()
                val total = conn.contentLength.toLong()
                conn.inputStream.buffered().use { inp ->
                    dest.outputStream().use { out ->
                        var downloaded = 0L
                        val buf = ByteArray(65536)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                val mb  = downloaded / 1_048_576
                                val tmb = total / 1_048_576
                                onProgress(pct, "${mb}MB / ${tmb}MB")
                            }
                        }
                    }
                }
                conn.disconnect()
                onDone(true, "Downloaded: ${model.fileName}", dest)
            } catch (e: Exception) {
                onDone(false, "Download failed: ${e.message}", null)
            }
        }.start()
    }

    // ── Server lifecycle ──────────────────────────────────────────────────────
    fun isRunning(): Boolean = serverProcess?.isAlive ?: false

    fun stop() {
        serverProcess?.destroyForcibly()
        serverProcess = null
    }
}

data class GGUFModel(
    val displayName: String,
    val downloadUrl: String,
    val fileName: String,
    val size: String
)
