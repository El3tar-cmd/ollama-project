package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

// ─── Shell terminal ────────────────────────────────────────────────────────
enum class ShellLineType { COMMAND, OUTPUT, ERROR, INFO }
data class ShellLine(val text: String, val type: ShellLineType = ShellLineType.OUTPUT)

// ─────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }
}

// ─────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────
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
    var agentInput        by mutableStateOf("")
    var agentModel        by mutableStateOf("")
    var isAgentRunning    by mutableStateOf(false)
    val agentSteps        = mutableStateListOf<AgentStep>()
    var agentWorkingDir   by mutableStateOf("")
    val agentFileTree     = mutableStateListOf<File>()
    var agentSelectedFile by mutableStateOf<File?>(null)
    var agentFileContent  by mutableStateOf("")
    var showFileTree      by mutableStateOf(true)

    // Terminal / Daemon logs
    val liveLogs      = mutableStateListOf<String>()
    var terminalInput by mutableStateOf("")

    // Shell (real bash terminal)
    val shellLines    = mutableStateListOf<ShellLine>()
    var shellCwd      by mutableStateOf(
        ctx.getExternalFilesDir(null)?.absolutePath ?: ctx.filesDir.absolutePath
    )
    val shellHistory  = mutableListOf<String>()
    var shellHistIdx  by mutableStateOf(-1)

    // Cloud auth — API key obtained via `ollama signin` or entered manually
    var authLoginUrl     by mutableStateOf<String?>(null)
    var isLoggedIn       by mutableStateOf(false)
    var cloudApiKey      by mutableStateOf("")
    var manualApiKeyInput by mutableStateOf("")
    var isSigningIn      by mutableStateOf(false)
    var isValidatingKey  by mutableStateOf(false)
    var cloudAuthStatus  by mutableStateOf("")   // feedback message for the UI

    private val prefs = ctx.getSharedPreferences("ollama_prefs", Context.MODE_PRIVATE)

    init {
        // Restore cloud auth state from SharedPreferences
        cloudApiKey = prefs.getString("cloud_api_key", "") ?: ""
        isLoggedIn  = cloudApiKey.isNotBlank()

        // Native lib (libollama.so) is always preferred — check it first
        checkBinaryInstalled()

        // Init agent working dir — prefer external app-specific storage so files
        // are visible in any file manager under Android/data/<pkg>/files/OllamaAgent/
        val extDir = ctx.getExternalFilesDir(null)
        val workDir = if (extDir != null) {
            java.io.File(extDir, "OllamaAgent").also { it.mkdirs() }
        } else {
            java.io.File(ctx.filesDir, "OllamaAgent").also { it.mkdirs() }
        }
        agentWorkingDir = workDir.absolutePath
        agentEngine.workingDir = agentWorkingDir
        refreshFileTree()

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
                delay(3000)
            }
        }
    }

    fun checkBinaryInstalled() {
        binaryInstalled = executor.isBinaryInstalled()
    }

    fun refreshServiceStatus() {
        serviceActive = OllamaService.isRunning
        checkBinaryInstalled()
    }

    fun toggleOllamaService(context: Context) {
        if (OllamaService.isRunning) {
            context.stopService(Intent(context, OllamaService::class.java))
        } else {
            context.startForegroundService(Intent(context, OllamaService::class.java).apply {
                putExtra("host", hostUrlState)
                putExtra("origins", originsState)
                // Pass API key so daemon can use OLLAMA_API_KEY for cloud models
                if (cloudApiKey.isNotBlank()) putExtra("api_key", cloudApiKey)
            })
        }
        viewModelScope.launch(Dispatchers.Main) {
            delay(600)
            refreshServiceStatus()
        }
    }

    fun triggerBinaryInstall(context: Context) {
        if (isInstalling) return
        isInstalling = true
        setupProgress = 0
        setupStatus = "Starting download..."
        viewModelScope.launch(Dispatchers.IO) {
            val success = executor.downloadBinary(
                url = downloadUrlState,
                onProgress = { pct -> viewModelScope.launch(Dispatchers.Main) { setupProgress = pct } },
                onLog = { msg -> viewModelScope.launch(Dispatchers.Main) { setupStatus = msg } }
            )
            withContext(Dispatchers.Main) {
                isInstalling = false
                val msg = if (success) "Binary installed successfully" else "Installation failed — see logs"
                setupStatus = msg
                checkBinaryInstalled()
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Cloud Auth — `ollama login` browser flow OR manual API key ───────────

    /**
     * Runs `ollama login`, captures the connect URL and opens the browser.
     * The binary blocks until the user authorises on ollama.com, then stores
     * credentials under $HOME/.ollama/ (HOME = context.filesDir).
     */
    fun triggerLogin(context: Context) {
        if (!executor.isBinaryInstalled()) {
            Toast.makeText(context, "Install binary first, then sign in.", Toast.LENGTH_LONG).show()
            return
        }
        authLoginUrl    = null
        isSigningIn     = true
        cloudAuthStatus = ""
        liveLogs.add("Starting Ollama login…")

        viewModelScope.launch(Dispatchers.IO) {
            var binarySupportsLogin = true

            // ── Try the binary's built-in login first ──────────────────────────
            val proc = executor.execLogin { line ->
                viewModelScope.launch(Dispatchers.Main) {
                    liveLogs.add(line)
                    if (line.contains("unknown command", ignoreCase = true) ||
                        (line.contains("Error", ignoreCase = true) && line.contains("login", ignoreCase = true))) {
                        binarySupportsLogin = false
                    }
                    val match = "(https://ollama\\.com/connect\\S+)".toRegex().find(line)
                    if (match != null) authLoginUrl = match.value
                    if (line.contains("Logged in", ignoreCase = true) ||
                        line.contains("Authenticated", ignoreCase = true)) {
                        cloudAuthStatus = "✅ Logged in!"
                        markLoggedIn()
                    }
                }
            }
            proc?.waitFor()

            // Small delay for Main callbacks to finish
            kotlinx.coroutines.delay(400)

            withContext(Dispatchers.Main) {
                when {
                    // Binary produced the URL or already logged us in
                    authLoginUrl != null || isLoggedIn -> {
                        isSigningIn = false
                    }
                    // Binary doesn't support login → generate key pair ourselves
                    !binarySupportsLogin || liveLogs.any { it.contains("unknown command", ignoreCase = true) } -> {
                        liveLogs.add("Binary doesn't support 'login'. Generating auth key locally…")
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val auth = OllamaAuth(context)
                                val url  = auth.generateConnectUrl("devhive")
                                withContext(Dispatchers.Main) {
                                    authLoginUrl = url
                                    isSigningIn  = false
                                    liveLogs.add("✅ Auth key generated — open browser to authorize")
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    cloudAuthStatus = "❌ Failed to generate key: ${e.message}"
                                    isSigningIn     = false
                                    liveLogs.add("❌ Key generation error: ${e.message}")
                                }
                            }
                        }
                    }
                    // Binary exited cleanly — check for stored credentials
                    else -> {
                        isSigningIn = false
                        readAndApplyStoredCredential()
                    }
                }
            }
        }
    }

    /** Scan $HOME/.ollama/ for credential file written by `ollama login`. */
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

    /**
     * Validate a manually entered API key with Ollama Cloud, then save it.
     * Users can get an API key from https://ollama.com/settings/api
     */
    fun validateAndSaveApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            Toast.makeText(context, "Enter an API key first.", Toast.LENGTH_SHORT).show()
            return
        }
        isValidatingKey = true
        cloudAuthStatus = "Validating key…"
        api.validateCloudApiKey(trimmed) { ok, msg ->
            viewModelScope.launch(Dispatchers.Main) {
                isValidatingKey = false
                if (ok) {
                    persistApiKey(trimmed)
                    manualApiKeyInput = ""
                    cloudAuthStatus = "✅ Key validated and saved!"
                    Toast.makeText(context, "API key saved — cloud auth active.", Toast.LENGTH_SHORT).show()
                } else {
                    cloudAuthStatus = "❌ $msg"
                    Toast.makeText(context, "Validation failed: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Persist an API key to state + SharedPreferences and mark as logged in. */
    private fun persistApiKey(key: String) {
        cloudApiKey  = key
        isLoggedIn   = true
        authLoginUrl = null
        prefs.edit().putString("cloud_api_key", key).apply()
        liveLogs.add("✅ Cloud API key stored.")
    }

    /** Mark as logged in (binary handled auth — credentials stored in ~/.ollama/). */
    fun markLoggedIn() {
        if (!isLoggedIn) {
            isLoggedIn      = true
            authLoginUrl    = null
            cloudAuthStatus = "✅ Logged in to Ollama Cloud!"
            prefs.edit().putBoolean("logged_in_flag", true).apply()
            liveLogs.add("✅ Login confirmed — authenticated with Ollama.")
        }
    }

    /** Called when user taps "Done — I've Authorized" in the browser dialog. */
    fun confirmLogin() {
        isSigningIn  = false
        authLoginUrl = null
        // Try reading credentials the binary may have written
        readAndApplyStoredCredential()
        // Even if no file found, mark as logged in — binary has the session stored
        markLoggedIn()
    }

    /** Run `ollama logout` and clear stored credentials. */
    fun triggerLogout(context: Context) {
        val key = cloudApiKey
        viewModelScope.launch(Dispatchers.IO) {
            executor.execLogout(apiKey = key) { line ->
                viewModelScope.launch(Dispatchers.Main) { liveLogs.add(line) }
            }
            withContext(Dispatchers.Main) {
                cloudApiKey     = ""
                isLoggedIn      = false
                cloudAuthStatus = ""
                prefs.edit().remove("cloud_api_key").putBoolean("logged_in_flag", false).apply()
                Toast.makeText(context, "Logged out from Ollama Cloud.", Toast.LENGTH_SHORT).show()
                liveLogs.add("Logged out — cloud credentials cleared.")
            }
        }
    }

    // ── Models ──────────────────────────────────────────────────────────
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

    // ── Chat ─────────────────────────────────────────────────────────────
    fun startChatSession() {
        chatHistory.clear()
        chatHistory.add(ChatMessage("assistant", "Hello! I'm ready. Selected model: **$selectedModelChat**"))
    }

    fun sendChatMessage(context: Context) {
        val text = chatMessageInput.trim()
        if (text.isEmpty() || selectedModelChat.isEmpty()) return
        if (!apiOnline) { Toast.makeText(context, "Server offline", Toast.LENGTH_SHORT).show(); return }
        chatHistory.add(ChatMessage("user", text)); chatMessageInput = ""
        isGeneratingResponse = true
        val idx = chatHistory.size
        chatHistory.add(ChatMessage("assistant", ""))
        var response = ""
        // Drop leading assistant messages (e.g. the welcome bubble) — LLMs expect
        // conversation to start with a user turn, and some backends reject otherwise.
        val apiMessages = chatHistory.subList(0, idx)
            .dropWhile { it.role != "user" }
            .toList()
        api.chatStream("http://$hostUrlState", selectedModelChat, apiMessages,
            onTokenGenerated = { token -> viewModelScope.launch(Dispatchers.Main) { response += token; chatHistory[idx] = ChatMessage("assistant", response) } },
            onComplete = { ok, msg -> viewModelScope.launch(Dispatchers.Main) {
                isGeneratingResponse = false
                if (!ok) {
                    chatHistory[idx] = ChatMessage("assistant", "⚠️ $msg")
                } else if (response.isBlank()) {
                    chatHistory[idx] = ChatMessage("assistant", "⚠️ Empty response from server. Check Logs tab for details.")
                }
            }}
        )
    }

    // ── Agent ─────────────────────────────────────────────────────────────
    fun updateAgentWorkingDir(path: String) {
        agentWorkingDir = path
        agentEngine.workingDir = path
        refreshFileTree()
    }

    fun refreshFileTree() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = File(agentWorkingDir).listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
            withContext(Dispatchers.Main) {
                agentFileTree.clear()
                agentFileTree.addAll(files)
            }
        }
    }

    fun openFile(file: File) {
        if (file.isDirectory) {
            updateAgentWorkingDir(file.absolutePath)
        } else {
            agentSelectedFile = file
            viewModelScope.launch(Dispatchers.IO) {
                val content = try { if (file.length() < 200_000) file.readText() else "(File too large)" } catch (e: Exception) { "Error: ${e.message}" }
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
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
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
        if (task.isEmpty() || agentModel.isEmpty()) return
        if (!apiOnline) { Toast.makeText(context, "Server must be running for agent", Toast.LENGTH_SHORT).show(); return }
        agentInput = ""; isAgentRunning = true
        agentSteps.add(AgentStep("user", task))
        agentJob = viewModelScope.launch(Dispatchers.IO) {
            agentEngine.runAgentLoop(task, agentModel, "http://$hostUrlState") { step ->
                withContext(Dispatchers.Main) { agentSteps.add(step) }
            }
            withContext(Dispatchers.Main) { isAgentRunning = false; agentJob = null; refreshFileTree() }
        }
    }

    fun stopAgent() {
        agentJob?.cancel()
        agentJob = null
        isAgentRunning = false
        agentSteps.add(AgentStep("info", "🛑 Stopped by user."))
        refreshFileTree()
    }

    fun spawnSubAgent(context: Context, subTask: String) {
        val parentStepCount = agentSteps.size
        agentSteps.add(AgentStep("spawn", "🌱 Spawning sub-agent: $subTask"))
        viewModelScope.launch(Dispatchers.IO) {
            agentEngine.runAgentLoop(subTask, agentModel, "http://$hostUrlState") { step ->
                withContext(Dispatchers.Main) { agentSteps.add(AgentStep(step.type, "  [sub] ${step.content}", step.isError)) }
            }
            withContext(Dispatchers.Main) { isAgentRunning = false; refreshFileTree() }
        }
    }

    // ── Terminal ──────────────────────────────────────────────────────────
    fun clearLogs() {
        synchronized(OllamaService.logBuffer) { OllamaService.logBuffer.clear() }
        liveLogs.clear()
    }

    fun runTerminalCommand(context: Context) {
        val cmds = terminalInput.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        terminalInput = ""
        if (cmds.isEmpty()) return
        liveLogs.add("> ollama ${cmds.joinToString(" ")}")
        authLoginUrl = null
        executor.execOllamaCommand(*cmds.toTypedArray()) { line ->
            viewModelScope.launch(Dispatchers.Main) {
                liveLogs.add(line)
                val match = "(https://ollama\\.com/connect\\S+)".toRegex().find(line)
                if (match != null) authLoginUrl = match.value
            }
        }
    }
}

// ─────────────────────────────────────────────
// Navigation enum
// ─────────────────────────────────────────────
enum class AppTab(val label: String, val icon: ImageVector) {
    SERVER("Server",   Icons.Default.Settings),
    MODELS("Models",   Icons.Default.List),
    CHAT("Chat",       Icons.Default.Face),
    AGENT("Agent",     Icons.Default.Star),
    TERMINAL("Logs",   Icons.Default.Info),
}

// ─────────────────────────────────────────────
// Root screen
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val vm: MainViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(context.applicationContext) as T
    })
    var activeTab by remember { mutableStateOf(AppTab.SERVER) }

    // Request MANAGE_EXTERNAL_STORAGE (Android 11+) for full filesystem access in Agent file browser
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                // Fallback: open general storage settings if app-specific page unavailable
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    Scaffold(
        containerColor = OllamaBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OllamaSurface, titleContentColor = OllamaText),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(OllamaGreen),
                            contentAlignment = Alignment.Center
                        ) { Text("O", color = OllamaBg, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
                        Text("Ollama", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OllamaText)
                        Text("Devhive", fontWeight = FontWeight.Light, fontSize = 18.sp, color = OllamaGreen)
                    }
                },
                actions = {
                    StatusPill(online = vm.apiOnline)
                    Spacer(Modifier.width(12.dp))
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = OllamaSurface, tonalElevation = 0.dp) {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(20.dp)) },
                        label = { Text(tab.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OllamaGreen,
                            selectedTextColor = OllamaGreen,
                            indicatorColor    = OllamaCard,
                            unselectedIconColor = OllamaTextDim,
                            unselectedTextColor = OllamaTextDim
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(OllamaBg)) {
            when (activeTab) {
                AppTab.SERVER   -> ServerScreen(vm, context)
                AppTab.MODELS   -> ModelsScreen(vm, context)
                AppTab.CHAT     -> ChatScreen(vm, context)
                AppTab.AGENT    -> AgentScreen(vm, context)
                AppTab.TERMINAL -> TerminalScreen(vm, context)
            }
        }
    }

    // Sign-in dialog — shown after `ollama signin` outputs a connect URL
    vm.authLoginUrl?.let { url ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        var browserOpened by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { vm.authLoginUrl = null; vm.isSigningIn = false },
            containerColor = OllamaCard,
            title = { Text("Sign in to Ollama Cloud", color = OllamaText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Open the link below in your browser and sign in to your Ollama account. " +
                        "Then tap \"Done\" once you've authorized this device.",
                        color = OllamaTextDim, fontSize = 13.sp
                    )
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(OllamaCardAlt).padding(8.dp)
                            .clickable {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Text(url, fontSize = 10.sp, color = OllamaGreen, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    if (browserOpened) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1A3A2A)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.AccountCircle, null, tint = OllamaGreen, modifier = Modifier.size(14.dp))
                            Text("Browser opened — authorize, then tap Done ↓", color = OllamaGreen, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            catch (_: Exception) {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                            browserOpened = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccountCircle, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Browser")
                    }
                    if (browserOpened) {
                        Button(
                            onClick = { vm.confirmLogin() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A4A2A), contentColor = OllamaGreen),
                            modifier = Modifier.fillMaxWidth(),
                            border = androidx.compose.foundation.BorderStroke(1.dp, OllamaGreen)
                        ) { Text("✓ Done — I've Signed In", fontWeight = FontWeight.Bold) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy Link", color = OllamaTextDim) }
            }
        )
    }
}

