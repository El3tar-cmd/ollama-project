package com.example.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import java.io.File

val SyntaxKeyword  = Color(0xFFFF79C6)
val SyntaxString   = Color(0xFFF1FA8C)
val SyntaxNumber   = Color(0xFFBD93F9)
val SyntaxComment  = Color(0xFF6272A4)
val SyntaxFunction = Color(0xFF50FA7B)
val SyntaxType     = Color(0xFF8BE9FD)
val SyntaxError    = Color(0xFFFF5555)
val SyntaxTag      = Color(0xFFFF79C6)
val SyntaxAttr     = Color(0xFF50FA7B)

fun getFileIcon(file: File): String {
    return if (file.isDirectory) "📂" else {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        when (ext) {
            "kt", "kts"             -> "🟉"
            "java"                  -> "☕"
            "py"                    -> "🐍"
            "js", "mjs"             -> "📜"
            "ts", "tsx"             -> "🔷"
            "json"                  -> "📋"
            "xml"                   -> "📰"
            "html", "htm"           -> "🌐"
            "css"                   -> "🎨"
            "gradle"                -> "🟢"
            "md", "markdown"        -> "📝"
            "txt"                   -> "📄"
            "sh", "bash"            -> "🖥️"
            "yml", "yaml"           -> "⚙️"
            "toml"                  -> "⚙️"
            "gitignore"             -> "🚫"
            "png", "jpg", "jpeg",
            "gif", "webp", "svg"    -> "🖼️"
            "mp3", "wav", "ogg"     -> "🎵"
            "mp4", "webm", "mov"    -> "🎬"
            "zip", "tar", "gz", "rar" -> "📦"
            "pdf"                   -> "📕"
            "doc", "docx"           -> "📘"
            "exe", "apk"            -> "⚡"
            "so", "dll", "dylib"    -> "🔧"
            else                    -> "📄"
        }
    }
}

fun getLanguageFromExtension(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts"     -> "kotlin"
        "java"          -> "java"
        "py"            -> "python"
        "js"            -> "javascript"
        "ts", "tsx"     -> "typescript"
        "json"          -> "json"
        "xml"           -> "xml"
        "html", "htm"   -> "html"
        "css"           -> "css"
        "gradle"        -> "groovy"
        "md"            -> "markdown"
        "sh", "bash"    -> "bash"
        "yml", "yaml"   -> "yaml"
        else            -> "plain"
    }
}

@Composable
fun EnhancedCodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    language: String,
    modifier: Modifier = Modifier
) {
    val lineCount = remember(code) { code.lines().size.coerceAtLeast(1) }

    Box(modifier = modifier.background(TerminalBg).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .width(48.dp)
                    .background(OllamaSurface)
                    .padding(top = 10.dp, bottom = 10.dp, start = 4.dp, end = 4.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.End
            ) {
                repeat(lineCount) { i ->
                    Text(
                        (i + 1).toString(),
                        color = OllamaTextDim,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }

            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(OllamaBorder)
            )

            BasicTextField(
                value = code,
                onValueChange = onCodeChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = OllamaText,
                    lineHeight = 20.sp
                ),
                cursorBrush = Brush.verticalGradient(listOf(OllamaGreen, OllamaGreen)),
                decorationBox = { innerTextField ->
                    androidx.compose.foundation.layout.Box {
                        if (code.isEmpty()) {
                            Text(
                                "Start typing...",
                                color = OllamaTextDim.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}
