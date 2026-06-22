import os

filepath = "app/src/main/java/com/example/MainActivity.kt"
with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

replacements = []

# 1. State variables
rep1_target = """    // ── MLC LLM backend ───────────────────────────────────────────────────────
    var activeBackend        by mutableStateOf("ollama")   // "ollama" | "mlc"
    val mlcEngine            = MlcEngine(ctx)
    var mlcEngineRunning     by mutableStateOf(false)
    var mlcSelectedModel     by mutableStateOf<File?>(null)
    var mlcAvailableModels   by mutableStateOf<List<File>>(emptyList())
    var mlcContextSize       by mutableStateOf(2048)
    var mlcTemperature       by mutableStateOf(0.7f)
    var mlcMaxTokens         by mutableStateOf(2048)
    var isLoadingMlc         by mutableStateOf(false)
    var mlcLoadStatus        by mutableStateOf("")
    var isDownloadingMlc     by mutableStateOf(false)
    var mlcDownloadProgress  by mutableStateOf(0)
    var mlcDownloadStatus    by mutableStateOf("")"""

rep1_replace = """    // ── llama.cpp backend ─────────────────────────────────────────────────────
    var activeBackend          by mutableStateOf("ollama")  // "ollama" | "llamacpp"
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
    var llamaHealthStatus      by mutableStateOf("")"""

replacements.append((rep1_target, rep1_replace))

# 2. scanMlcModels() in init
rep2_target = "        scanMlcModels()"
rep2_replace = """        llamaBinaryInstalled = llamaServer.isBinaryInstalled
        scanGGUFs()"""
replacements.append((rep2_target, rep2_replace))

# 3. syncLogs()
rep3_target = """    private fun syncLogs() {
        synchronized(OllamaService.logBuffer) { liveLogs.addAll(OllamaService.logBuffer) }
        OllamaService.onLogReceived = { line ->
            viewModelScope.launch(Dispatchers.Main) {
                liveLogs.add(line)
                if (liveLogs.size > 1000) liveLogs.removeAt(0)
            }
        }
    }"""

rep3_replace = """    private fun syncLogs() {
        synchronized(OllamaService.logBuffer) { liveLogs.addAll(OllamaService.logBuffer) }
        OllamaService.onLogReceived = { line ->
            viewModelScope.launch(Dispatchers.Main) {
                liveLogs.add(line)
                if (liveLogs.size > 1000) liveLogs.removeAt(0)
            }
        }
        // Poll llama.cpp logs and merge into liveLogs
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
    }"""
replacements.append((rep3_target, rep3_replace))

# 4. startApiWatcher health check
rep4_target = """                if (apiOnline) {
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
                delay(3000)"""

rep4_replace = """                if (apiOnline) {
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
                // ── llama.cpp health (only while service is active) ──────────────────
                if (llamaServiceActive || LlamaService.isRunning) {
                    LlamaCppApi().checkHealth("http://127.0.0.1:$llamaPort") { ok, _ ->
                        llamaApiOnline = ok
                        if (!ok && LlamaService.isRunning == false) {
                            llamaServiceActive = false
                        }
                    }
                } else {
                    llamaApiOnline = false
                }
                delay(3000)"""
replacements.append((rep4_target, rep4_replace))

