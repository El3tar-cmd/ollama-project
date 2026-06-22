package com.example

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import com.example.agent.AgentEngine
import com.example.data.api.LlamaCppApi
import com.example.data.api.OllamaApi
import com.example.data.model.AgentStep
import com.example.data.model.ChatMessage
import com.example.data.model.OllamaModel
import com.example.data.model.ShellLine
import com.example.data.model.ShellLineType
import com.example.ui.editor.getLanguageFromExtension

class MainViewModel(private val ctx: Context) : ViewModel() {
    private val api      = OllamaApi()
    val executor         = OllamaExecutor(ctx)
    val agentEngine      = AgentEngine(ctx)

    // Server config
    var hostUrlState     by mutableStateOf("127.0.0.1:11434")
    var originsState     by mutableStateOf("*")
    var downloadUrlState by mutableStateOf("https://github.com/El3tar-cmd/ollama-project/raw/main/app/src/main/jniLibs/arm64-v8a/libollama.so")

    // Daemon states
    var binaryInstalled   by mutableStateOf(false)
    var serviceActive     by mutableStateOf(false)
    var setupProgress     by mutableStateOf(0)
    var setupStatus       by mutableStateOf("")
    var isInstalling      by mutableStateOf(false)
    var isPreparingBinary by mutableStateOf(false)

    // API status
    var apiOnline         by mutableStateOf(false)
    var apiHostMessage    by mutableStateOf("Disconnected")

    // Models
    var modelList           by mutableStateOf<List<OllamaModel>>(emptyList())
    var customModelPullName by mutableStateOf("qwen2:0.5b")
    var pullProgressStatus  by mutableStateOf("")
    var pullProgressPercent by mutableStateOf(-1)
    var isPullingActive     by mutableStateOf(false)

    // Chat
    var selectedModelChat    by mutableStateOf("")
    var chatMessageInput     by mutableStateOf("")
    val chatHistory          = mutableStateListOf<ChatMessage>()
    var isGeneratingResponse by mutableStateOf(false)

    // Agent
    var agentInput              by mutableStateOf("")
    var agentModel              by mutableStateOf("")
    var agentMaxSteps           by mutableStateOf(15)
    var isAgentRunning          by mutableStateOf(false)
    val agentSteps              = mutableStateListOf<AgentStep>()
    var agentWorkingDir         by mutableStateOf("")
    val agentFileTree           = mutableStateListOf<File>()
    var agentSelectedFile       by mutableStateOf<File?>(null)
    var agentFileContent        by mutableStateOf("")
    var showFileTree            by mutableStateOf(true)
    val openFiles               = mutableStateListOf<File>()
    var activeTabIndex          by mutableStateOf(0)

    fun openInNewTab(file: File) {
        if (!openFiles.contains(file)) openFiles.add(file)
        activeTabIndex = openFiles.indexOf(file)
        openFile(file)
    }

    fun closeTab(index: Int) {
        if (index in openFiles.indices) {
            openFiles.removeAt(index)
            if (openFiles.isEmpty()) {
                agentSelectedFile = null; agentFileContent = ""; activeTabIndex = 0
            } else {
                activeTabIndex = minOf(activeTabIndex, openFiles.size - 1)
                openFile(openFiles[activeTabIndex])
            }
        }
    }

    fun switchToTab(index: Int) {
        if (index in openFiles.indices) { activeTabIndex = index; openFile(openFiles[index]) }
    }

    // ask_user support
    var agentPendingQuestion    by mutableStateOf<String?>(null)
    var agentQuestionInput      by mutableStateOf("")
    private val agentAnswerChannel = kotlinx.coroutines.channels.Channel<String>(1)

    fun submitAgentAnswer() {
        val answer = agentQuestionInput.trim().ifEmpty { "Continue" }
        agentAnswerChannel.trySend(answer)
        agentPendingQuestion = null; agentQuestionInput = ""
    }

    // HuggingFace model downloader
    var hfRepo             by mutableStateOf("")
    var hfFile             by mutableStateOf("")
    var hfDownloadProgress by mutableStateOf(-1f)
    var hfDownloadStatus   by mutableStateOf("")
    private var hfDownloadJob: kotlinx.coroutines.Job? = null

