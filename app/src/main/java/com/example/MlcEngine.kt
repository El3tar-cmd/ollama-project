package com.example

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ─────────────────────────────────────────────────────────────────────────────
// MLC LLM Engine
// Runs entirely in-process via JNI — no external binary, no SELinux issue.
// Supports Vulkan GPU on Samsung Adreno/Mali automatically.
// NOTE: mlc4j AAR not bundled — engine stubs gracefully. Ollama backend is
//       fully functional for all chat/agent/IDE tasks.
// ─────────────────────────────────────────────────────────────────────────────
class MlcEngine(private val context: Context) {

    companion object {
        val CURATED_MODELS = listOf(
            MlcModel("Llama 3.2 1B  (Q4 · ~0.8 GB)",  "mlc-ai/Llama-3.2-1B-Instruct-q4f16_1-MLC",  "Llama-3.2-1B-Instruct-q4f16_1"),
            MlcModel("Llama 3.2 3B  (Q4 · ~2.0 GB)",  "mlc-ai/Llama-3.2-3B-Instruct-q4f16_1-MLC",  "Llama-3.2-3B-Instruct-q4f16_1"),
            MlcModel("Qwen2.5 0.5B  (Q4 · ~0.4 GB)",  "mlc-ai/Qwen2.5-0.5B-Instruct-q4f16_1-MLC",  "Qwen2.5-0.5B-Instruct-q4f16_1"),
            MlcModel("Qwen2.5 1.5B  (Q4 · ~1.0 GB)",  "mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1-MLC",  "Qwen2.5-1.5B-Instruct-q4f16_1"),
            MlcModel("Qwen2.5 3B    (Q4 · ~2.0 GB)",  "mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC",    "Qwen2.5-3B-Instruct-q4f16_1"),
            MlcModel("Qwen3 1.7B    (Q4 · ~1.1 GB)",  "mlc-ai/Qwen3-1.7B-Instruct-q4f16_1-MLC",   "Qwen3-1.7B-Instruct-q4f16_1"),
            MlcModel("Phi-3.5 Mini  (Q4 · ~2.2 GB)",  "mlc-ai/Phi-3.5-mini-instruct-q4f16_1-MLC",  "Phi-3.5-mini-instruct-q4f16_1"),
            MlcModel("Gemma 2 2B    (Q4 · ~1.6 GB)",  "mlc-ai/gemma-2-2b-it-q4f16_1-MLC",          "gemma-2-2b-it-q4f16_1"),
            MlcModel("DeepSeek-R1 1.5B (Q4 · ~1.0 GB)","mlc-ai/DeepSeek-R1-Distill-Qwen-1.5B-q4f16_1-MLC","DeepSeek-R1-Distill-Qwen-1.5B-q4f16_1"),
            MlcModel("SmolLM2 1.7B  (Q4 · ~1.1 GB)",  "mlc-ai/SmolLM2-1.7B-Instruct-q4f16_1-MLC",  "SmolLM2-1.7B-Instruct-q4f16_1"),
        )

        private const val MLC_UNAVAILABLE = "MLC LLM library not bundled in this build — use Ollama backend."
    }

    // ── Engine state ──────────────────────────────────────────────────────────
    @Volatile var isLoaded  = false
    @Volatile var isLoading = false
    var loadedModelName: String? = null
    val logLines = mutableListOf<String>()

    val modelsDir: File get() = File(
        context.getExternalFilesDir(null) ?: context.filesDir, "mlc_models"
    ).also { it.mkdirs() }

