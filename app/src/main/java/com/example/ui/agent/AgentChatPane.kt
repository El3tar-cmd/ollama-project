package com.example.ui.agent

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.data.model.AgentStep
import com.example.ui.components.OllamaTextField
import com.example.ui.theme.*

// ─── Plan step card ──────────────────────────────────────────────────────────
@Composable
private fun PlanStepCard(content: String) {
    val lines = content.trim().lines()
    // Parse numbered steps: "1. ...", "2. ...", etc.
    val steps = lines.filter { it.matches(Regex("^\\d+\\..*")) }
    val otherText = lines.filter { !it.matches(Regex("^\\d+\\..*")) }
        .joinToString("\n").trim()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A1A12))
            .border(BorderStroke(1.dp, OllamaGreen.copy(alpha = 0.35f)), RoundedCornerShape(12.dp))
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF0D2018))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📋", fontSize = 14.sp)
            Text("TASK PLAN", color = OllamaGreen, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.weight(1f))
            Text("${steps.size} steps", color = OllamaGreen.copy(alpha = 0.5f),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        HorizontalDivider(color = OllamaGreen.copy(alpha = 0.2f), thickness = 0.5.dp)

        // Intro text if any
        if (otherText.isNotBlank()) {
            Text(otherText, color = OllamaTextDim, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                lineHeight = 16.sp)
        }

        // Plan steps
        if (steps.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEach { step ->
                    val num = step.substringBefore(".").trim()
                    val text = step.substringAfter(".").trim()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier.size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(OllamaGreen.copy(alpha = 0.15f))
                                .border(1.dp, OllamaGreen.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(num, color = OllamaGreen, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Text(text, color = Color(0xFFCCE8D5), fontSize = 12.sp,
                            lineHeight = 17.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            // Fallback: show as plain text
            Text(content.trim(), color = Color(0xFFCCE8D5), fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

// ─── Single step bubble ──────────────────────────────────────────────────────
@Composable
fun AgentStepBubble(step: AgentStep) {
    // Plan step → special card
    if (step.type == "plan") { PlanStepCard(step.content); return }

    val rawContent = if (step.type == "assistant") {
        step.content
            .replace(Regex("```tool[\\s\\S]*?```"), "")
            .replace(Regex("```json[\\s\\S]*?```"), "")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .trim()
    } else step.content

    if (rawContent.isBlank() && step.type == "assistant") return

    val isThink = step.type in listOf("think", "sequential_thinking")
    val isTool  = step.type == "tool_result"
    val THRESHOLD = 220

    val startCollapsed = (isTool && rawContent.length > THRESHOLD) || (isThink && rawContent.length > 500)
    var expanded by remember { mutableStateOf(!startCollapsed) }

    if (isThink) {
        val cleaned = rawContent.lines().joinToString("\n") { line ->
            line.trimStart().removePrefix(">").trimStart()
        }
        val lines       = cleaned.lines()
        val lineCount   = lines.size
        val canCollapse = lineCount > 4
        val shown = if (!expanded && canCollapse)
            lines.take(3).joinToString("\n") + "\n…"
        else cleaned

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0D0D20))
                .border(BorderStroke(1.dp, OllamaPurple.copy(alpha = 0.25f)), RoundedCornerShape(10.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.width(3.dp).height(13.dp).background(OllamaPurple, RoundedCornerShape(2.dp)))
                    Text("THINKING", color = OllamaPurple, fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
                Text("$lineCount lines", color = OllamaPurple.copy(alpha = 0.4f),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider(color = OllamaPurple.copy(alpha = 0.18f), thickness = 0.5.dp)
            Text(
                text = shown, color = Color(0xFFBFAEE0),
                fontSize = 11.5.sp, fontStyle = FontStyle.Italic, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
            if (canCollapse) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }
                        .background(OllamaPurple.copy(alpha = 0.07f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (expanded) "▲ collapse" else "▼ show all  ($lineCount lines)",
                        color = OllamaPurple.copy(alpha = 0.55f), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        return
    }

    val (bg, textColor, icon) = when (step.type) {
        "user"        -> Triple(OllamaGreen,       OllamaBg,      "👤")
        "assistant"   -> Triple(OllamaCard,        OllamaText,    "🤖")
        "tool_call"   -> Triple(Color(0xFF1A2A1A), OllamaGreen,   "🔧")
        "tool_result" -> Triple(Color(0xFF0D1A0D), TerminalGreen, "📤")
        "spawn"       -> Triple(Color(0xFF1A1A2E), OllamaBlue,    "🌱")
        "error"       -> Triple(Color(0xFF2A0D0D), OllamaRed,     "❌")
        else          -> Triple(OllamaCard,        OllamaTextDim, "•")
    }

    val lineCount   = rawContent.lines().size
    val canCollapse = isTool && rawContent.length > THRESHOLD
    val firstLine   = rawContent.lines().firstOrNull()?.take(130) ?: ""
    val shownText   = if (!expanded && canCollapse) firstLine else rawContent

    val isUser = step.type == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(bg)
        ) {
            Text(
                text = if (step.type != "user") "$icon $shownText" else shownText,
                color = textColor, fontSize = 12.sp,
                fontFamily = if (isTool) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
            if (canCollapse) {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }
                        .background(Color.Black.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (expanded) "▲ collapse" else "▼ show all  ($lineCount lines)",
                        color = textColor.copy(alpha = 0.65f), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (!expanded) Text("${rawContent.length} chars",
                        color = textColor.copy(alpha = 0.45f), fontSize = 9.sp)
                }
            }
        }
    }
}

// ─── Agent chat pane ─────────────────────────────────────────────────────────
@Composable
fun AgentChatPane(
    vm: MainViewModel,
    context: Context,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusManager: FocusManager
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            if (vm.agentSteps.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("🤖", fontSize = 36.sp)
                            Text(
                                "Describe a task for the agent.\nIt can read, write, edit files and run commands.",
                                color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 13.sp
                            )
                        }
                    }
                }
            }
            items(vm.agentSteps) { step -> AgentStepBubble(step) }
        }

        Row(
            Modifier.fillMaxWidth().background(OllamaSurface).padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OllamaTextField(
                vm.agentInput, { vm.agentInput = it }, "Give the agent a task...",
                Modifier.weight(1f), maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    focusManager.clearFocus(); vm.runAgent(context)
                })
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
                        Icon(Icons.Default.Send, contentDescription = "Send",
                            modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = { vm.agentSteps.clear() },
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                        .background(OllamaCard)
                ) {
                    Icon(Icons.Default.Delete, null, tint = OllamaTextDim,
                        modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
