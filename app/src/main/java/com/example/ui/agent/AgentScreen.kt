package com.example.ui.agent

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.ui.components.OllamaTextField
import com.example.ui.theme.*
import java.io.File

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
                val ctx = LocalContext.current
                val termuxHome = "/data/data/com.termux/files/home"
                val hasTermux = remember {
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
                                Text(label, color = if (label == "Termux") OllamaBlue else OllamaGreen, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
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
                        currentPath, color = OllamaTextDim, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
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
                            Modifier.fillMaxWidth().clickable { currentPath = dir.absolutePath }
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
            Button(onClick = { onSelect(currentPath) }, colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen)) {
                Text("Select This Folder", color = OllamaBg, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = OllamaTextDim) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(vm: MainViewModel, context: Context) {
    val agentListState = rememberLazyListState()
    val focusManager   = LocalFocusManager.current

    LaunchedEffect(vm.agentSteps.size) { if (vm.agentSteps.isNotEmpty()) agentListState.animateScrollToItem(vm.agentSteps.size - 1) }

    var agentTab by remember { mutableStateOf(0) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }

    BackHandler(enabled = agentTab != 0) { agentTab = 0 }

    if (showFolderPicker) {
        FolderPickerDialog(
            initialPath = vm.agentWorkingDir,
            onSelect = { path -> vm.updateAgentWorkingDir(path); showFolderPicker = false },
            onDismiss = { showFolderPicker = false }
        )
    }

    val pendingQ = vm.agentPendingQuestion
    if (pendingQ != null) {
        AlertDialog(
            onDismissRequest = { },
            containerColor = OllamaCard,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🤖", fontSize = 22.sp)
                    Text("Agent is asking…", color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pendingQ, color = OllamaText, fontSize = 13.sp, lineHeight = 18.sp)
                    HorizontalDivider(color = OllamaBorder)
                    OllamaTextField(
                        vm.agentQuestionInput, { vm.agentQuestionInput = it },
                        "Your answer…", Modifier.fillMaxWidth(), maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.submitAgentAnswer() })
                    )
                }
            },
            confirmButton = {
                Button(onClick = { vm.submitAgentAnswer() }, colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg)) {
                    Text("Answer", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(OllamaSurface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val isLlamaBackend = vm.activeBackend == "llamacpp"
                val hasModels = if (isLlamaBackend) vm.llamaAvailableGGUFs.isNotEmpty() else vm.modelList.isNotEmpty()
                val displayedAgentModel = if (isLlamaBackend) {
                    vm.llamaSelectedModel?.name ?: vm.agentModel
                } else vm.agentModel

                ExposedDropdownMenuBox(
                    expanded = modelDropdownExpanded && hasModels,
                    onExpandedChange = { if (hasModels) modelDropdownExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.menuAnchor()) {
                        Text("DEVHIVE AGENT", color = OllamaGreen, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.clickable { if (hasModels) modelDropdownExpanded = true }
                        ) {
                            Text(
                                displayedAgentModel.ifEmpty { if (hasModels) "Select model ▾" else "No model available" },
                                color = if (displayedAgentModel.isEmpty()) OllamaRed else OllamaText,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                            if (hasModels) Icon(Icons.Default.KeyboardArrowDown, null, tint = OllamaGreen, modifier = Modifier.size(16.dp))
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = modelDropdownExpanded && hasModels,
                        onDismissRequest = { modelDropdownExpanded = false },
                        containerColor = OllamaCard
                    ) {
                        if (isLlamaBackend) {
                            vm.llamaAvailableGGUFs.forEach { file ->
                                DropdownMenuItem(
                                    text = { Text(file.name, color = OllamaText, fontSize = 13.sp) },
                                    onClick = { vm.llamaSelectedModel = file; vm.agentModel = file.name; modelDropdownExpanded = false },
                                    colors = MenuDefaults.itemColors(textColor = OllamaText),
                                    leadingIcon = if (vm.llamaSelectedModel?.absolutePath == file.absolutePath) ({
                                        Icon(Icons.Default.Check, null, tint = OllamaGreen, modifier = Modifier.size(14.dp))
                                    }) else null
                                )
                            }
                        } else {
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
                }

                val serverReady = if (vm.activeBackend == "llamacpp") vm.llamaApiOnline else vm.apiOnline
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (serverReady) OllamaGreen else OllamaRed))
                    Text(
                        if (vm.isAgentRunning) "Running…"
                        else if (serverReady) "Ready"
                        else if (vm.activeBackend == "llamacpp") "llama.cpp server offline"
                        else "Server offline",
                        color = if (vm.isAgentRunning) OllamaGreen else OllamaTextDim, fontSize = 11.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Home, null, tint = OllamaTextDim, modifier = Modifier.size(14.dp))
                Text(
                    vm.agentWorkingDir, color = OllamaTextDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("max:", color = OllamaTextDim, fontSize = 9.sp)
                    IconButton(onClick = { if (vm.agentMaxSteps > 3) vm.agentMaxSteps-- }, Modifier.size(20.dp)) {
                        Text("−", color = OllamaGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("${vm.agentMaxSteps}", color = OllamaText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    IconButton(onClick = { if (vm.agentMaxSteps < 50) vm.agentMaxSteps++ }, Modifier.size(20.dp)) {
                        Text("+", color = OllamaGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(
                    onClick = { showFolderPicker = true },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(OllamaCard)
                ) { Text("📁", fontSize = 14.sp) }
            }
        }

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
                    Box(Modifier.tabIndicatorOffset(tabPositions[agentTab]).height(2.dp).background(OllamaGreen))
                }
            ) {
                listOf("Chat", "Files", "Steps", "Terminal", "Git").forEachIndexed { i, title ->
                    Tab(
                        selected = agentTab == i,
                        onClick = { agentTab = i },
                        text = { Text(title, fontSize = 11.sp, color = if (agentTab == i) OllamaGreen else OllamaTextDim) }
                    )
                }
            }
        }

        when (agentTab) {
            0 -> AgentChatPane(vm, context, agentListState, focusManager)
            1 -> AgentFilesPane(vm, context)
            2 -> AgentStepsPane(vm)
            3 -> AgentTerminalPane(vm, context)
            4 -> GitPanel(vm, context)
        }
    }
}