    // ── Scan local MLC model dirs ─────────────────────────────────────────────
    fun scanLocalModels(): List<File> =
        modelsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "mlc-chat-config.json").exists() }
            ?.sortedBy { it.name }
            ?: emptyList()

    // ── Load a model into the engine ──────────────────────────────────────────
    fun loadModel(modelDir: File, onLog: (String) -> Unit) {
        onLog("⚠️ $MLC_UNAVAILABLE")
        onLog("💡 Switch to Ollama backend in Settings to use local models.")
    }

    fun unloadModel() {
        isLoaded = false
        loadedModelName = null
    }

    // ── Chat (blocking — call from a background thread / IO coroutine) ────────
    fun chatBlocking(
        messages: List<ChatMessage>,
        temperature: Float = 0.7f,
        maxTokens: Int   = 2048,
        onToken: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        onComplete(false, MLC_UNAVAILABLE)
    }

    // ── Download MLC model from HuggingFace ───────────────────────────────────
    @Volatile var cancelDownload = false

    fun downloadModel(
        model: MlcModel,
        onProgress: (Int, String) -> Unit,
        onDone: (Boolean, String, File?) -> Unit
    ) {
        cancelDownload = false
        Thread {
            try {
                val destDir = File(modelsDir, model.localName).also { it.mkdirs() }
                onProgress(0, "Fetching file list from HuggingFace…")

                val apiConn = URL("https://huggingface.co/api/models/${model.hfRepo}")
                    .openConnection() as HttpURLConnection
                apiConn.connectTimeout = 30_000; apiConn.readTimeout = 30_000
                apiConn.connect()
                if (apiConn.responseCode != 200) {
                    onDone(false, "❌ HF API ${apiConn.responseCode}", null); return@Thread
                }
                val json  = apiConn.inputStream.bufferedReader().readText()
                apiConn.disconnect()
                val siblings = JSONObject(json).optJSONArray("siblings")
                    ?: run { onDone(false, "❌ No files in repo", null); return@Thread }

                val files = (0 until siblings.length())
                    .map { siblings.getJSONObject(it).getString("rfilename") }
                    .filter { fname ->
                        !fname.endsWith(".so") &&
                        !fname.startsWith(".") &&
                        fname != ".gitattributes"
                    }

                onProgress(2, "Downloading ${files.size} files…")
                var downloaded = 0L
                val estTotal   = 800_000_000L

                files.forEachIndexed { idx, fname ->
                    if (cancelDownload) { onDone(false, "❌ Cancelled", null); return@Thread }

                    val dest = File(destDir, fname.substringAfterLast('/'))
                    if (dest.exists() && dest.length() > 0) {
                        onProgress(3 + (idx + 1) * 90 / files.size, "Exists: ${dest.name}")
                        return@forEachIndexed
                    }
                    dest.parentFile?.mkdirs()

                    val fileUrl = "https://huggingface.co/${model.hfRepo}/resolve/main/$fname"
                    val fc = URL(fileUrl).openConnection() as HttpURLConnection
                    fc.instanceFollowRedirects = true
                    fc.connectTimeout = 30_000; fc.readTimeout = 600_000
                    fc.connect()
                    if (fc.responseCode != 200) { fc.disconnect(); return@forEachIndexed }

                    fc.inputStream.use { inp ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(65536); var n: Int
                            while (inp.read(buf).also { n = it } != -1) {
                                if (cancelDownload) {
                                    onDone(false, "❌ Cancelled", null); return@Thread
                                }
                                out.write(buf, 0, n); downloaded += n
                                val pct = (3 + downloaded * 90 / estTotal).toInt().coerceAtMost(93)
                                val mb  = "%.1f".format(downloaded / 1_000_000.0)
                                onProgress(pct, "[${idx+1}/${files.size}] ${dest.name} · ${mb}MB")
                            }
                        }
                    }
                    fc.disconnect()
                }

                if (!File(destDir, "mlc-chat-config.json").exists()) {
                    onDone(false, "❌ Incomplete download — config missing", null); return@Thread
                }
                onProgress(100, "✅ Done")
                onDone(true, "✅ ${model.displayName} downloaded", destDir)
            } catch (e: Exception) {
                onDone(false, "❌ ${e.message}", null)
            }
        }.start()
    }
}

data class MlcModel(
    val displayName: String,
    val hfRepo: String,
    val localName: String
)
