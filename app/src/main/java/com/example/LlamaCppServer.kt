package com.example

import android.content.Context
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LlamaCppServer(private val context: Context) {

    companion object {
        val CURATED_GGUF_MODELS = listOf(
            GGUFModel("Qwen3 0.6B Q4",
                "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
                "Qwen3-0.6B-Q4_K_M.gguf", "~0.4 GB"),
            GGUFModel("Qwen3 1.7B Q4",
                "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                "Qwen3-1.7B-Q4_K_M.gguf", "~1.1 GB"),
            GGUFModel("Qwen3 4B Q4",
                "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
                "Qwen3-4B-Q4_K_M.gguf", "~2.6 GB"),
            GGUFModel("Qwen2.5-Coder 0.5B Q4",
                "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
                "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf", "~0.4 GB"),
            GGUFModel("Qwen2.5-Coder 1.5B Q4",
                "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
                "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", "~1.0 GB"),
            GGUFModel("Qwen2.5-Coder 3B Q4",
                "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
                "qwen2.5-coder-3b-instruct-q4_k_m.gguf", "~2.0 GB"),
            GGUFModel("DeepSeek-R1 1.5B Q4",
                "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
                "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf", "~1.0 GB"),
            GGUFModel("DeepSeek-R1 7B Q4",
                "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
                "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf", "~4.7 GB"),
            GGUFModel("Gemma 3 1B Q4",
                "https://huggingface.co/bartowski/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
                "gemma-3-1b-it-Q4_K_M.gguf", "~0.7 GB"),
            GGUFModel("Llama 3.2 1B Q4",
                "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                "Llama-3.2-1B-Instruct-Q4_K_M.gguf", "~0.7 GB"),
            GGUFModel("Llama 3.2 3B Q4",
                "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                "Llama-3.2-3B-Instruct-Q4_K_M.gguf", "~2.0 GB"),
            GGUFModel("Phi-3.5-mini Q4",
                "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
                "Phi-3.5-mini-instruct-Q4_K_M.gguf", "~2.5 GB"),
            GGUFModel("SmolLM2 1.7B Q4",
                "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
                "SmolLM2-1.7B-Instruct-Q4_K_M.gguf", "~1.1 GB"),
        )
    }

    @Volatile var cancelDownload = false

    val binaryFile: File get() = File(context.applicationInfo.nativeLibraryDir, "libllama_server.so")

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

    // ── Download GGUF model ───────────────────────────────────────────────────
    fun downloadGGUF(
        model: GGUFModel,
        onProgress: (Int, String) -> Unit,
        onDone: (Boolean, String, File?) -> Unit
    ) {
        cancelDownload = false
        Thread {
            val dest = File(modelsDir, model.fileName)
            try {
                onProgress(0, "Starting download…")
                val conn = URL(model.downloadUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connect()
                val total = conn.contentLengthLong
                conn.inputStream.buffered().use { inp ->
                    dest.outputStream().use { out ->
                        var downloaded = 0L
                        val buf = ByteArray(65536)
                        var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            if (cancelDownload) {
                                onDone(false, "❌ Download cancelled", null)
                                return@Thread
                            }
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
                dest.delete()
                if (cancelDownload) onDone(false, "❌ Download cancelled", null)
                else onDone(false, "Download failed: ${e.message}", null)
            }
        }.start()
    }
}

data class GGUFModel(
    val displayName: String,
    val downloadUrl: String,
    val fileName: String,
    val size: String
)
