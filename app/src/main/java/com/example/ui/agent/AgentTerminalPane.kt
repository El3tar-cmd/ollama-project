package com.example.ui.agent

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.data.model.ShellLineType
import com.example.ui.theme.*

@Composable
fun AgentTerminalPane(vm: MainViewModel, context: Context) {
    val listState      = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(vm.shellLines.size) {
        if (vm.shellLines.isNotEmpty())
            listState.animateScrollToItem(vm.shellLines.size - 1)
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(Modifier.fillMaxSize().background(TerminalBg)) {

        // ── Path bar ─────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF0D1117))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("❯", color = OllamaGreen, fontFamily = FontFamily.Monospace,
                fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                vm.shellCwd,
                color = Color(0xFF79B8FF), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.shellLines.clear() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, "Clear", tint = OllamaTextDim,
                    modifier = Modifier.size(15.dp))
            }
        }

        HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)

        // ── Output ───────────────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.shellLines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("DevHive Terminal", color = OllamaGreen, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(vm.shellCwd, color = OllamaTextDim, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text("اكتب أي أمر bash أدناه ↓",
                            color = OllamaTextDim.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        items(vm.shellLines) { sl ->
                            val color = when (sl.type) {
                                ShellLineType.COMMAND -> Color(0xFFFFCC44)
                                ShellLineType.ERROR   -> Color(0xFFFF6B6B)
                                ShellLineType.INFO    -> Color(0xFF79B8FF)
                                ShellLineType.OUTPUT  -> TerminalGreen
                            }
                            Text(sl.text, color = color, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)

        // ── Input row ────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF0D1117))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // History ↑
            IconButton(onClick = { vm.shellHistoryUp() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, null, tint = OllamaTextDim,
                    modifier = Modifier.size(18.dp))
            }

            // Input field with prompt
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF161B22))
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("❯ ", color = OllamaGreen, fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = vm.shellInput,
                    onValueChange = { vm.shellInput = it; vm.shellHistIdx = -1 },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = TerminalGreen),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = Color.Transparent,
                        unfocusedBorderColor    = Color.Transparent,
                        cursorColor             = OllamaGreen,
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    placeholder = {
                        Text("bash command...", color = OllamaTextDim.copy(alpha = 0.35f),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.runShellCommand() })
                )
            }

            // History ↓
            IconButton(onClick = { vm.shellHistoryDown() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, null, tint = OllamaTextDim,
                    modifier = Modifier.size(18.dp))
            }

            // Run
            IconButton(
                onClick = { vm.runShellCommand() },
                modifier = Modifier.size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OllamaGreen)
            ) {
                Icon(Icons.Default.Send, null, tint = OllamaBg, modifier = Modifier.size(18.dp))
            }
        }
    }
}