# 5. Functions block
rep5_target = """    // ── MLC LLM backend functions ─────────────────────────────────────────────

    fun loadMlcModel(context: Context) {
        val dir = mlcSelectedModel ?: run {
            Toast.makeText(context, "Select an MLC model first", Toast.LENGTH_SHORT).show()
            return
        }
        isLoadingMlc = true; mlcLoadStatus = "Loading…"; mlcEngineRunning = false
        mlcEngine.loadModel(dir) { msg ->
            viewModelScope.launch(Dispatchers.Main) {
                mlcLoadStatus = msg
                liveLogs.add("[MLC] $msg")
                when {
                    msg.startsWith("✅") -> { mlcEngineRunning = true;  isLoadingMlc = false }
                    msg.startsWith("❌") -> { mlcEngineRunning = false; isLoadingMlc = false }
                }
            }
        }
    }

    fun unloadMlcModel() {
        mlcEngine.unloadModel()
        mlcEngineRunning = false; mlcLoadStatus = "Unloaded"
        liveLogs.add("[MLC] Model unloaded")
    }

    fun cancelMlcDownload() {
        mlcEngine.cancelDownload = true
        isDownloadingMlc = false; mlcDownloadStatus = "Cancelled"
    }

    fun scanMlcModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val dirs = mlcEngine.scanLocalModels()
            withContext(Dispatchers.Main) {
                mlcAvailableModels = dirs
                if (mlcSelectedModel == null && dirs.isNotEmpty()) mlcSelectedModel = dirs.first()
            }
        }
    }

    fun downloadMlcModel(context: Context, model: MlcModel) {
        if (isDownloadingMlc) return
        isDownloadingMlc = true; mlcDownloadProgress = 0; mlcDownloadStatus = "Starting…"
        mlcEngine.downloadModel(model,
            onProgress = { pct, msg -> viewModelScope.launch(Dispatchers.Main) { mlcDownloadProgress = pct; mlcDownloadStatus = msg } },
            onDone = { ok, msg, file -> viewModelScope.launch(Dispatchers.Main) {
                isDownloadingMlc = false; mlcDownloadStatus = msg
                if (ok && file != null) { scanMlcModels(); mlcSelectedModel = file }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }}
        )
    }"""

rep5_replace = """    // ── llama.cpp backend functions ───────────────────────────────────────────

    fun toggleLlamaService(context: Context) {
        if (LlamaService.isRunning) {
            context.stopService(Intent(context, LlamaService::class.java))
            llamaServiceActive = false
            llamaApiOnline = false
            llamaHealthStatus = ""
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
            llamaServiceActive = true
            llamaHealthStatus = "⏳ Starting server…"
            viewModelScope.launch(Dispatchers.IO) {
                repeat(30) { attempt ->
                    delay(1000)
                    if (!LlamaService.isRunning) {
                        viewModelScope.launch(Dispatchers.Main) {
                            llamaServiceActive = false
                            llamaApiOnline = false
                            llamaHealthStatus = "❌ Server process exited — check Logs for details"
                        }
                        return@launch
                    }
                    LlamaCppApi().checkHealth("http://127.0.0.1:$llamaPort") { ok, msg ->
                        viewModelScope.launch(Dispatchers.Main) {
                            if (ok) {
                                llamaApiOnline = true
                                llamaHealthStatus = "✅ Server running on port $llamaPort"
                            } else if (attempt == 29) {
                                llamaHealthStatus = "❌ Timeout — $msg"
                            }
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
                llamaHealthStatus = if (ok) "✅ Server healthy on port $llamaPort"
                                    else "❌ Not reachable: $msg"
                context?.let { Toast.makeText(it, llamaHealthStatus, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun cancelGGUFDownload() {
        llamaServer.cancelDownload = true
        isDownloadingGGUF = false
        ggufDownloadStatus = "Cancelled"
    }

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
    }"""
replacements.append((rep5_target, rep5_replace))

# 6. Chat stream branch
rep6_target = """            activeBackend == "mlc" -> {
                viewModelScope.launch(Dispatchers.IO) {
                    mlcEngine.chatBlocking(apiMessages, mlcTemperature, mlcMaxTokens, onToken, onDone)
                }
            }"""

rep6_replace = """            activeBackend == "llamacpp" ->
                LlamaCppApi().chatStream(
                    "http://127.0.0.1:$llamaPort", apiMessages,
                    temperature = llamaTemperature,
                    maxTokens   = 2048,
                    onToken     = onToken,
                    onComplete  = onDone
                )"""
replacements.append((rep6_target, rep6_replace))

# 7. Needs MLC in Agent Screen
rep7_target = """        val needsMlc = !isCloudModel && activeBackend == "mlc"
        if (needsMlc && !mlcEngineRunning) {
            Toast.makeText(context, "Load an MLC model first (Settings → MLC LLM)", Toast.LENGTH_SHORT).show()
            return
        }"""

