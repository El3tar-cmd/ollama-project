package com.example.data.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

data class OllamaModel(val name: String, val size: Long)

data class ChatMessage(val role: String, val content: String)

data class AgentStep(
    val type: String,
    val content: String,
    val isError: Boolean = false
)

enum class ShellLineType { COMMAND, OUTPUT, ERROR, INFO }

data class ShellLine(val text: String, val type: ShellLineType = ShellLineType.OUTPUT)

sealed class ChatSegment {
    data class PlainText(val text: String) : ChatSegment()
    data class CodeBlock(val language: String, val code: String) : ChatSegment()
    data class ThinkBlock(val content: String) : ChatSegment()
}

sealed class MdSegment {
    data class Heading(val level: Int, val text: String) : MdSegment()
    data class Paragraph(val text: String) : MdSegment()
    data class BulletItem(val indent: Int, val text: String) : MdSegment()
    data class NumberedItem(val num: Int, val text: String) : MdSegment()
    data class MdCodeBlock(val lang: String, val code: String) : MdSegment()
    data class Quote(val text: String) : MdSegment()
    object Rule : MdSegment()
    object Blank : MdSegment()
}

enum class AppTab(val label: String, val icon: ImageVector) {
    SERVER("Server",   Icons.Default.Settings),
    MODELS("Models",   Icons.Default.List),
    CHAT("Chat",       Icons.Default.Face),
    AGENT("Agent",     Icons.Default.Star),
    TERMINAL("Logs",   Icons.Default.Info),
    BROWSER("Browser", Icons.Default.Language),
}
