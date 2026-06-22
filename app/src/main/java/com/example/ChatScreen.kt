package com.example

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

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

        val segments    = remember(msg.content) { parseChatContent(msg.content) }
        val bubbleShape = RoundedCornerShape(
            topStart    = 16.dp, topEnd   = 16.dp,
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
                            SelectionContainer {
                                Text(segment.text, color = if (isUser) OllamaBg else OllamaText, fontSize = 14.sp)
                            }
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(segment.language.ifBlank { "code" }, color = OllamaGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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

@Composable
fun ChatScreen(vm: MainViewModel, context: Context) {
    val listState    = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(vm.chatHistory.size) { if (vm.chatHistory.isNotEmpty()) listState.animateScrollToItem(vm.chatHistory.size - 1) }
    val lastMsgContent = vm.chatHistory.lastOrNull()?.content ?: ""
    LaunchedEffect(lastMsgContent) {
        if (vm.isGeneratingResponse && vm.chatHistory.isNotEmpty())
            listState.animateScrollToItem(vm.chatHistory.size - 1)
    }
    LaunchedEffect(vm.selectedModelChat) { if (vm.selectedModelChat.isNotEmpty() && vm.chatHistory.isEmpty()) vm.startChatSession() }

    var modelDropdownExpanded by remember { mutableStateOf(false) }
    val isLlamaBackend = vm.activeBackend == "llamacpp"
    val isOnline = if (isLlamaBackend) vm.llamaApiOnline else vm.apiOnline
    val hasModel = if (isLlamaBackend) vm.llamaSelectedModel != null else vm.selectedModelChat.isNotEmpty()
    val modelName = if (isLlamaBackend) {
        vm.llamaSelectedModel?.name ?: "No model selected"
    } else {
        vm.selectedModelChat.ifEmpty { "No model selected" }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(OllamaSurface).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable {
                        if (isLlamaBackend && vm.llamaAvailableGGUFs.isNotEmpty()) modelDropdownExpanded = true
                        else if (!isLlamaBackend && vm.modelList.isNotEmpty()) modelDropdownExpanded = true
                    }
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (isOnline) OllamaGreen else OllamaRed))
                    Text(modelName, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if ((isLlamaBackend && vm.llamaAvailableGGUFs.isNotEmpty()) || (!isLlamaBackend && vm.modelList.isNotEmpty())) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = OllamaGreen, modifier = Modifier.size(16.dp))
                    }
                }
                DropdownMenu(
                    expanded = modelDropdownExpanded,
                    onDismissRequest = { modelDropdownExpanded = false },
                    containerColor = OllamaCard
                ) {
                    if (isLlamaBackend) {
                        vm.llamaAvailableGGUFs.forEach { file ->
                            DropdownMenuItem(
                                text = { Text(file.name, color = OllamaText, fontSize = 13.sp) },
                                onClick = { vm.llamaSelectedModel = file; modelDropdownExpanded = false },
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
                                onClick = { vm.selectedModelChat = model.name; vm.agentModel = model.name; modelDropdownExpanded = false },
                                colors = MenuDefaults.itemColors(textColor = OllamaText),
                                leadingIcon = if (vm.selectedModelChat == model.name) ({
                                    Icon(Icons.Default.Check, null, tint = OllamaGreen, modifier = Modifier.size(14.dp))
                                }) else null
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { vm.startChatSession() }) { Text("New Chat", color = OllamaGreen, fontSize = 12.sp) }
        }

        if (!hasModel || !isOnline) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🤖", fontSize = 40.sp)
                    val message = if (!isOnline) {
                        if (isLlamaBackend) "Start the llama.cpp server first" else "Start the Ollama daemon first"
                    } else "Select a model from the Models tab"
                    Text(message, color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 13.sp)
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

        Row(
            Modifier.fillMaxWidth().background(OllamaSurface).padding(8.dp),
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