rep7_replace = """        val needsLlama = !isCloudModel && activeBackend == "llamacpp"
        if (needsLlama && !llamaApiOnline) {
            Toast.makeText(context, "llama.cpp server must be running for local agent", Toast.LENGTH_SHORT).show()
            return
        }"""
replacements.append((rep7_target, rep7_replace))

# 8. backend in runAgent
rep8_target = """                backend     = if (activeBackend == "mlc") "ollama" else activeBackend,"""
rep8_replace = """                backend     = activeBackend,"""
replacements.append((rep8_target, rep8_replace))

# 9. backend in spawnSubAgent
rep9_target = """                backend         = if (activeBackend == "mlc") "ollama" else activeBackend"""
rep9_replace = """                backend         = activeBackend"""
replacements.append((rep9_target, rep9_replace))

# 10. StatusPill
rep10_target = """                    StatusPill(
                        online = if (vm.activeBackend == "mlc") vm.mlcEngineRunning else vm.apiOnline,
                        label  = if (vm.activeBackend == "mlc") "MLC" else null
                    )"""

rep10_replace = """                    StatusPill(
                        online = if (vm.activeBackend == "llamacpp") vm.llamaApiOnline else vm.apiOnline,
                        label  = if (vm.activeBackend == "llamacpp") "llama.cpp" else null
                    )"""
replacements.append((rep10_target, rep10_replace))

# 11. Settings section card header and switcher
rep11_target = """        // ── MLC LLM backend section ───────────────────────────────────────────────
        SectionCard("MLC LLM BACKEND", "In-process GPU inference • Vulkan + CPU • No Knox issue") {

            // Backend switcher
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(OllamaCardAlt).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("ollama" to "Ollama", "mlc" to "MLC LLM").forEach { (key, label) ->"""

rep11_replace = """        // ── llama.cpp backend section ────────────────────────────────────────
        SectionCard("llama.cpp BACKEND", "Vulkan GPU accelerated • GGUF models") {

            // Backend switcher
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(OllamaCardAlt).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("ollama" to "Ollama", "llamacpp" to "llama.cpp").forEach { (key, label) ->"""
replacements.append((rep11_target, rep11_replace))

# 12. Settings activeBackend mlc UI block
rep12_target = """            if (vm.activeBackend == "mlc") {
                // Engine status + load/unload switch
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                vm.mlcEngineRunning -> "● Running — ${vm.mlcEngine.loadedModelName ?: ""}"
                                vm.isLoadingMlc     -> "● Loading…"
                                else                -> "○ No model loaded"
                            },
                            color = if (vm.mlcEngineRunning) OllamaGreen else OllamaTextDim,
                            fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        if (vm.mlcLoadStatus.isNotBlank()) Text(vm.mlcLoadStatus, color = OllamaTextDim, fontSize = 10.sp)
                        if (vm.mlcSelectedModel == null)
                            Text("⚠ Go to Models tab → download an MLC model → select it", color = Color(0xFFFFAA33), fontSize = 11.sp)
                    }
                    Switch(
                        checked = vm.mlcEngineRunning,
                        onCheckedChange = { on -> if (on) vm.loadMlcModel(context) else vm.unloadMlcModel() },
                        enabled = vm.mlcSelectedModel != null && !vm.isLoadingMlc,
                        colors = SwitchDefaults.colors(checkedThumbColor = OllamaBg, checkedTrackColor = OllamaGreen)
                    )
                }
                if (vm.isLoadingMlc) LinearProgressIndicator(Modifier.fillMaxWidth(), color = OllamaGreen, trackColor = OllamaBorder)

                // Selected model display
                if (vm.mlcSelectedModel != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Selected model", color = OllamaTextDim, fontSize = 10.sp)
                            Text(vm.mlcSelectedModel!!.name, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { vm.scanMlcModels() },
                            border = BorderStroke(1.dp, OllamaBorder),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("Rescan", color = OllamaTextDim, fontSize = 12.sp) }
                    }
                }

                // Context + max tokens
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OllamaTextField(vm.mlcContextSize.toString(), { vm.mlcContextSize = it.toIntOrNull() ?: 2048 }, "Context", Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OllamaTextField(vm.mlcMaxTokens.toString(), { vm.mlcMaxTokens = it.toIntOrNull() ?: 2048 }, "Max Tokens", Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                }

                // Temperature
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Temperature", color = OllamaText, fontSize = 13.sp)
                    Text(String.format("%.2f", vm.mlcTemperature), color = OllamaGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = vm.mlcTemperature, onValueChange = { vm.mlcTemperature = it },
                    valueRange = 0f..2f, steps = 39,
                    colors = SliderDefaults.colors(thumbColor = OllamaGreen, activeTrackColor = OllamaGreen, inactiveTrackColor = OllamaBorder)
                )

                // Vulkan info (auto-detected — MLC handles it internally)
                LaunchedEffect(Unit) { vm.checkVulkanSupport(context) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vulkan GPU", color = OllamaText, fontSize = 13.sp)
                        Text(
                            if (vm.deviceHasVulkan) "✅ Supported — GPU inference active"
                            else "⚠ Not detected — CPU inference only",
                            color = if (vm.deviceHasVulkan) OllamaGreen else Color(0xFFFFAA33), fontSize = 10.sp
                        )
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (vm.deviceHasVulkan) OllamaGreen else OllamaRed))
                }
                Text(
                    "MLC LLM runs in-process via JNI — no external binary, no Knox/SELinux restriction.",
                    color = OllamaTextDim, fontSize = 10.sp
                )
            }"""