    fun downloadFromHF(context: Context) {
        val repo = hfRepo.trim(); val file = hfFile.trim()
        if (repo.isBlank() || file.isBlank()) return
        val url = "https://huggingface.co/$repo/resolve/main/$file"
        val destDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val dest = java.io.File(destDir, file)
        hfDownloadJob?.cancel()
        hfDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { hfDownloadStatus = "⬇️ Connecting…"; hfDownloadProgress = 0f }
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()
                val req  = okhttp3.Request.Builder().url(url).header("User-Agent", "DevHiveIDE/1.0").build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) { hfDownloadStatus = "❌ HTTP ${resp.code}"; hfDownloadProgress = -1f }
                    return@launch
                }
                val total = resp.body?.contentLength() ?: -1L
                val input = resp.body!!.byteStream()
                destDir.mkdirs()
                dest.outputStream().use { out ->
                    var downloaded = 0L; val buf = ByteArray(65536); var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read); downloaded += read
                        if (total > 0) {
                            val pct = downloaded.toFloat() / total.toFloat()
                            val dlGB = "%.2f".format(downloaded / 1_000_000_000.0)
                            val totGB = "%.2f".format(total / 1_000_000_000.0)
                            withContext(Dispatchers.Main) { hfDownloadProgress = pct; hfDownloadStatus = "⬇️ $dlGB / $totGB GB" }
                        }
                    }
                }
                withContext(Dispatchers.Main) { hfDownloadProgress = -1f; hfDownloadStatus = "✅ Downloaded: $file" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { hfDownloadProgress = -1f; hfDownloadStatus = "❌ ${e.message}" }
            }
        }
    }

    fun cancelHFDownload() {
        hfDownloadJob?.cancel(); hfDownloadJob = null; hfDownloadProgress = -1f; hfDownloadStatus = ""
    }

    // Vulkan GPU support
    var useVulkan       by mutableStateOf(false)
    var deviceHasVulkan by mutableStateOf(false)

    fun checkVulkanSupport(context: Context) {
        deviceHasVulkan = context.packageManager.hasSystemFeature("android.hardware.vulkan.version") ||
            context.packageManager.hasSystemFeature("android.hardware.vulkan.level.0")
    }

    // Terminal / Daemon logs
    val liveLogs      = mutableStateListOf<String>()
    var terminalInput by mutableStateOf("")

    // Shell
    val shellLines    = mutableStateListOf<ShellLine>()
    var shellCwd      by mutableStateOf(
        ctx.getExternalFilesDir(null)?.absolutePath ?: ctx.filesDir.absolutePath
    )
    val shellHistory  = mutableListOf<String>()
    var shellHistIdx  by mutableStateOf(-1)

    // Cloud auth
    var authLoginUrl      by mutableStateOf<String?>(null)
    var isLoggedIn        by mutableStateOf(false)
    var cloudApiKey       by mutableStateOf("")
    var manualApiKeyInput by mutableStateOf("")
    var isSigningIn       by mutableStateOf(false)
    var isValidatingKey   by mutableStateOf(false)
    var cloudAuthStatus   by mutableStateOf("")

    // llama.cpp backend
    var activeBackend          by mutableStateOf("ollama")
    val llamaServer            = LlamaCppServer(ctx)
    var llamaBinaryInstalled   by mutableStateOf(false)
    var llamaServiceActive     by mutableStateOf(false)
    var llamaApiOnline         by mutableStateOf(false)
    var llamaSelectedModel     by mutableStateOf<File?>(null)
    var llamaAvailableGGUFs    by mutableStateOf<List<File>>(emptyList())
    var llamaGpuLayers         by mutableStateOf(99)
    var llamaContextSize       by mutableStateOf(4096)
    var llamaThreads           by mutableStateOf(4)
    var llamaBatchSize         by mutableStateOf(512)
    var llamaPort              by mutableStateOf("8080")
    var llamaTemperature       by mutableStateOf(0.7f)
    var isDownloadingGGUF      by mutableStateOf(false)
    var ggufDownloadProgress   by mutableStateOf(0)
    var ggufDownloadStatus     by mutableStateOf("")
    var llamaHealthStatus      by mutableStateOf("")

    private val prefs = ctx.getSharedPreferences("ollama_prefs", Context.MODE_PRIVATE)

    init {
        cloudApiKey = prefs.getString("cloud_api_key", "") ?: ""
        isLoggedIn  = cloudApiKey.isNotBlank()
        checkBinaryInstalled()
        val extDir  = ctx.getExternalFilesDir(null)
        val workDir = if (extDir != null) {
            java.io.File(extDir, "OllamaAgent").also { it.mkdirs() }
        } else {
            java.io.File(ctx.filesDir, "OllamaAgent").also { it.mkdirs() }
        }
        agentWorkingDir = workDir.absolutePath
        agentEngine.workingDir = agentWorkingDir
        refreshFileTree()
        llamaBinaryInstalled = llamaServer.isBinaryInstalled
        scanGGUFs()
        refreshServiceStatus()
        syncLogs()
        startApiWatcher()
    }

    private fun syncLogs() {
        synchronized(OllamaService.logBuffer) { liveLogs.addAll(OllamaService.logBuffer) }
        OllamaService.onLogReceived = { line ->
            viewModelScope.launch(Dispatchers.Main) {
                liveLogs.add(line)
                if (liveLogs.size > 1000) liveLogs.removeAt(0)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            var lastSize = 0
            while (true) {
                val lines = synchronized(LlamaService.logBuffer) {
                    if (LlamaService.logBuffer.size > lastSize)
                        LlamaService.logBuffer.drop(lastSize).also { lastSize = LlamaService.logBuffer.size }
                    else emptyList()
                }
                if (lines.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.Main) {
                        lines.forEach { line ->
                            liveLogs.add("[llama.cpp] $line")
                            if (liveLogs.size > 1000) liveLogs.removeAt(0)
                        }
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun startApiWatcher() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val base = "http://$hostUrlState"
                api.checkRunning(base) { online, msg ->
                    apiOnline = online
                    apiHostMessage = if (online) "Online" else msg
                }
                if (apiOnline) {
                    api.listModels(base) { list, _ ->
                        viewModelScope.launch(Dispatchers.Main) {
                            if (list != null) {
                                modelList = list
                                if (selectedModelChat.isEmpty() && list.isNotEmpty()) selectedModelChat = list.first().name
                                if (agentModel.isEmpty() && list.isNotEmpty()) agentModel = list.first().name
                            }
                        }
                    }
                }
                if (llamaServiceActive || LlamaService.isRunning) {
                    LlamaCppApi().checkHealth("http://127.0.0.1:$llamaPort") { ok, _ ->
                        llamaApiOnline = ok
                        if (!ok && LlamaService.isRunning == false) llamaServiceActive = false
                    }
                } else {
                    llamaApiOnline = false
                }
                delay(3000)
            }
        }
    }

    fun checkBinaryInstalled() { binaryInstalled = executor.isBinaryInstalled() }

    fun refreshServiceStatus() { serviceActive = OllamaService.isRunning; checkBinaryInstalled() }

    fun toggleOllamaService(context: Context) {
        if (OllamaService.isRunning) {
            context.stopService(Intent(context, OllamaService::class.java))
        } else {
            context.startForegroundService(Intent(context, OllamaService::class.java).apply {
                putExtra("host", hostUrlState)
                putExtra("origins", originsState)
                if (cloudApiKey.isNotBlank()) putExtra("api_key", cloudApiKey)
            })
        }
        viewModelScope.launch(Dispatchers.Main) { delay(600); refreshServiceStatus() }
    }

    fun triggerBinaryInstall(context: Context) {
        if (isInstalling) return
        isInstalling = true; setupProgress = 0; setupStatus = "Starting download..."
        viewModelScope.launch(Dispatchers.IO) {
            val success = executor.downloadBinary(
                url = downloadUrlState,
                onProgress = { pct -> viewModelScope.launch(Dispatchers.Main) { setupProgress = pct } },
                onLog = { msg -> viewModelScope.launch(Dispatchers.Main) { setupStatus = msg } }
            )
            withContext(Dispatchers.Main) {
                isInstalling = false
                val msg = if (success) "Binary installed successfully" else "Installation failed — see logs"
                setupStatus = msg; checkBinaryInstalled()
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun toggleLlamaService(context: Context) {
        if (LlamaService.isRunning) {
            context.stopService(Intent(context, LlamaService::class.java))
            llamaServiceActive = false; llamaApiOnline = false; llamaHealthStatus = ""
        } else {
            val model = llamaSelectedModel ?: run {
                Toast.makeText(context, "Select a GGUF model first", Toast.LENGTH_SHORT).show()
                return
            }
            context.startForegroundService(Intent(context, LlamaService::class.java).apply {
                putExtra("model_path", model.absolutePath)
                putExtra("host", "127.0.0.1")
                putExtra("port", llamaPort)
                putExtra("gpu_layers", llamaGpuLayers)
                putExtra("ctx_size", llamaContextSize)
                putExtra("threads", llamaThreads)
                putExtra("batch_size", llamaBatchSize)
            })
            llamaServiceActive = true; llamaHealthStatus = "⏳ Starting server…"
            viewModelScope.launch(Dispatchers.IO) {
                repeat(30) { attempt ->
                    delay(1000)
                    if (!LlamaService.isRunning) {
                        viewModelScope.launch(Dispatchers.Main) {
                            llamaServiceActive = false; llamaApiOnline = false
                            llamaHealthStatus = "❌ Server process exited — check Logs for details"
                        }
                        return@launch
                    }
                    LlamaCppApi().checkHealth("http://127.0.0.1:$llamaPort") { ok, msg ->
                        viewModelScope.launch(Dispatchers.Main) {
                            if (ok) { llamaApiOnline = true; llamaHealthStatus = "✅ Server running on port $llamaPort" }
                            else if (attempt == 29) { llamaHealthStatus = "❌ Timeout — $msg" }
                        }
                    }
                    if (llamaApiOnline) return@launch
                }
            }
        }
    }

    fun checkLlamaHealth(context: Context? = null) {
        llamaHealthStatus = "⏳ Checking…"
        LlamaCppApi().checkHealth("http://127.0.0.1:$llamaPort") { ok, msg ->
            viewModelScope.launch(Dispatchers.Main) {
                llamaApiOnline = ok
                llamaHealthStatus = if (ok) "✅ Server healthy on port $llamaPort" else "❌ Not reachable: $msg"
                context?.let { Toast.makeText(it, llamaHealthStatus, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun cancelGGUFDownload() { llamaServer.cancelDownload = true; isDownloadingGGUF = false; ggufDownloadStatus = "Cancelled" }

    fun scanGGUFs() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = llamaServer.scanLocalGGUFs()
            withContext(Dispatchers.Main) {
                llamaAvailableGGUFs = files
                if (llamaSelectedModel == null && files.isNotEmpty()) llamaSelectedModel = files.first()
            }
        }
    }

    fun downloadGGUF(context: Context, model: GGUFModel) {
        if (isDownloadingGGUF) return
        isDownloadingGGUF = true; ggufDownloadProgress = 0; ggufDownloadStatus = "Starting…"
        llamaServer.downloadGGUF(model,
            onProgress = { pct, msg -> viewModelScope.launch(Dispatchers.Main) { ggufDownloadProgress = pct; ggufDownloadStatus = msg } },
            onDone = { ok, msg, file -> viewModelScope.launch(Dispatchers.Main) {
                isDownloadingGGUF = false; ggufDownloadStatus = msg
                if (ok && file != null) { scanGGUFs(); llamaSelectedModel = file }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }}
        )
    }

    fun triggerLogin(context: Context) {
        if (!executor.isBinaryInstalled()) {
            Toast.makeText(context, "Install binary first, then sign in.", Toast.LENGTH_LONG).show()
            return
        }
        authLoginUrl = null; isSigningIn = true; cloudAuthStatus = ""
        liveLogs.add("Starting Ollama login…")
        viewModelScope.launch(Dispatchers.IO) {
            var binarySupportsLogin = true
            val proc = executor.execLogin { line ->
                viewModelScope.launch(Dispatchers.Main) {
                    liveLogs.add(line)
                    if (line.contains("unknown command", ignoreCase = true) ||
                        (line.contains("Error", ignoreCase = true) && line.contains("login", ignoreCase = true)))
                        binarySupportsLogin = false
                    val match = "(https://ollama\\.com/connect\\S+)".toRegex().find(line)
                    if (match != null) authLoginUrl = match.value
                    if (line.contains("Logged in", ignoreCase = true) || line.contains("Authenticated", ignoreCase = true)) {
                        cloudAuthStatus = "✅ Logged in!"; markLoggedIn()
                    }
                }
            }
            proc?.waitFor()
            kotlinx.coroutines.delay(400)
            withContext(Dispatchers.Main) {
                when {
                    authLoginUrl != null || isLoggedIn -> isSigningIn = false
                    !binarySupportsLogin || liveLogs.any { it.contains("unknown command", ignoreCase = true) } -> {
                        liveLogs.add("Binary doesn't support 'login'. Generating auth key locally…")
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val auth = OllamaAuth(context)
                                val url  = auth.generateConnectUrl("devhive")
                                withContext(Dispatchers.Main) {
                                    authLoginUrl = url; isSigningIn = false
                                    liveLogs.add("✅ Auth key generated — open browser to authorize")
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    cloudAuthStatus = "❌ Failed to generate key: ${e.message}"
                                    isSigningIn = false; liveLogs.add("❌ Key generation error: ${e.message}")
                                }
                            }
                        }
                    }
                    else -> { isSigningIn = false; readAndApplyStoredCredential() }
                }
            }
        }
    }

    fun readAndApplyStoredCredential() {
        viewModelScope.launch(Dispatchers.IO) {
            val credential = executor.readStoredCredential()
            withContext(Dispatchers.Main) {
                if (!credential.isNullOrBlank()) {
                    persistApiKey(credential)
                    cloudAuthStatus = "✅ Credentials found and saved!"
                    liveLogs.add("✅ Credentials stored — cloud auth active.")
                }
            }
        }
    }

    fun validateAndSaveApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) { Toast.makeText(context, "Enter an API key first.", Toast.LENGTH_SHORT).show(); return }
        isValidatingKey = true; cloudAuthStatus = "Validating key…"
        api.validateCloudApiKey(trimmed) { ok, msg ->
            viewModelScope.launch(Dispatchers.Main) {
                isValidatingKey = false
                if (ok) {
                    persistApiKey(trimmed); manualApiKeyInput = ""
                    cloudAuthStatus = "✅ Key validated and saved!"
                    Toast.makeText(context, "API key saved — cloud auth active.", Toast.LENGTH_SHORT).show()
                } else {
                    cloudAuthStatus = "❌ $msg"
                    Toast.makeText(context, "Validation failed: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun persistApiKey(key: String) {
        cloudApiKey = key; isLoggedIn = true; authLoginUrl = null
        prefs.edit().putString("cloud_api_key", key).apply()
        liveLogs.add("✅ Cloud API key stored.")
    }

    fun markLoggedIn() {
        if (!isLoggedIn) {
            isLoggedIn = true; authLoginUrl = null
            cloudAuthStatus = "✅ Logged in to Ollama Cloud!"
            prefs.edit().putBoolean("logged_in_flag", true).apply()
            liveLogs.add("✅ Login confirmed — authenticated with Ollama.")
        }
    }

    fun confirmLogin() { isSigningIn = false; authLoginUrl = null; readAndApplyStoredCredential(); markLoggedIn() }

    fun triggerLogout(context: Context) {
        val key = cloudApiKey
        viewModelScope.launch(Dispatchers.IO) {
            executor.execLogout(apiKey = key) { line -> viewModelScope.launch(Dispatchers.Main) { liveLogs.add(line) } }
            withContext(Dispatchers.Main) {
                cloudApiKey = ""; isLoggedIn = false; cloudAuthStatus = ""
                prefs.edit().remove("cloud_api_key").putBoolean("logged_in_flag", false).apply()
                Toast.makeText(context, "Logged out from Ollama Cloud.", Toast.LENGTH_SHORT).show()
                liveLogs.add("Logged out — cloud credentials cleared.")
            }
        }
    }

    fun pullModel(context: Context) {
        val model = customModelPullName.trim()
        if (model.isEmpty()) { Toast.makeText(context, "Enter a model name", Toast.LENGTH_SHORT).show(); return }
        if (!apiOnline) { Toast.makeText(context, "Server must be running", Toast.LENGTH_SHORT).show(); return }
        isPullingActive = true; pullProgressStatus = "Starting..."; pullProgressPercent = -1
        api.pullModelStream("http://$hostUrlState", model,
            onProgress = { s, p -> viewModelScope.launch(Dispatchers.Main) { pullProgressStatus = s; pullProgressPercent = p } },
            onComplete = { _, msg ->
                viewModelScope.launch(Dispatchers.Main) {
                    isPullingActive = false
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    api.listModels("http://$hostUrlState") { list, _ ->
                        viewModelScope.launch(Dispatchers.Main) { if (list != null) modelList = list }
                    }
                }
            })
    }

    fun deleteModel(context: Context, name: String) {
        if (!apiOnline) return
        api.deleteModel("http://$hostUrlState", name) { _, msg ->
            viewModelScope.launch(Dispatchers.Main) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                api.listModels("http://$hostUrlState") { list, _ ->
                    viewModelScope.launch(Dispatchers.Main) { if (list != null) modelList = list }
                }
            }
        }
    }

    fun startChatSession() {
        chatHistory.clear()
        chatHistory.add(ChatMessage("assistant", "Hello! I'm ready. Selected model: **$selectedModelChat**"))
    }

    fun sendChatMessage(context: Context) {
        val text = chatMessageInput.trim()
        val isLlama = activeBackend == "llamacpp"
        if (text.isEmpty()) return
        if (!isLlama && selectedModelChat.isEmpty()) return
        if (isLlama && llamaSelectedModel == null) return
        val isCloud = selectedModelChat.endsWith(":cloud")
        when {
            isCloud && cloudApiKey.isBlank() -> {
                Toast.makeText(context, "Cloud model needs an API key — add it in Settings → Cloud Auth", Toast.LENGTH_LONG).show()
                return
            }
            !isCloud && isLlama && !llamaApiOnline -> {
                Toast.makeText(context, "llama.cpp server offline", Toast.LENGTH_SHORT).show(); return
            }
            !isCloud && !isLlama && !apiOnline -> {
                Toast.makeText(context, "Server offline", Toast.LENGTH_SHORT).show(); return
            }
        }
        chatHistory.add(ChatMessage("user", text))
        chatMessageInput = ""; isGeneratingResponse = true
        val idx = chatHistory.size
        chatHistory.add(ChatMessage("assistant", ""))
        var response = ""
        val sysPrompt = buildString {
            append("You are DevHive — an expert AI coding assistant running locally on Android. ")
            append("Be concise, precise, and helpful. Answer in the same language the user uses.")
            agentSelectedFile?.let { f ->
                append(" Current file: ${f.name}.")
                val lang = getLanguageFromExtension(f.name)
                if (lang.isNotBlank() && lang != "Text") append(" Language: $lang.")
            }
        }
        val history = chatHistory.subList(0, idx).dropWhile { it.role != "user" }.toList()
        val trimmedHistory = if (history.size > 20) {
            listOf(ChatMessage("system", "[${history.size - 10} earlier messages omitted for context efficiency]")) +
            history.takeLast(10)
        } else history
        val apiMessages = listOf(ChatMessage("system", sysPrompt)) + trimmedHistory

        fun cleanDisplay(raw: String): String = raw
            
            .replace(Regex("^\\s*null\\s*", setOf(RegexOption.MULTILINE)), "")
            .trimStart()

        val onToken: (String) -> Unit = { token ->
            viewModelScope.launch(Dispatchers.Main) {
                response += token
                chatHistory[idx] = ChatMessage("assistant", cleanDisplay(response).ifEmpty { "..." })
            }
        }
        val onDone: (Boolean, String) -> Unit = { ok, msg ->
            viewModelScope.launch(Dispatchers.Main) {
                isGeneratingResponse = false
                when {
                    !ok -> chatHistory[idx] = ChatMessage("assistant", "⚠️ $msg")
                    response.isBlank() -> chatHistory[idx] = ChatMessage("assistant", "⚠️ Empty response. Check Logs tab.")
                    else -> chatHistory[idx] = ChatMessage("assistant", cleanDisplay(response).ifEmpty { response })
                }
            }
        }
        when {
            isCloud       -> api.cloudChatStream(cloudApiKey, selectedModelChat, apiMessages, onToken, onDone)
            activeBackend == "llamacpp" -> LlamaCppApi().chatStream(
                "http://127.0.0.1:$llamaPort", apiMessages,
                temperature = llamaTemperature, maxTokens = 2048, onToken = onToken, onComplete = onDone
            )
            else          -> api.chatStream("http://$hostUrlState", selectedModelChat, apiMessages, onToken, onDone)
        }
    }

    fun updateAgentWorkingDir(path: String) {
        agentWorkingDir = path; agentEngine.workingDir = path; refreshFileTree()
    }

    fun refreshFileTree() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = File(agentWorkingDir).listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
            withContext(Dispatchers.Main) { agentFileTree.clear(); agentFileTree.addAll(files) }
        }
    }

    fun openFile(file: File) {
        if (file.isDirectory) {
            updateAgentWorkingDir(file.absolutePath)
        } else {
            agentSelectedFile = file
            viewModelScope.launch(Dispatchers.IO) {
                val content = try { if (file.length() < 200_000) file.readText() else "(File too large)" }
                              catch (e: Exception) { "Error: ${e.message}" }
                withContext(Dispatchers.Main) { agentFileContent = content }
            }
        }
    }

    fun saveCurrentFile() {
        val file = agentSelectedFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { file.writeText(agentFileContent) }
            catch (e: Exception) { withContext(Dispatchers.Main) { agentSteps.add(AgentStep("error", "❌ Save failed: ${e.message}")) } }
        }
    }

    fun deleteFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
                withContext(Dispatchers.Main) { refreshFileTree() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { agentSteps.add(AgentStep("error", "❌ Delete failed: ${e.message}")) }
            }
        }
    }

    fun renameFile(file: File, newName: String) {
        val trimmed = newName.trim(); if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = File(file.parentFile, trimmed)
                val ok = file.renameTo(target)
                withContext(Dispatchers.Main) {
                    if (!ok) agentSteps.add(AgentStep("error", "❌ Rename failed: could not rename"))
                    refreshFileTree()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { agentSteps.add(AgentStep("error", "❌ Rename failed: ${e.message}")) }
            }
        }
    }

    private var agentJob: kotlinx.coroutines.Job? = null

    fun runAgent(context: Context) {
        val task = agentInput.trim()
        if (activeBackend == "llamacpp" && agentModel.isEmpty() && llamaSelectedModel != null)
            agentModel = llamaSelectedModel!!.name
        if (task.isEmpty() || agentModel.isEmpty()) return
        val isCloudModel = agentModel.endsWith(":cloud")
        val needsServer = !isCloudModel && activeBackend == "ollama"
        if (needsServer && !apiOnline) {
            Toast.makeText(context, "Ollama server must be running for local agent", Toast.LENGTH_SHORT).show(); return
        }
        val needsLlama = !isCloudModel && activeBackend == "llamacpp"
        if (needsLlama && !llamaApiOnline) {
            Toast.makeText(context, "llama.cpp server must be running for local agent", Toast.LENGTH_SHORT).show(); return
        }
        agentInput = ""; isAgentRunning = true
        agentSteps.add(AgentStep("user", task))
        agentJob = viewModelScope.launch(Dispatchers.IO) {
            agentEngine.runAgentLoop(
                userTask    = task,
                model       = agentModel,
                baseUrl     = if (activeBackend == "llamacpp") "http://127.0.0.1:$llamaPort" else "http://$hostUrlState",
                cloudApiKey = cloudApiKey,
                backend     = activeBackend,
                maxSteps    = agentMaxSteps,
                onAskUser   = { question ->
                    withContext(Dispatchers.Main) { agentPendingQuestion = question }
                    agentAnswerChannel.receive()
                }
            ) { step -> withContext(Dispatchers.Main) { agentSteps.add(step) } }
            withContext(Dispatchers.Main) { isAgentRunning = false; agentJob = null; refreshFileTree() }
        }
    }

    fun stopAgent() {
        agentJob?.cancel(); agentJob = null; isAgentRunning = false
        agentSteps.add(AgentStep("info", "🛑 Stopped by user."))
        refreshFileTree()
    }

    fun spawnSubAgent(context: Context, subTask: String) {
        agentSteps.add(AgentStep("spawn", "🌱 Spawning sub-agent: $subTask"))
        viewModelScope.launch(Dispatchers.IO) {
            agentEngine.runAgentLoop(
                userTask    = subTask,
                model       = agentModel,
                baseUrl     = "http://$hostUrlState",
                cloudApiKey = cloudApiKey,
                backend     = activeBackend
            ) { step -> withContext(Dispatchers.Main) { agentSteps.add(AgentStep(step.type, "  [sub] ${step.content}", step.isError)) } }
            withContext(Dispatchers.Main) { isAgentRunning = false; refreshFileTree() }
        }
    }

    fun clearLogs() { synchronized(OllamaService.logBuffer) { OllamaService.logBuffer.clear() }; liveLogs.clear() }

    fun runTerminalCommand(context: Context) {
        val cmds = terminalInput.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        terminalInput = ""; if (cmds.isEmpty()) return
        liveLogs.add("> ollama ${cmds.joinToString(" ")}"); authLoginUrl = null
        executor.execOllamaCommand(*cmds.toTypedArray()) { line ->
            viewModelScope.launch(Dispatchers.Main) {
                liveLogs.add(line)
                val match = "(https://ollama\\.com/connect\\S+)".toRegex().find(line)
                if (match != null) authLoginUrl = match.value
            }
        }
    }
}
