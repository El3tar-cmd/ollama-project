package com.example.ui.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.ui.theme.*

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024        -> "${bytes}B"
    bytes < 1048576     -> "${bytes / 1024}KB"
    else                -> String.format("%.1fMB", bytes / 1048576.0)
}

private enum class TerminalTab { DAEMON, WORKSPACE }

@Composable
fun TerminalScreen(vm: MainViewModel, context: Context) {
    var activeTab by remember { mutableStateOf(TerminalTab.DAEMON) }

    Column(Modifier.fillMaxSize()) {

        // ── Tab bar ─────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().background(OllamaSurface),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row {
                TerminalTab.values().forEach { tab ->
                    val selected = activeTab == tab
                    val label    = if (tab == TerminalTab.DAEMON) "DAEMON LOGS" else "WORKSPACE"
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (selected) TerminalBg else Color.Transparent)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) { activeTab = tab }
                    ) {
                        Column {
                            Text(
                                label,
                                color = if (selected) OllamaGreen else OllamaTextDim,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.8.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                            if (selected) {
                                Box(Modifier.fillMaxWidth().height(2.dp).background(OllamaGreen))
                            }
                        }
                    }
                }
            }
            if (activeTab == TerminalTab.DAEMON) {
                TextButton(
                    onClick = { vm.clearLogs() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Text("Clear", color = OllamaRed, fontSize = 11.sp) }
            }
        }

        HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)

        when (activeTab) {
            TerminalTab.DAEMON    -> DaemonLogsPane(vm)
            TerminalTab.WORKSPACE -> WorkspacePane(vm, context)
        }
    }
}

@Composable
private fun ColumnScope.DaemonLogsPane(vm: MainViewModel) {
    val listState    = rememberLazyListState()
    LaunchedEffect(vm.liveLogs.size) {
        if (vm.liveLogs.isNotEmpty()) listState.animateScrollToItem(vm.liveLogs.size - 1)
    }

    val isLlama   = vm.activeBackend == "llamacpp"
    val isOnline  = if (isLlama) vm.llamaApiOnline else vm.apiOnline
    val backend   = if (isLlama) "llama.cpp" else "ollama"

    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0A1A0A)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
            .background(if (isOnline) OllamaGreen else OllamaRed))
        Text("$backend daemon", color = OllamaGreen, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(if (isOnline) "running" else "offline",
            color = if (isOnline) OllamaGreen.copy(alpha = 0.6f) else OllamaRed.copy(alpha = 0.6f),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        Text("${vm.liveLogs.size} lines", color = OllamaTextDim,
            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }

    Box(Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
        if (vm.liveLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs yet.\nStart the daemon to see output.",
                    color = OllamaTextDim, textAlign = TextAlign.Center,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(vm.liveLogs) { line ->
                        val color = when {
                            line.startsWith("❌") || line.contains("error", ignoreCase = true)  -> Color(0xFFFF6B6B)
                            line.startsWith("✅") || line.contains("success", ignoreCase = true) -> OllamaGreen
                            line.startsWith(">")                                                 -> Color(0xFFFFCC44)
                            line.startsWith("[llama.cpp]")                                       -> OllamaBlue
                            else -> TerminalGreen
                        }
                        Text(line, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.WorkspacePane(vm: MainViewModel, context: Context) {
    val listState    = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val workDir      = vm.agentWorkingDir.ifBlank { context.filesDir.absolutePath }

    LaunchedEffect(vm.liveLogs.size) {
        if (vm.liveLogs.isNotEmpty()) listState.animateScrollToItem(vm.liveLogs.size - 1)
    }

    // Working dir header
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0A0A1A)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("📁", fontSize = 12.sp)
        Text(workDir, color = OllamaGreen, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
    }

    // Log output
    Box(Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
        if (vm.liveLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Run a command below\nin the workspace directory.",
                    color = OllamaTextDim, textAlign = TextAlign.Center,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(vm.liveLogs) { line ->
                        val color = when {
                            line.startsWith(">") || line.startsWith("$ ")                       -> Color(0xFFFFCC44)
                            line.startsWith("❌") || line.contains("error", ignoreCase = true)  -> Color(0xFFFF6B6B)
                            line.startsWith("✅")                                               -> OllamaGreen
                            else -> TerminalGreen
                        }
                        Text(line, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    // Shell input — imePadding keeps it above the soft keyboard
    Row(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .background(OllamaSurface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("$", color = OllamaGreen, fontFamily = FontFamily.Monospace,
            fontSize = 13.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = vm.terminalInput,
            onValueChange = { vm.terminalInput = it },
            placeholder = {
                Text("bash command in workspace...", color = OllamaTextDim,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            },
            modifier = Modifier.weight(1f).testTag("terminal_input"),
            singleLine = true,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TerminalGreen),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor      = OllamaGreen,
                unfocusedBorderColor    = OllamaBorder,
                cursorColor             = OllamaGreen,
                focusedContainerColor   = Color(0xFF0A1A0A),
                unfocusedContainerColor = Color(0xFF0A1A0A)
            ),
            shape = RoundedCornerShape(6.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                focusManager.clearFocus(); vm.runTerminalCommand(context)
            })
        )
        IconButton(
            onClick = { focusManager.clearFocus(); vm.runTerminalCommand(context) },
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                .background(OllamaGreen).testTag("terminal_exec_btn")
        ) { Icon(Icons.Default.Send, null, tint = OllamaBg, modifier = Modifier.size(18.dp)) }
    }
}