rep12_replace = """            if (vm.activeBackend == "llamacpp") {
                // Server status + toggle
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (vm.llamaApiOnline) "● Running · Port ${vm.llamaPort}"
                            else if (vm.llamaServiceActive) "● Starting…"
                            else "○ Stopped",
                            color = if (vm.llamaApiOnline) OllamaGreen else OllamaTextDim, fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        if (vm.llamaSelectedModel != null) {
                            Text("Model: ${vm.llamaSelectedModel!!.name}", color = OllamaTextDim, fontSize = 10.sp)
                        } else {
                            Text(
                                "⚠ Go to Models tab → download a GGUF model → select it",
                                color = Color(0xFFFFAA33), fontSize = 11.sp
                            )
                        }
                        if (vm.llamaHealthStatus.isNotBlank() && !vm.llamaApiOnline) {
                            Text(vm.llamaHealthStatus, color = OllamaTextDim, fontSize = 11.sp)
                        }
                    }
                    Switch(
                        checked = vm.llamaServiceActive || vm.llamaApiOnline,
                        onCheckedChange = { vm.toggleLlamaService(context) },
                        enabled = vm.llamaSelectedModel != null,
                        colors = SwitchDefaults.colors(checkedThumbColor = OllamaBg, checkedTrackColor = OllamaGreen)
                    )
                }

                // Port + GPU layers + Context config
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OllamaTextField(vm.llamaPort, { vm.llamaPort = it }, "Port", Modifier.width(100.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OllamaTextField(vm.llamaGpuLayers.toString(), { vm.llamaGpuLayers = it.toIntOrNull() ?: 99 }, "GPU Layers", Modifier.width(110.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OllamaTextField(vm.llamaContextSize.toString(), { vm.llamaContextSize = it.toIntOrNull() ?: 4096 }, "Context", Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OllamaTextField(vm.llamaThreads.toString(), { vm.llamaThreads = it.toIntOrNull() ?: 4 }, "Threads", Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OllamaTextField(vm.llamaBatchSize.toString(), { vm.llamaBatchSize = it.toIntOrNull() ?: 512 }, "Batch", Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                }

                // Temperature
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Temperature", color = OllamaText, fontSize = 13.sp)
                    Text(String.format("%.2f", vm.llamaTemperature), color = OllamaGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = vm.llamaTemperature, onValueChange = { vm.llamaTemperature = it },
                    valueRange = 0f..2f, steps = 39,
                    colors = SliderDefaults.colors(thumbColor = OllamaGreen, activeTrackColor = OllamaGreen, inactiveTrackColor = OllamaBorder)
                )

                // Vulkan GPU info
                LaunchedEffect(Unit) { vm.checkVulkanSupport(context) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GPU Acceleration (Vulkan)", color = OllamaText, fontSize = 13.sp)
                        Text(
                            if (vm.deviceHasVulkan) "✅ Supported — set GPU Layers > 0 to offload"
                            else "⚠ Not detected — CPU inference only",
                            color = if (vm.deviceHasVulkan) OllamaGreen else Color(0xFFFFAA33), fontSize = 10.sp
                        )
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (vm.deviceHasVulkan) OllamaGreen else OllamaRed))
                }
                Text(
                    "llama-server runs as a separate process with OpenAI-compatible API on port ${vm.llamaPort}.",
                    color = OllamaTextDim, fontSize = 10.sp
                )
            }"""
