package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
    var downloadUrlState by mutableStateOf("https://github.com/sunshine0523/OllamaServer/raw/master/android/app/src/main/assets/arm64-v8a/ollama")

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
    var agentFileTree     by mutableStateOf<List<File>>(emptyList())
    var agentSelectedFile by mutableStateOf<File?>(null)
    var agentFileContent  by mutableStateOf("")
    var showFileTree      by mutableStateOf(true)

    // Terminal
    val liveLogs      = mutableStateListOf<String>()
    var terminalInput by mutableStateOf("")

    // Login
    var authLoginUrl  by mutableStateOf<String?>(null)
    var isLoggedIn    by mutableStateOf(false)

    init {
        if (!executor.ollamaFile.exists()) {
            isPreparingBinary = true
            viewModelScope.launch(Dispatchers.IO) {
                executor.copyBinaryFromAssets()
                withContext(Dispatchers.Main) {
                    isPreparingBinary = false
                    checkBinaryInstalled()
                }
            }
        } else {
            checkBinaryInstalled()
        }

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
        binaryInstalled = executor.ollamaFile.exists() && executor.ollamaFile.canExecute()
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
        executor.downloadBinary(
            downloadUrlState,
            onProgress = { pct, msg -> viewModelScope.launch(Dispatchers.Main) { setupProgress = pct; setupStatus = msg } },
            onComplete = { ok, msg ->
                viewModelScope.launch(Dispatchers.Main) {
                    isInstalling = false; setupStatus = msg
                    checkBinaryInstalled()
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // ── Login (fixed: URL-safe base64, no padding) ──────────────────────
    fun triggerLogin(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val ollamaDir  = File(ctx.filesDir, ".ollama").also { it.mkdirs() }
            val privFile   = File(ollamaDir, "id_ed25519")
            val pubFile    = File(ollamaDir, "id_ed25519.pub")
            val pubStr: String
            if (!privFile.exists() || !pubFile.exists()) {
                val (priv, pub) = SshKeyGen.generateEd25519Key()
                privFile.writeText(priv); privFile.setReadable(true, true)
                pubFile.writeText(pub)
                pubStr = pub
            } else {
                pubStr = pubFile.readText().trim()
            }
            val parts     = pubStr.trim().split(" ")
            val keyPart   = if (parts.size >= 2) "${parts[0]} ${parts[1]}" else pubStr
            val encoded   = android.util.Base64.encodeToString(
                keyPart.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            val deviceName = android.os.Build.MODEL.replace(" ", "_")
            withContext(Dispatchers.Main) {
                authLoginUrl = "https://ollama.com/connect?name=$deviceName&key=$encoded"
                liveLogs.add("Login URL ready — opening browser...")
            }
        }
    }

    fun triggerLogout(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val ollamaDir = File(ctx.filesDir, ".ollama")
            File(ollamaDir, "id_ed25519").delete()
            File(ollamaDir, "id_ed25519.pub").delete()
            withContext(Dispatchers.Main) {
                isLoggedIn = false
                Toast.makeText(context, "Logged out", Toast.LENGTH_SHORT).show()
                liveLogs.add("Logged out — SSH keys removed.")
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
        api.chatStream("http://$hostUrlState", selectedModelChat,
            chatHistory.subList(0, idx).toList(),
            onTokenGenerated = { token -> viewModelScope.launch(Dispatchers.Main) { response += token; chatHistory[idx] = ChatMessage("assistant", response) } },
            onComplete = { ok, msg -> viewModelScope.launch(Dispatchers.Main) { isGeneratingResponse = false; if (!ok) chatHistory[idx] = ChatMessage("assistant", "Error: $msg") } }
        )
    }

    // ── Agent ─────────────────────────────────────────────────────────────
    fun setAgentWorkingDir(path: String) {
        agentWorkingDir = path
        agentEngine.workingDir = path
        refreshFileTree()
    }

    fun refreshFileTree() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = File(agentWorkingDir).listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
            withContext(Dispatchers.Main) { agentFileTree = files }
        }
    }

    fun openFile(file: File) {
        if (file.isDirectory) {
            setAgentWorkingDir(file.absolutePath)
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

    fun runAgent(context: Context) {
        val task = agentInput.trim()
        if (task.isEmpty() || agentModel.isEmpty()) return
        if (!apiOnline) { Toast.makeText(context, "Server must be running for agent", Toast.LENGTH_SHORT).show(); return }
        agentInput = ""; isAgentRunning = true
        agentSteps.add(AgentStep("user", task))
        viewModelScope.launch(Dispatchers.IO) {
            agentEngine.runAgentLoop(task, agentModel, "http://$hostUrlState") { step ->
                withContext(Dispatchers.Main) { agentSteps.add(step) }
            }
            withContext(Dispatchers.Main) { isAgentRunning = false; refreshFileTree() }
        }
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
                        Text("Server", fontWeight = FontWeight.Light, fontSize = 18.sp, color = OllamaTextDim)
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

    // Login dialog
    vm.authLoginUrl?.let { url ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { vm.authLoginUrl = null },
            containerColor = OllamaCard,
            title = { Text("Sign in to Ollama", color = OllamaText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your browser needs to open to complete sign-in.", color = OllamaTextDim, fontSize = 13.sp)
                    Text("If it doesn't open automatically, copy the link below:", color = OllamaTextDim, fontSize = 13.sp)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(OllamaCardAlt).padding(8.dp)) {
                        Text(url, fontSize = 10.sp, color = OllamaGreen, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        catch (e: Exception) {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                        vm.authLoginUrl = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg)
                ) { Text("Open Browser") }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    vm.authLoginUrl = null
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
        SectionCard("CLOUD AUTH", "Sign in to access Ollama cloud models") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.triggerLogin(context) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg)
                ) { Icon(Icons.Default.AccountCircle, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Login", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { vm.triggerLogout(context) },
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OllamaBorder)
                ) { Text("Logout", color = OllamaTextDim) }
            }
            Text(
                "Login generates an SSH Ed25519 key pair and opens ollama.com to authorize your device.",
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

    LaunchedEffect(vm.chatHistory.size) { if (vm.chatHistory.isNotEmpty()) listState.animateScrollToItem(vm.chatHistory.size - 1) }
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
                items(vm.chatHistory) { msg ->
                    val isUser = msg.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                        Box(
                            Modifier
                                .widthIn(max = 290.dp)
                                .clip(RoundedCornerShape(
                                    topStart = 16.dp, topEnd = 16.dp,
                                    bottomStart = if (isUser) 16.dp else 0.dp,
                                    bottomEnd = if (isUser) 0.dp else 16.dp
                                ))
                                .background(if (isUser) OllamaGreen else OllamaCard)
                                .border(if (isUser) 0.dp else 1.dp, OllamaBorder, RoundedCornerShape(
                                    topStart = 16.dp, topEnd = 16.dp,
                                    bottomStart = if (isUser) 16.dp else 0.dp,
                                    bottomEnd = if (isUser) 0.dp else 16.dp
                                ))
                                .padding(12.dp)
                        ) {
                            if (msg.content.isEmpty() && !isUser) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = OllamaGreen, strokeWidth = 2.dp)
                            } else {
                                Text(msg.content, color = if (isUser) OllamaBg else OllamaText, fontSize = 14.sp)
                            }
                        }
                    }
                }
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

    Column(Modifier.fillMaxSize()) {
        // Agent header
        Column(Modifier.fillMaxWidth().background(OllamaSurface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("AI AGENT", color = OllamaGreen, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    Text(vm.agentModel.ifEmpty { "No model selected" }, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (vm.apiOnline) OllamaGreen else OllamaRed))
                    Text(if (vm.isAgentRunning) "Running..." else if (vm.apiOnline) "Ready" else "Server offline", color = if (vm.isAgentRunning) OllamaGreen else OllamaTextDim, fontSize = 11.sp)
                }
            }
            // Working dir
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Home, null, tint = OllamaTextDim, modifier = Modifier.size(14.dp))
                Text(vm.agentWorkingDir, color = OllamaTextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    vm.setAgentWorkingDir(
                        if (vm.agentWorkingDir == context.filesDir.absolutePath) context.getExternalFilesDir(null)?.absolutePath ?: vm.agentWorkingDir
                        else context.filesDir.absolutePath
                    )
                }, contentPadding = PaddingValues(0.dp)) { Text("Switch", color = OllamaGreen, fontSize = 10.sp) }
            }
        }

        // Sub-tabs
        TabRow(selectedTabIndex = agentTab, containerColor = OllamaSurface, contentColor = OllamaGreen, indicator = { tabPositions ->
            Box(
                Modifier
                    .tabIndicatorOffset(tabPositions[agentTab])
                    .height(2.dp)
                    .background(OllamaGreen)
            )
        }) {
            listOf("Chat", "Files", "Steps").forEachIndexed { i, title ->
                Tab(selected = agentTab == i, onClick = { agentTab = i },
                    text = { Text(title, fontSize = 12.sp, color = if (agentTab == i) OllamaGreen else OllamaTextDim) })
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
                    onClick = { focusManager.clearFocus(); vm.runAgent(context) },
                    modifier = Modifier.size(52.dp),
                    containerColor = if (vm.isAgentRunning) OllamaCardAlt else OllamaGreen,
                    contentColor = OllamaBg
                ) {
                    if (vm.isAgentRunning) CircularProgressIndicator(Modifier.size(20.dp), color = OllamaGreen, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Send, null, Modifier.size(20.dp))
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
        "user"        -> Triple(OllamaGreen,     OllamaBg,      "👤")
        "assistant"   -> Triple(OllamaCard,      OllamaText,    "🤖")
        "think"       -> Triple(Color(0xFF1A1A2E), OllamaPurple, "💭")
        "tool_call"   -> Triple(Color(0xFF1A2A1A), OllamaGreen,  "🔧")
        "tool_result" -> Triple(Color(0xFF0D1A0D), TerminalGreen,"📤")
        "spawn"       -> Triple(Color(0xFF1A1A2E), OllamaBlue,   "🌱")
        "error"       -> Triple(Color(0xFF2A0D0D), OllamaRed,    "❌")
        else          -> Triple(OllamaCard,      OllamaTextDim, "•")
    }
    val isUser = step.type == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .padding(10.dp)
        ) {
            Text(
                text = if (step.type != "user") "$icon ${step.content}" else step.content,
                color = textColor,
                fontSize = 12.sp,
                fontFamily = if (step.type in listOf("tool_result", "think")) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}

@Composable
fun AgentFilesPane(vm: MainViewModel, context: Context) {
    if (vm.agentSelectedFile != null) {
        // File editor view
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("✏️ ${vm.agentSelectedFile!!.name}", color = OllamaGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { vm.saveCurrentFile(); Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() },
                        border = androidx.compose.foundation.BorderStroke(1.dp, OllamaGreen),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("Save", color = OllamaGreen, fontSize = 12.sp) }
                    TextButton(onClick = { vm.agentSelectedFile = null }) { Text("Close", color = OllamaTextDim, fontSize = 12.sp) }
                }
            }
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(TerminalBg).border(1.dp, OllamaBorder, RoundedCornerShape(8.dp))
            ) {
                OutlinedTextField(
                    value = vm.agentFileContent,
                    onValueChange = { vm.agentFileContent = it },
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = OllamaText),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        cursorColor = OllamaGreen, focusedTextColor = OllamaText, unfocusedTextColor = OllamaText
                    )
                )
            }
        }
    } else {
        // File tree view
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(OllamaSurface).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                val parent = File(vm.agentWorkingDir).parentFile
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (parent != null) {
                        TextButton(onClick = { vm.setAgentWorkingDir(parent.absolutePath) }, contentPadding = PaddingValues(0.dp)) {
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
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(if (file.isDirectory) "📂" else "📄", fontSize = 16.sp)
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = if (file.isDirectory) OllamaBlue else OllamaText, fontSize = 13.sp)
                            if (file.isFile) Text(formatFileSize(file.length()), color = OllamaTextDim, fontSize = 10.sp)
                        }
                        if (file.isDirectory) Icon(Icons.Default.ArrowForward, null, tint = OllamaTextDim, modifier = Modifier.size(14.dp))
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

