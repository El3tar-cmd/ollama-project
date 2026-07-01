package com.example.ui.terminal

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.AccountBox
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

@Composable
fun TerminalScreen(vm: MainViewModel, context: Context) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(vm.liveLogs.size) {
        if (vm.liveLogs.isNotEmpty()) listState.animateScrollToItem(vm.liveLogs.size - 1)
    }

    Column(Modifier.fillMaxSize().background(TerminalBg)) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(OllamaSurface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.AccountBox, null, tint = OllamaGreen, modifier = Modifier.size(16.dp))
            Text("Ubuntu Linux (PRoot)", color = OllamaGreen, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(vm.linuxCwd, color = OllamaTextDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1)
            TextButton(onClick = { vm.clearLogs() }) {
                Text("Clear", color = OllamaRed, fontSize = 10.sp)
            }
        }

        // ── Persistent server status banner ───────────────────────────────────
        if (vm.linuxSession.isRunning) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D2A1A))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(OllamaGreen)
                )
                Column(Modifier.weight(1f)) {
                    Text("Server Running", color = OllamaGreen, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    vm.linuxSession.serverPort?.let { port ->
                        Text("localhost:$port — open Browser tab to view",
                            color = OllamaTextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                TextButton(
                    onClick = { vm.stopPersistentServer() },
                    colors = ButtonDefaults.textButtonColors(contentColor = OllamaRed)
                ) {
                    Text("⏹ Stop", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)

        // Terminal output
        Box(Modifier.weight(1f).fillMaxWidth().background(TerminalBg)) {
            if (vm.liveLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Welcome to Ubuntu Linux Terminal!\n\nType commands below to run in Ubuntu Linux.\nExample: ls, pwd, apt install, cat file.txt",
                        color = OllamaTextDim, textAlign = TextAlign.Center,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(vm.liveLogs) { line ->
                        val displayLine = if (line.length > 1200) line.take(1200) + " ..." else line
                        val color = when {
                            line.startsWith("❌") || line.contains("error", ignoreCase = true) -> Color(0xFFFF6B6B)
                            line.startsWith("✅") || line.contains("success", ignoreCase = true) -> OllamaGreen
                            line.startsWith(">") -> Color(0xFFFFCC44)
                            line.startsWith("$") -> Color(0xFF44CCFF)
                            else -> TerminalGreen
                        }
                        Text(displayLine, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Input
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
                    Text("linux command...", color = OllamaTextDim,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                },
                modifier = Modifier.weight(1f).testTag("terminal_input"),
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TerminalGreen),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OllamaGreen,
                    unfocusedBorderColor = OllamaBorder,
                    cursorColor = OllamaGreen,
                    focusedContainerColor = Color(0xFF0A1A0A),
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
}