replacements.append((rep12_target, rep12_replace))

# 13. Curated models list
rep13_target = """        // ── MLC LLM Models ─────────────────────────────────────────────────────
        SectionCard("MLC LLM MODELS", "Pre-compiled models for Android GPU/CPU inference") {

            // Scan + count row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Local MLC models", color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${vm.mlcAvailableModels.size} found in ${vm.mlcEngine.modelsDir.absolutePath.substringAfter("/Android")}", color = OllamaTextDim, fontSize = 10.sp)
                }
                Button(
                    onClick = { vm.scanMlcModels() },
                    colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg)
                ) { Text("Scan", fontWeight = FontWeight.Bold) }
            }

            // Installed models
            if (vm.mlcAvailableModels.isNotEmpty()) {
                vm.mlcAvailableModels.forEach { dir ->
                    val selected = vm.mlcSelectedModel?.absolutePath == dir.absolutePath
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFF1A3A2A) else OllamaCardAlt)
                            .border(1.dp, if (selected) OllamaGreen else OllamaBorder, RoundedCornerShape(8.dp))
                            .clickable { vm.mlcSelectedModel = dir }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(dir.name, color = if (selected) OllamaGreen else OllamaText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sizeMb = (dir.walkTopDown().sumOf { if (it.isFile) it.length() else 0L }) / 1_000_000
                            Text("${sizeMb} MB", color = OllamaTextDim, fontSize = 10.sp)
                        }
                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = OllamaGreen, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Divider
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Divider(Modifier.weight(1f), color = OllamaBorder)
                Text("DOWNLOAD CURATED MODELS", color = OllamaTextDim, fontSize = 9.sp, letterSpacing = 1.sp)
                Divider(Modifier.weight(1f), color = OllamaBorder)
            }

            // Download progress + cancel
            if (vm.isDownloadingMlc) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.mlcDownloadStatus, color = OllamaGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { vm.cancelMlcDownload() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("✕ Cancel", color = OllamaRed, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
                LinearProgressIndicator({ vm.mlcDownloadProgress / 100f }, Modifier.fillMaxWidth(), color = OllamaGreen, trackColor = OllamaBorder)
            }

            // Curated MLC models
            MlcEngine.CURATED_MODELS.forEach { model ->
                val alreadyDownloaded = vm.mlcAvailableModels.any { it.name == model.localName }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(OllamaCard)
                        .border(1.dp, OllamaBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.displayName, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(model.hfRepo, color = OllamaTextDim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (alreadyDownloaded) Text("✓ Downloaded", color = OllamaGreen, fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            if (alreadyDownloaded) vm.mlcSelectedModel = vm.mlcAvailableModels.first { it.name == model.localName }
                            else vm.downloadMlcModel(context, model)
                        },
                        enabled = !vm.isDownloadingMlc,
                        border = BorderStroke(1.dp, if (alreadyDownloaded) OllamaGreen else OllamaBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(if (alreadyDownloaded) "Select" else "Download", color = if (alreadyDownloaded) OllamaGreen else OllamaText, fontSize = 12.sp)
                    }
                }
            }
        }"""

