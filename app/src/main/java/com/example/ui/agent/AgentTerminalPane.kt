package com.example.ui.agent

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.terminal.RuntimeManager
import com.example.ui.theme.*

private data class RuntimeOpt(val id: String, val label: String, val icon: String, val color: Color)

private val RUNTIMES = listOf(
    RuntimeOpt("BASH",   "BASH",    "🖥",  Color(0xFF4CAF50)),
    RuntimeOpt("PYTHON", "PYTHON",  "🐍",  Color(0xFFFFCC44)),
    RuntimeOpt("NODE",   "NODE.JS", "⬡",  Color(0xFF68D391)),
)

@Composable
fun AgentTerminalPane(vm: MainViewModel, context: Context) {
    val listState      = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var dropdownOpen   by remember { mutableStateOf(false) }

    val current = RUNTIMES.find { it.id == vm.shellRuntime } ?: RUNTIMES[0]

    val promptSymbol = when (vm.shellRuntime) { "PYTHON" -> ">>>";  "NODE" -> ">"; else -> "❯" }
    val promptColor  = current.color
    val placeholder  = when (vm.shellRuntime) {
        "PYTHON" -> "python code  e.g. print('hi')"
        "NODE"   -> "js code  e.g. console.log('hi')"
        else     -> "bash command..."
    }

    LaunchedEffect(vm.shellLines.size) {
        if (vm.shellLines.isNotEmpty()) listState.animateScrollToItem(vm.shellLines.size - 1)
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(Modifier.fillMaxSize().background(TerminalBg)) {

        // ── Header bar ───────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1117))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Runtime dropdown chip
            Box {
                val interSrc = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1C2128))
                        .border(0.5.dp, current.color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable(interSrc, indication = null) { dropdownOpen = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(current.icon, fontSize = 12.sp)
                    Text(
                        current.label,
                        color = current.color,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Icon(Icons.Default.ArrowDropDown, null,
                        tint = current.color, modifier = Modifier.size(14.dp))
                }

                DropdownMenu(
                    expanded = dropdownOpen,
                    onDismissRequest = { dropdownOpen = false },
                    modifier = Modifier.background(Color(0xFF1C2128))
                ) {
                    RUNTIMES.forEach { opt ->
                        val isSel = opt.id == vm.shellRuntime
                        val src = when (opt.id) {
                            "PYTHON" -> RuntimeManager.pythonSource(context)
                            "NODE"   -> RuntimeManager.nodeSource(context)
                            else     -> "built-in"
                        }
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Text(opt.icon, fontSize = 14.sp)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            opt.label,
                                            color = if (isSel) opt.color else OllamaText,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            src,
                                            color = if (src == "not installed") OllamaRed.copy(alpha = 0.8f)
                                                    else OllamaTextDim,
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    if (isSel) Icon(Icons.Default.Check, null,
                                        tint = opt.color, modifier = Modifier.size(14.dp))
                                }
                            },
                            onClick = { vm.shellRuntime = opt.id; dropdownOpen = false }
                        )
                    }
                }
            }

            // Current path
            Text(
                vm.shellCwd,
                color = Color(0xFF79B8FF), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )

            // Clear
            IconButton(onClick = { vm.shellLines.clear() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, "Clear", tint = OllamaTextDim, modifier = Modifier.size(15.dp))
            }
        }

        HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)

        // ── Output area ──────────────────────────────────────────────────────
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.shellLines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "${current.icon}  DevHive ${current.label} Terminal",
                            color = current.color, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                        )
                        Text(
                            vm.shellCwd, color = OllamaTextDim, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        when (vm.shellRuntime) {
                            "PYTHON" -> {
                                val src = RuntimeManager.pythonSource(context)
                                if (src == "not installed") {
                                    Text("❌ Python غير موجود", color = OllamaRed, fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace)
                                    Text("Install Termux ← pkg install python",
                                        color = OllamaTextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                } else {
                                    Text("✅ $src  ·  اكتب كود Python أدناه ↓",
                                        color = OllamaTextDim.copy(alpha = 0.7f), fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                            "NODE" -> {
                                val src = RuntimeManager.nodeSource(context)
                                if (src == "not installed") {
                                    Text("❌ Node.js غير موجود", color = OllamaRed, fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace)
                                    Text("Install Termux ← pkg install nodejs",
                                        color = OllamaTextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                } else {
                                    Text("✅ $src  ·  اكتب JavaScript أدناه ↓",
                                        color = OllamaTextDim.copy(alpha = 0.7f), fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            }
                            else -> Text("اكتب أي أمر bash أدناه ↓",
                                color = OllamaTextDim.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
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
                                ShellLineType.COMMAND -> promptColor
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
            Modifier
                .fillMaxWidth()
                .imePadding()
                .background(Color(0xFF0D1117))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = { vm.shellHistoryUp() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, null, tint = OllamaTextDim, modifier = Modifier.size(18.dp))
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF161B22))
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$promptSymbol ",
                    color = promptColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = vm.shellInput,
                    onValueChange = { vm.shellInput = it; vm.shellHistIdx = -1 },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TerminalGreen),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = Color.Transparent,
                        unfocusedBorderColor    = Color.Transparent,
                        cursorColor             = promptColor,
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    placeholder = {
                        Text(placeholder, color = OllamaTextDim.copy(alpha = 0.35f),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.runShellCommand() })
                )
            }

            IconButton(onClick = { vm.shellHistoryDown() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, null, tint = OllamaTextDim, modifier = Modifier.size(18.dp))
            }

            IconButton(
                onClick = { vm.runShellCommand() },
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(promptColor)
            ) {
                Icon(Icons.Default.Send, null, tint = OllamaBg, modifier = Modifier.size(18.dp))
            }
        }
    }
}