// ─────────────────────────────────────────────
// Shared UI components
// ─────────────────────────────────────────────
@Composable
fun StatusPill(online: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (online) Color(0xFF1A3A2A) else Color(0xFF3A1A1A))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) OllamaGreen else OllamaRed))
        Text(if (online) "Online" else "Offline", color = if (online) OllamaGreen else OllamaRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OllamaCard)
            .border(1.dp, OllamaBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(title, color = OllamaGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (subtitle != null) Text(subtitle, color = OllamaTextDim, fontSize = 12.sp)
        }
        content()
    }
}

@Composable
fun OllamaTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, maxLines: Int = 1, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default, tag: String = "") {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        maxLines = maxLines,
        modifier = modifier.then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = OllamaGreen,
            unfocusedBorderColor = OllamaBorder,
            focusedLabelColor    = OllamaGreen,
            unfocusedLabelColor  = OllamaTextDim,
            cursorColor          = OllamaGreen,
            focusedTextColor     = OllamaText,
            unfocusedTextColor   = OllamaText
        ),
        shape = RoundedCornerShape(8.dp)
    )
}

// ─────────────────────────────────────────────
// SERVER SCREEN
// ─────────────────────────────────────────────
@Composable
fun ServerScreen(vm: MainViewModel, context: Context) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status header
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(OllamaCard)
                .border(1.dp, if (vm.serviceActive) Color(0xFF1E4030) else OllamaBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DAEMON", color = OllamaTextDim, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(
                    if (vm.serviceActive) "Running · Port 11434" else "Stopped",
                    color = if (vm.serviceActive) OllamaGreen else OllamaRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    if (vm.binaryInstalled) "ARM64 binary ready" else if (vm.isPreparingBinary) "Copying binary..." else "Binary missing",
                    color = OllamaTextDim,
                    fontSize = 11.sp
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = vm.serviceActive,
                    onCheckedChange = { vm.toggleOllamaService(context) },
                    enabled = vm.binaryInstalled && !vm.isPreparingBinary,
                    modifier = Modifier.testTag("daemon_toggle"),
                    colors = SwitchDefaults.colors(checkedThumbColor = OllamaBg, checkedTrackColor = OllamaGreen)
                )
                if (vm.isPreparingBinary) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = OllamaGreen, strokeWidth = 2.dp)
                }
            }
        }

        // Binary engine
        SectionCard("BINARY ENGINE", "Ollama ARM64 executable for Android") {
            OllamaTextField(
                vm.downloadUrlState, { vm.downloadUrlState = it },
                "Download URL",
                Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                tag = "download_url_input"
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        if (vm.binaryInstalled) "✓ Installed" else "✗ Not found",
                        color = if (vm.binaryInstalled) OllamaGreen else OllamaRed,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    if (vm.isInstalling) Text(vm.setupStatus, color = OllamaTextDim, fontSize = 11.sp)
                }
                Button(
                    onClick = { vm.triggerBinaryInstall(context) },
                    enabled = !vm.isInstalling,
                    colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg),
                    modifier = Modifier.testTag("binary_download_btn")
                ) {
                    if (vm.isInstalling) CircularProgressIndicator(Modifier.size(14.dp), color = OllamaBg, strokeWidth = 2.dp)
                    else Text(if (vm.binaryInstalled) "Reinstall" else "Install", fontWeight = FontWeight.Bold)
                }
            }
            if (vm.isInstalling && vm.setupProgress >= 0) {
                LinearProgressIndicator(
                    progress = { vm.setupProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = OllamaGreen, trackColor = OllamaBorder
                )
            }
        }

        // Network config
        SectionCard("NETWORK CONFIG") {
            OllamaTextField(vm.hostUrlState, { vm.hostUrlState = it }, "OLLAMA_HOST", Modifier.fillMaxWidth(), tag = "host_input",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next))
            OllamaTextField(vm.originsState, { vm.originsState = it }, "OLLAMA_ORIGINS (CORS)", Modifier.fillMaxWidth(), tag = "origins_input",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            Text("Set host to 0.0.0.0:11434 to expose server to your local Wi-Fi network.", color = OllamaTextDim, fontSize = 11.sp)
        }

        // Cloud auth
        SectionCard("CLOUD AUTH", "Sign in to use Ollama cloud models") {
            // Status badge
            if (vm.isLoggedIn) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A3A2A)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.AccountCircle, null, tint = OllamaGreen, modifier = Modifier.size(18.dp))
                    Text(
                        if (vm.cloudApiKey.isNotBlank()) "Authenticated with API key" else "Authenticated via Ollama",
                        color = OllamaGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Status/feedback message
            if (vm.cloudAuthStatus.isNotBlank()) {
                Text(vm.cloudAuthStatus, color = if (vm.cloudAuthStatus.startsWith("✅")) OllamaGreen else OllamaRed, fontSize = 12.sp)
            }

            // Sign-in button row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.triggerLogin(context) },
                    modifier = Modifier.weight(1f),
                    enabled = !vm.isSigningIn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (vm.isLoggedIn) OllamaCard else OllamaGreen,
                        contentColor   = if (vm.isLoggedIn) OllamaTextDim else OllamaBg
                    ),
                    border = if (vm.isLoggedIn) androidx.compose.foundation.BorderStroke(1.dp, OllamaBorder) else null
                ) {
                    if (vm.isSigningIn) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = OllamaBg, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Waiting…")
                    } else {
                        Icon(Icons.Default.AccountCircle, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (vm.isLoggedIn) "Re-login" else "Login", fontWeight = FontWeight.Bold)
                    }
                }
                if (vm.isLoggedIn) {
                    OutlinedButton(
                        onClick = { vm.triggerLogout(context) },
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OllamaBorder)
                    ) { Text("Logout", color = OllamaTextDim) }
                }
            }

            Text(
                "Tap Login — a browser page will open so you can authorize your device. " +
                "After approving, tap \"Done\" in the dialog that appears here.",
                color = OllamaTextDim, fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ─────────────────────────────────────────────
// MODELS SCREEN
// ─────────────────────────────────────────────
@Composable
fun ModelsScreen(vm: MainViewModel, context: Context) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

        SectionCard("PULL MODEL", "Download from Ollama registry") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OllamaTextField(
                    vm.customModelPullName, { vm.customModelPullName = it }, "Model name (e.g. qwen2:0.5b)",
                    Modifier.weight(1f), tag = "model_pull_input",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); vm.pullModel(context) })
                )
                Button(
                    onClick = { focusManager.clearFocus(); vm.pullModel(context) },
                    enabled = !vm.isPullingActive && vm.apiOnline,
                    colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg),
                    modifier = Modifier.height(56.dp).testTag("pull_start_btn")
                ) { Text("Pull", fontWeight = FontWeight.Bold) }
            }

            if (vm.isPullingActive) {
                Text(vm.pullProgressStatus, color = OllamaGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (vm.pullProgressPercent >= 0)
                    LinearProgressIndicator({ vm.pullProgressPercent / 100f }, Modifier.fillMaxWidth(), color = OllamaGreen, trackColor = OllamaBorder)
                else
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = OllamaGreen, trackColor = OllamaBorder)
            }

            // Quick picks
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("qwen2:0.5b", "gemma:2b", "tinyllama", "phi3:mini").forEach { name ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(OllamaCardAlt)
                            .border(1.dp, OllamaBorder, RoundedCornerShape(6.dp))
                            .clickable { vm.customModelPullName = name }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(name, fontSize = 9.sp, color = OllamaTextDim, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }

        Text("INSTALLED MODELS", color = OllamaTextDim, fontSize = 10.sp, letterSpacing = 1.sp)

        if (!vm.apiOnline) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null, tint = OllamaTextDim, modifier = Modifier.size(36.dp))
                    Text("Server offline\nStart the daemon to see installed models", color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 13.sp)
                }
            }
        } else if (vm.modelList.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No models installed\nPull a model above to get started", color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.modelList) { model ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(OllamaCard)
                            .border(1.dp, OllamaBorder, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.name, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(String.format("%.2f GB", model.size / 1e9), color = OllamaTextDim, fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { vm.selectedModelChat = model.name; vm.agentModel = model.name; Toast.makeText(context, "Selected: ${model.name}", Toast.LENGTH_SHORT).show() },
                                border = androidx.compose.foundation.BorderStroke(1.dp, OllamaGreen),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("Use", color = OllamaGreen, fontSize = 12.sp) }
                            IconButton(
                                onClick = { vm.deleteModel(context, model.name) },
                                modifier = Modifier.size(32.dp)
                            ) { Icon(Icons.Default.Delete, null, tint = OllamaRed, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// CHAT SCREEN
// ─────────────────────────────────────────────
@Composable
fun ChatScreen(vm: MainViewModel, context: Context) {
    val listState    = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Scroll when a new message is added
    LaunchedEffect(vm.chatHistory.size) { if (vm.chatHistory.isNotEmpty()) listState.animateScrollToItem(vm.chatHistory.size - 1) }
    // Scroll while streaming (last message content grows)
    val lastMsgContent = vm.chatHistory.lastOrNull()?.content ?: ""
    LaunchedEffect(lastMsgContent) {
        if (vm.isGeneratingResponse && vm.chatHistory.isNotEmpty())
            listState.animateScrollToItem(vm.chatHistory.size - 1)
    }
    LaunchedEffect(vm.selectedModelChat) { if (vm.selectedModelChat.isNotEmpty() && vm.chatHistory.isEmpty()) vm.startChatSession() }

    Column(Modifier.fillMaxSize()) {
        // Header bar
        Row(
            Modifier.fillMaxWidth().background(OllamaSurface).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (vm.apiOnline) OllamaGreen else OllamaRed))
                Text(vm.selectedModelChat.ifEmpty { "No model selected" }, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            TextButton(onClick = { vm.startChatSession() }) { Text("New Chat", color = OllamaGreen, fontSize = 12.sp) }
        }

        if (vm.selectedModelChat.isEmpty() || !vm.apiOnline) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🤖", fontSize = 40.sp)
                    Text(
                        if (!vm.apiOnline) "Start the Ollama daemon first" else "Select a model from the Models tab",
                        color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(vm.chatHistory) { msg -> ChatMessageBubble(msg) }
            }
        }

        // Input row
        Row(
            Modifier
                .fillMaxWidth()
                .background(OllamaSurface)
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OllamaTextField(
                vm.chatMessageInput, { vm.chatMessageInput = it }, "Message...",
                Modifier.weight(1f).testTag("chat_input"),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { focusManager.clearFocus(); vm.sendChatMessage(context) })
            )
            FloatingActionButton(
                onClick = { focusManager.clearFocus(); vm.sendChatMessage(context) },
                modifier = Modifier.size(52.dp).testTag("chat_send_btn"),
                containerColor = OllamaGreen, contentColor = OllamaBg
            ) {
                if (vm.isGeneratingResponse) CircularProgressIndicator(Modifier.size(20.dp), color = OllamaBg, strokeWidth = 2.dp)
                else Icon(Icons.Default.Send, null, Modifier.size(20.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────
// AGENT SCREEN
// ─────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(vm: MainViewModel, context: Context) {
    val agentListState = rememberLazyListState()
    val focusManager   = LocalFocusManager.current

    LaunchedEffect(vm.agentSteps.size) { if (vm.agentSteps.isNotEmpty()) agentListState.animateScrollToItem(vm.agentSteps.size - 1) }

    var agentTab by remember { mutableStateOf(0) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }

    // Back press in Files/Steps → return to Chat
    BackHandler(enabled = agentTab != 0) { agentTab = 0 }

    if (showFolderPicker) {
        FolderPickerDialog(
            initialPath = vm.agentWorkingDir,
            onSelect = { path ->
                vm.updateAgentWorkingDir(path)
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false }
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Agent header
        Column(Modifier.fillMaxWidth().background(OllamaSurface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // Model selector
                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded && vm.modelList.isNotEmpty(),
                    onExpandedChange = { if (vm.modelList.isNotEmpty()) modelDropdownExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.menuAnchor()) {
                        Text("DEVHIVE AGENT", color = OllamaGreen, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.clickable { if (vm.modelList.isNotEmpty()) modelDropdownExpanded = true }
                        ) {
                            Text(
                                vm.agentModel.ifEmpty { "No model selected" },
                                color = if (vm.agentModel.isEmpty()) OllamaRed else OllamaText,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                            if (vm.modelList.isNotEmpty()) {
                                Icon(Icons.Default.KeyboardArrowDown, null, tint = OllamaGreen, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded && vm.modelList.isNotEmpty(),
                        onDismissRequest = { modelDropdownExpanded = false },
                        containerColor = OllamaCard
                    ) {
                        vm.modelList.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.name, color = OllamaText, fontSize = 13.sp) },
                                onClick = { vm.agentModel = model.name; modelDropdownExpanded = false },
                                colors = MenuDefaults.itemColors(textColor = OllamaText),
                                leadingIcon = if (vm.agentModel == model.name) ({
                                    Icon(Icons.Default.Check, null, tint = OllamaGreen, modifier = Modifier.size(14.dp))
                                }) else null
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (vm.apiOnline) OllamaGreen else OllamaRed))
                    Text(if (vm.isAgentRunning) "Running..." else if (vm.apiOnline) "Ready" else "Server offline", color = if (vm.isAgentRunning) OllamaGreen else OllamaTextDim, fontSize = 11.sp)
                }
            }
            // Working dir row with folder picker button
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Home, null, tint = OllamaTextDim, modifier = Modifier.size(14.dp))
                Text(
                    vm.agentWorkingDir,
                    color = OllamaTextDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { showFolderPicker = true },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(OllamaCard)
                ) {
                    Text("📁", fontSize = 14.sp)
                }
            }
        }

        // Sub-tabs with back arrow when not on Chat
        Row(Modifier.fillMaxWidth().background(OllamaSurface), verticalAlignment = Alignment.CenterVertically) {
            if (agentTab != 0) {
                IconButton(onClick = { agentTab = 0 }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back to Chat", tint = OllamaGreen, modifier = Modifier.size(20.dp))
                }
            }
            TabRow(
                selectedTabIndex = agentTab,
                modifier = Modifier.weight(1f),
                containerColor = OllamaSurface,
                contentColor = OllamaGreen,
                indicator = { tabPositions ->
                    Box(
                        Modifier
                            .tabIndicatorOffset(tabPositions[agentTab])
                            .height(2.dp)
                            .background(OllamaGreen)
                    )
                }
            ) {
                listOf("Chat", "Files", "Steps").forEachIndexed { i, title ->
                    Tab(
                        selected = agentTab == i,
                        onClick = { agentTab = i },
                        text = { Text(title, fontSize = 12.sp, color = if (agentTab == i) OllamaGreen else OllamaTextDim) }
                    )
                }
            }
        }

        when (agentTab) {
            0 -> AgentChatPane(vm, context, agentListState, focusManager)
            1 -> AgentFilesPane(vm, context)
            2 -> AgentStepsPane(vm)
        }
    }
}

@Composable
fun FolderPickerDialog(
    initialPath: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPath by remember { mutableStateOf(
        if (File(initialPath).exists()) initialPath else "/storage/emulated/0"
    ) }

    val currentDir = File(currentPath)
    val dirs = remember(currentPath) {
        (currentDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name } ?: emptyList())
    }
    val parent = currentDir.parentFile

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OllamaCard,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pick Project Folder", color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                // Quick shortcuts
                val ctx = LocalContext.current
                val termuxHome = "/data/data/com.termux/files/home"
                val hasTermux  = remember {
                    File(termuxHome).exists() ||
                    try { ctx.packageManager.getPackageInfo("com.termux", 0); true }
                    catch (_: Exception) { false }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "Internal"  to "/storage/emulated/0",
                        "Downloads" to "/storage/emulated/0/Download",
                        "Documents" to "/storage/emulated/0/Documents"
                    ).plus(if (hasTermux) listOf("Termux" to termuxHome) else emptyList())
                     .forEach { (label, path) ->
                        if (File(path).exists()) {
                            TextButton(
                                onClick = { currentPath = path },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(OllamaSurface)
                            ) {
                                Text(
                                    label,
                                    color = if (label == "Termux") OllamaBlue else OllamaGreen,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                // Current path + up button
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(OllamaSurface).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (parent != null) {
                        IconButton(onClick = { currentPath = parent.absolutePath }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ArrowBack, null, tint = OllamaGreen, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(
                        currentPath,
                        color = OllamaTextDim, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Directory list
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (dirs.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                Text("No subdirectories", color = OllamaTextDim, fontSize = 12.sp)
                            }
                        }
                    }
                    items(dirs) { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { currentPath = dir.absolutePath }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("📂", fontSize = 16.sp)
                            Text(dir.name, color = OllamaText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.KeyboardArrowRight, null, tint = OllamaTextDim, modifier = Modifier.size(14.dp))
                        }
                        HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelect(currentPath) },
                colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen)
            ) { Text("Select This Folder", color = OllamaBg, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = OllamaTextDim) }
        }
    )
}

@Composable
fun AgentChatPane(vm: MainViewModel, context: Context, listState: androidx.compose.foundation.lazy.LazyListState, focusManager: androidx.compose.ui.focus.FocusManager) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            if (vm.agentSteps.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("🤖", fontSize = 36.sp)
                            Text("Describe a task for the agent.\nIt can read, write, edit files and run commands.", color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 13.sp)
                        }
                    }
                }
            }
            items(vm.agentSteps) { step ->
                AgentStepBubble(step)
            }
        }

        // Input
        Row(
            Modifier.fillMaxWidth().background(OllamaSurface).padding(8.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OllamaTextField(
                vm.agentInput, { vm.agentInput = it }, "Give the agent a task...",
                Modifier.weight(1f),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { focusManager.clearFocus(); vm.runAgent(context) })
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FloatingActionButton(
                    onClick = {
                        focusManager.clearFocus()
                        if (vm.isAgentRunning) vm.stopAgent() else vm.runAgent(context)
                    },
                    modifier = Modifier.size(52.dp),
                    containerColor = if (vm.isAgentRunning) Color(0xFFCC2222) else OllamaGreen,
                    contentColor = Color.White
                ) {
                    if (vm.isAgentRunning)
                        Box(Modifier.size(18.dp).background(Color.White, RoundedCornerShape(3.dp)))
                    else
                        Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = { vm.agentSteps.clear() },
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(OllamaCard)
                ) { Icon(Icons.Default.Delete, null, tint = OllamaTextDim, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
fun AgentStepBubble(step: AgentStep) {
    val (bg, textColor, icon) = when (step.type) {
        "user"        -> Triple(OllamaGreen,       OllamaBg,      "👤")
        "assistant"   -> Triple(OllamaCard,        OllamaText,    "🤖")
        "think"       -> Triple(Color(0xFF1A1A2E), OllamaPurple,  "💭")
        "tool_call"   -> Triple(Color(0xFF1A2A1A), OllamaGreen,   "🔧")
        "tool_result" -> Triple(Color(0xFF0D1A0D), TerminalGreen, "📤")
        "spawn"       -> Triple(Color(0xFF1A1A2E), OllamaBlue,    "🌱")
        "error"       -> Triple(Color(0xFF2A0D0D), OllamaRed,     "❌")
        else          -> Triple(OllamaCard,        OllamaTextDim, "•")
    }

    val displayContent = if (step.type == "assistant") {
        step.content
            .replace(Regex("```tool[\\s\\S]*?```"), "")
            .replace(Regex("```json[\\s\\S]*?```"), "")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .trim()
    } else step.content

    if (displayContent.isBlank() && step.type == "assistant") return

    // Collapse threshold — tool results and think blocks can be very long
    val THRESHOLD = 220
    val collapsible = displayContent.length > THRESHOLD &&
                      step.type in listOf("tool_result", "think", "sequential_thinking")
    // tool_result starts collapsed; think starts collapsed only when very long
    val defaultExpanded = when {
        step.type == "tool_result" && displayContent.length > THRESHOLD -> false
        step.type in listOf("think", "sequential_thinking") && displayContent.length > 600 -> false
        else -> true
    }
    var expanded by remember { mutableStateOf(defaultExpanded) }

    // Compute what to show when collapsed
    val firstLine = displayContent.lines().firstOrNull()?.take(120) ?: ""
    val lineCount = displayContent.lines().size
    val shownText = if (!expanded && collapsible) firstLine else displayContent

    val isUser = step.type == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
        ) {
            // Main content
            Text(
                text = if (step.type != "user") "$icon $shownText" else shownText,
                color = textColor,
                fontSize = 12.sp,
                fontFamily = if (step.type in listOf("tool_result", "think")) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
            // Expand / collapse toggle
            if (collapsible) {
                HorizontalDivider(color = bg.copy(alpha = 0f), thickness = 0.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .background(Color.Black.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (expanded) "▲ collapse" else "▼ show all  ($lineCount lines)",
                        color = textColor.copy(alpha = 0.65f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (!expanded) {
                        Text(
                            "${displayContent.length} chars",
                            color = textColor.copy(alpha = 0.45f),
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// CHAT BUBBLE — with code-block parsing + copy
// ─────────────────────────────────────────────

sealed class ChatSegment {
    data class PlainText(val text: String) : ChatSegment()
    data class CodeBlock(val language: String, val code: String) : ChatSegment()
}

fun parseChatContent(content: String): List<ChatSegment> {
    if (content.isBlank()) return listOf(ChatSegment.PlainText(content))
    val result = mutableListOf<ChatSegment>()
    val regex  = Regex("```(\\w*?)\\n([\\s\\S]*?)```|```([\\s\\S]*?)```")
    var last   = 0
    regex.findAll(content).forEach { m ->
        val before = content.substring(last, m.range.first).trim()
        if (before.isNotBlank()) result.add(ChatSegment.PlainText(before))
        val lang = m.groupValues[1].trim()
        val code = m.groupValues[2].ifBlank { m.groupValues[3] }.trimEnd()
        result.add(ChatSegment.CodeBlock(lang, code))
        last = m.range.last + 1
    }
    val tail = content.substring(last).trim()
    if (tail.isNotBlank()) result.add(ChatSegment.PlainText(tail))
    if (result.isEmpty()) result.add(ChatSegment.PlainText(content))
    return result
}

@Composable
fun ChatMessageBubble(msg: ChatMessage) {
    val isUser    = msg.role == "user"
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (msg.content.isEmpty() && !isUser) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp))
                    .background(OllamaCard)
                    .border(1.dp, OllamaBorder, RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(Modifier.size(16.dp), color = OllamaGreen, strokeWidth = 2.dp) }
            return@Row
        }

        val segments   = remember(msg.content) { parseChatContent(msg.content) }
        val bubbleShape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isUser) 16.dp else 0.dp,
            bottomEnd   = if (isUser) 0.dp else 16.dp
        )

        Column(Modifier.widthIn(max = 290.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            segments.forEachIndexed { idx, segment ->
                when (segment) {
                    is ChatSegment.PlainText -> {
                        if (segment.text.isBlank()) return@forEachIndexed
                        val shape = if (segments.size == 1) bubbleShape else RoundedCornerShape(12.dp)
                        Column(
                            Modifier
                                .clip(shape)
                                .background(if (isUser) OllamaGreen else OllamaCard)
                                .border(if (isUser) 0.dp else 1.dp, OllamaBorder, shape)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(segment.text, color = if (isUser) OllamaBg else OllamaText, fontSize = 14.sp)
                            if (!isUser && segment.text.length > 10) {
                                TextButton(
                                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(segment.text)) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.align(Alignment.End).height(20.dp)
                                ) { Text("copy", color = OllamaTextDim, fontSize = 9.sp) }
                            }
                        }
                    }
                    is ChatSegment.CodeBlock -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0D1117))
                                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    segment.language.ifBlank { "code" },
                                    color = OllamaGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold
                                )
                                TextButton(
                                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(segment.code)) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(18.dp)
                                ) { Text("copy", color = OllamaGreen, fontSize = 9.sp) }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(segment.code, color = Color(0xFF79C0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// MARKDOWN VIEWER
// ─────────────────────────────────────────────

sealed class MdSegment {
    data class Heading(val level: Int, val text: String) : MdSegment()
    data class Paragraph(val text: String) : MdSegment()
    data class BulletItem(val indent: Int, val text: String) : MdSegment()
    data class NumberedItem(val num: Int, val text: String) : MdSegment()
    data class MdCodeBlock(val lang: String, val code: String) : MdSegment()
    data class Quote(val text: String) : MdSegment()
    object Rule : MdSegment()
    object Blank : MdSegment()
}

fun parseMd(md: String): List<MdSegment> {
    val segs  = mutableListOf<MdSegment>()
    val lines = md.lines()
    var i     = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        when {
            line.startsWith("```") -> {
                val lang = line.drop(3).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                segs.add(MdSegment.MdCodeBlock(lang, code.toString().trimEnd()))
            }
            line.matches(Regex("^#{1,6} .*")) -> {
                val lvl = line.takeWhile { it == '#' }.length
                segs.add(MdSegment.Heading(lvl, line.drop(lvl + 1)))
            }
            line.matches(Regex("^\\s*[-*+] .*")) -> {
                val indent = line.length - line.trimStart().length
                segs.add(MdSegment.BulletItem(indent / 2, line.trimStart().drop(2)))
            }
            line.matches(Regex("^\\s*\\d+\\. .*")) -> {
                val num = line.trimStart().takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                segs.add(MdSegment.NumberedItem(num, line.trimStart().dropWhile { it.isDigit() || it == '.' }.drop(1)))
            }
            line.startsWith("> ") -> segs.add(MdSegment.Quote(line.drop(2)))
            line.matches(Regex("^[-*_]{3,}\\s*$")) -> segs.add(MdSegment.Rule)
            line.isBlank() -> segs.add(MdSegment.Blank)
            else -> segs.add(MdSegment.Paragraph(line))
        }
        i++
    }
    return segs
}

@Composable
fun MarkdownViewer(markdown: String, modifier: Modifier = Modifier) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val segments  = remember(markdown) { parseMd(markdown) }
    val scrollState = rememberScrollState()
    Column(
        modifier.verticalScroll(scrollState).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        segments.forEach { seg ->
            when (seg) {
                is MdSegment.Heading -> {
                    if (seg.level == 1) Spacer(Modifier.height(8.dp))
                    Text(
                        seg.text,
                        color = OllamaText,
                        fontSize = when (seg.level) { 1 -> 22.sp; 2 -> 18.sp; 3 -> 15.sp; else -> 13.sp },
                        fontWeight = if (seg.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                        lineHeight = when (seg.level) { 1 -> 28.sp; 2 -> 24.sp; else -> 20.sp }
                    )
                    if (seg.level == 1) HorizontalDivider(color = OllamaBorder.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                }
                is MdSegment.Paragraph -> Text(seg.text, color = OllamaText, fontSize = 13.sp, lineHeight = 20.sp)
                is MdSegment.BulletItem -> Row(
                    Modifier.padding(start = (seg.indent * 14).dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("•", color = OllamaGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(seg.text, color = OllamaText, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                }
                is MdSegment.NumberedItem -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("${seg.num}.", color = OllamaGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(24.dp))
                    Text(seg.text, color = OllamaText, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                }
                is MdSegment.MdCodeBlock -> {
                    val code = seg.code
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0D1117))
                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(seg.lang.ifBlank { "code" }, color = OllamaGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            TextButton(
                                onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(code)) },
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.height(18.dp)
                            ) { Text("copy", color = OllamaGreen, fontSize = 9.sp) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(code, color = Color(0xFF79C0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(2.dp))
                }
                is MdSegment.Quote -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF0A1A14))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.width(3.dp).height(18.dp).background(OllamaGreen).clip(RoundedCornerShape(2.dp)))
                    Text(seg.text, color = OllamaTextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                MdSegment.Rule  -> HorizontalDivider(color = OllamaBorder, modifier = Modifier.padding(vertical = 6.dp))
                MdSegment.Blank -> Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────
// AGENT FILES PANE
// ─────────────────────────────────────────────
@Composable
fun AgentFilesPane(vm: MainViewModel, context: Context) {
    if (vm.agentSelectedFile != null) {
        val isMarkdown = vm.agentSelectedFile!!.name.endsWith(".md", ignoreCase = true) ||
                         vm.agentSelectedFile!!.name.endsWith(".markdown", ignoreCase = true)
        var isPreview  by remember { mutableStateOf(isMarkdown) }

        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${if (isMarkdown) "📝" else "✏️"} ${vm.agentSelectedFile!!.name}",
                    color = if (isMarkdown) OllamaBlue else OllamaGreen,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isMarkdown) {
                        TextButton(
                            onClick = { isPreview = !isPreview },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(if (isPreview) "✏️ Edit" else "👁 Preview", color = OllamaBlue, fontSize = 11.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = { vm.saveCurrentFile(); Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() },
                        border = androidx.compose.foundation.BorderStroke(1.dp, OllamaGreen),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("Save", color = OllamaGreen, fontSize = 12.sp) }
                    TextButton(onClick = { vm.agentSelectedFile = null }) { Text("Close", color = OllamaTextDim, fontSize = 12.sp) }
                }
            }

            if (isPreview && isMarkdown) {
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(OllamaCard)
                        .border(1.dp, OllamaBorder, RoundedCornerShape(8.dp))
                ) {
                    MarkdownViewer(vm.agentFileContent, Modifier.fillMaxSize())
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TerminalBg)
                        .border(1.dp, OllamaBorder, RoundedCornerShape(8.dp))
                ) {
                    OutlinedTextField(
                        value = vm.agentFileContent,
                        onValueChange = { vm.agentFileContent = it },
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = OllamaText
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            cursorColor = OllamaGreen, focusedTextColor = OllamaText, unfocusedTextColor = OllamaText
                        )
                    )
                }
            }
        }
    } else {
        // ── File tree with delete + rename ────────────────────────────────────
        var deleteTarget by remember { mutableStateOf<File?>(null) }
        var renameTarget by remember { mutableStateOf<File?>(null) }
        var newNameInput by remember { mutableStateOf("") }

        // ── Delete confirmation dialog ──────────────────────────────────────
        deleteTarget?.let { tgt ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                containerColor   = OllamaCard,
                title = { Text("Delete ${if (tgt.isDirectory) "folder" else "file"}?", color = OllamaText, fontWeight = FontWeight.Bold) },
                text  = { Text("\"${tgt.name}\" will be permanently deleted.", color = OllamaTextDim, fontSize = 13.sp) },
                confirmButton = {
                    Button(
                        onClick = { vm.deleteFile(tgt); deleteTarget = null },
                        colors  = ButtonDefaults.buttonColors(containerColor = OllamaRed)
                    ) { Text("Delete", color = Color.White, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = OllamaTextDim) } }
            )
        }

        // ── Rename dialog ───────────────────────────────────────────────────
        renameTarget?.let { tgt ->
            LaunchedEffect(tgt) { newNameInput = tgt.name }
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                containerColor   = OllamaCard,
                title = { Text("Rename", color = OllamaText, fontWeight = FontWeight.Bold) },
                text  = {
                    OutlinedTextField(
                        value = newNameInput,
                        onValueChange = { newNameInput = it },
                        label  = { Text("New name", color = OllamaTextDim) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = OllamaGreen,
                            unfocusedBorderColor = OllamaBorder,
                            focusedLabelColor    = OllamaGreen,
                            cursorColor          = OllamaGreen,
                            focusedTextColor     = OllamaText,
                            unfocusedTextColor   = OllamaText
                        )
                    )
                },
                confirmButton = {
                    Button(
                        onClick  = { vm.renameFile(tgt, newNameInput); renameTarget = null },
                        enabled  = newNameInput.trim().isNotBlank() && newNameInput.trim() != tgt.name,
                        colors   = ButtonDefaults.buttonColors(containerColor = OllamaGreen)
                    ) { Text("Rename", color = OllamaBg, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = OllamaTextDim) } }
            )
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(OllamaSurface).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                val parent = File(vm.agentWorkingDir).parentFile
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (parent != null) {
                        TextButton(onClick = { vm.updateAgentWorkingDir(parent.absolutePath) }, contentPadding = PaddingValues(0.dp)) {
                            Text("↑ Up", color = OllamaTextDim, fontSize = 12.sp)
                        }
                    }
                    Text(File(vm.agentWorkingDir).name, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                IconButton(onClick = { vm.refreshFileTree() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, null, tint = OllamaTextDim, modifier = Modifier.size(16.dp))
                }
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(vm.agentFileTree) { file ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.openFile(file) }
                            .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(if (file.isDirectory) "📂" else "📄", fontSize = 15.sp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name,
                                color = if (file.isDirectory) OllamaBlue else OllamaText,
                                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (file.isFile) Text(formatFileSize(file.length()), color = OllamaTextDim, fontSize = 10.sp)
                        }
                        // ── Rename ──
                        IconButton(
                            onClick  = { renameTarget = file },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Edit, "Rename", tint = OllamaTextDim, modifier = Modifier.size(15.dp))
                        }
                        // ── Delete ──
                        IconButton(
                            onClick  = { deleteTarget = file },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Delete, "Delete", tint = OllamaRed.copy(alpha = 0.65f), modifier = Modifier.size(15.dp))
                        }
                        if (file.isDirectory) Icon(Icons.Default.ArrowForward, null, tint = OllamaTextDim, modifier = Modifier.size(13.dp))
                    }
                    HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
fun AgentStepsPane(vm: MainViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.agentSteps.size) { if (vm.agentSteps.isNotEmpty()) listState.animateScrollToItem(vm.agentSteps.size - 1) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(vm.agentSteps) { step ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(OllamaCard).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(step.type.uppercase(), color = when (step.type) {
                    "think" -> OllamaPurple; "tool_call" -> OllamaGreen; "tool_result" -> TerminalGreen
                    "error" -> OllamaRed; "user" -> OllamaBlue; else -> OllamaTextDim
                }, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                Text(step.content, color = OllamaText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ─────────────────────────────────────────────
// TERMINAL SCREEN
// ─────────────────────────────────────────────
@Composable
fun TerminalScreen(vm: MainViewModel, context: Context) {
    val listState    = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(vm.liveLogs.size) { if (vm.liveLogs.isNotEmpty()) listState.animateScrollToItem(vm.liveLogs.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(OllamaSurface).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LIVE TERMINAL", color = OllamaGreen, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.clearLogs() }) { Text("Clear", color = OllamaRed, fontSize = 12.sp) }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).background(TerminalBg)
        ) {
            if (vm.liveLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No logs yet.\nStart the daemon to see output.", color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(vm.liveLogs) { line ->
                        Text(line, color = TerminalGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Terminal input
        Row(
            Modifier.fillMaxWidth().background(OllamaSurface).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("ollama", color = OllamaGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = vm.terminalInput, onValueChange = { vm.terminalInput = it },
                placeholder = { Text("command...", color = OllamaTextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.weight(1f).testTag("terminal_input"),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TerminalGreen),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = OllamaGreen, unfocusedBorderColor = OllamaBorder,
                    cursorColor          = OllamaGreen
                ),
                shape = RoundedCornerShape(6.dp),
                keyboardOptions  = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions  = KeyboardActions(onSend = { focusManager.clearFocus(); vm.runTerminalCommand(context) })
            )
            IconButton(
                onClick = { focusManager.clearFocus(); vm.runTerminalCommand(context) },
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(OllamaGreen).testTag("terminal_exec_btn")
            ) { Icon(Icons.Default.Send, null, tint = OllamaBg, modifier = Modifier.size(18.dp)) }
        }
    }
}

// ─────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
}