rep13_replace = """        // ── GGUF Models for llama.cpp ──────────────────────────────────────────
        SectionCard("GGUF MODELS", "Download quantized GGUF models for llama.cpp") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Local GGUF models", color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${vm.llamaAvailableGGUFs.size} found", color = OllamaTextDim, fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = { vm.scanGGUFs() },
                    border = BorderStroke(1.dp, OllamaBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("Rescan", color = OllamaTextDim, fontSize = 12.sp) }
            }

            if (vm.llamaAvailableGGUFs.isNotEmpty()) {
                vm.llamaAvailableGGUFs.forEach { file ->
                    val selected = vm.llamaSelectedModel?.absolutePath == file.absolutePath
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) OllamaGreen.copy(alpha = 0.1f) else OllamaCard)
                            .border(1.dp, if (selected) OllamaGreen else OllamaBorder, RoundedCornerShape(8.dp))
                            .clickable { vm.llamaSelectedModel = file }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = if (selected) OllamaGreen else OllamaText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sizeMb = file.length() / 1_000_000
                            Text("${sizeMb} MB", color = OllamaTextDim, fontSize = 10.sp)
                        }
                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = OllamaGreen, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Divider
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Divider(Modifier.weight(1f), color = OllamaBorder)
                Text("DOWNLOAD CURATED MODELS", color = OllamaTextDim, fontSize = 9.sp, letterSpacing = 1.sp)
                Divider(Modifier.weight(1f), color = OllamaBorder)
            }

            // Download progress + cancel
            if (vm.isDownloadingGGUF) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.ggufDownloadStatus, color = OllamaGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { vm.cancelGGUFDownload() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("✕ Cancel", color = OllamaRed, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
                LinearProgressIndicator({ vm.ggufDownloadProgress / 100f }, Modifier.fillMaxWidth(), color = OllamaGreen, trackColor = OllamaBorder)
            }

            // Curated GGUF models
            LlamaCppServer.CURATED_GGUF_MODELS.forEach { model ->
                val alreadyDownloaded = vm.llamaAvailableGGUFs.any { it.name == model.fileName }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(OllamaCard)
                        .border(1.dp, OllamaBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.displayName, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("${model.size} · ${model.fileName}", color = OllamaTextDim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (alreadyDownloaded) Text("✓ Downloaded", color = OllamaGreen, fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            if (alreadyDownloaded) vm.llamaSelectedModel = vm.llamaAvailableGGUFs.first { it.name == model.fileName }
                            else vm.downloadGGUF(context, model)
                        },
                        enabled = !vm.isDownloadingGGUF,
                        border = BorderStroke(1.dp, if (alreadyDownloaded) OllamaGreen else OllamaBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(if (alreadyDownloaded) "Select" else "Download", color = if (alreadyDownloaded) OllamaGreen else OllamaText, fontSize = 12.sp)
                    }
                }
            }
        }"""
replacements.append((rep13_target, rep13_replace))

# 14. Server status in Agent UI
rep14_target = """                val serverReady = if (vm.activeBackend == "mlc") vm.mlcEngineRunning else vm.apiOnline
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (serverReady) OllamaGreen else OllamaRed))
                    Text(
                        if (vm.isAgentRunning) "Running…"
                        else if (serverReady) "Ready"
                        else if (vm.activeBackend == "mlc") "MLC engine not loaded"
                        else "Server offline","""

rep14_replace = """                val serverReady = if (vm.activeBackend == "llamacpp") vm.llamaApiOnline else vm.apiOnline
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (serverReady) OllamaGreen else OllamaRed))
                    Text(
                        if (vm.isAgentRunning) "Running…"
                        else if (serverReady) "Ready"
                        else if (vm.activeBackend == "llamacpp") "llama.cpp server offline"
                        else "Server offline","""
replacements.append((rep14_target, rep14_replace))

success = True
for i, (t, r) in enumerate(replacements, 1):
    if t not in content:
        print(f"ERROR: Replacement {i} not found in file!")
        success = False
    else:
        # replace exactly once to avoid any issues
        if content.count(t) > 1:
            print(f"WARNING: Replacement {i} occurs multiple times! Replacing all.")
        content = content.replace(t, r)

if success:
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content)
    print("SUCCESS: All replacements applied successfully.")
else:
    print("FAILED: Some replacements were not found.")
