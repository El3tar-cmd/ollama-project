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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    bytes < 1024             -> "${bytes}B"
    bytes < 1024 * 1024      -> "${bytes / 1024}KB"
    else                     -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
}

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

        Box(Modifier.fillMaxWidth().weight(1f).background(TerminalBg)) {
            if (vm.liveLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No logs yet.\nStart the daemon to see output.",
                        color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        items(vm.liveLogs) { line ->
                            Text(line, color = TerminalGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

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
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TerminalGreen),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = OllamaGreen,
                    unfocusedBorderColor = OllamaBorder,
                    cursorColor          = OllamaGreen
                ),
                shape = RoundedCornerShape(6.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { focusManager.clearFocus(); vm.runTerminalCommand(context) })
            )
            IconButton(
                onClick = { focusManager.clearFocus(); vm.runTerminalCommand(context) },
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(OllamaGreen).testTag("terminal_exec_btn")
            ) { Icon(Icons.Default.Send, null, tint = OllamaBg, modifier = Modifier.size(18.dp)) }
        }
    }
}
