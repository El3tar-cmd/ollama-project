package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

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

fun inlineMd(raw: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < raw.length) {
        when {
            (raw.startsWith("**", i) || raw.startsWith("__", i)) -> {
                val marker = raw.substring(i, i + 2)
                val end = raw.indexOf(marker, i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(raw.substring(i + 2, end))
                    pop(); i = end + 2
                } else { append(raw[i]); i++ }
            }
            (raw.startsWith("*", i) && !raw.startsWith("**", i)) ||
            (raw.startsWith("_", i) && !raw.startsWith("__", i)) -> {
                val marker = raw[i].toString()
                val end = raw.indexOf(marker, i + 1)
                if (end != -1 && end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(raw.substring(i + 1, end))
                    pop(); i = end + 1
                } else { append(raw[i]); i++ }
            }
            raw.startsWith("`", i) -> {
                val end = raw.indexOf("`", i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF1A1A2E), color = Color(0xFF79C0FF)))
                    append(raw.substring(i + 1, end))
                    pop(); i = end + 1
                } else { append(raw[i]); i++ }
            }
            raw.startsWith("~~", i) -> {
                val end = raw.indexOf("~~", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color(0xFF888888)))
                    append(raw.substring(i + 2, end))
                    pop(); i = end + 2
                } else { append(raw[i]); i++ }
            }
            else -> { append(raw[i]); i++ }
        }
    }
}

fun parseMd(md: String): List<MdSegment> {
    val segs  = mutableListOf<MdSegment>()
    val lines = md.lines()
    var i     = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        when {
            line.startsWith("```") || line.startsWith("~~~") -> {
                val fence = if (line.startsWith("```")) "```" else "~~~"
                val lang  = line.drop(3).trim()
                val code  = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence.take(3))) {
                    code.appendLine(lines[i]); i++
                }
                segs.add(MdSegment.MdCodeBlock(lang, code.toString().trimEnd()))
            }
            line.matches(Regex("^#{1,6}( .*|$)")) -> {
                val lvl = line.takeWhile { it == '#' }.length
                segs.add(MdSegment.Heading(lvl, line.drop(lvl).trimStart()))
            }
            line.matches(Regex("^\\s*[-*+] .*")) -> {
                val indent = line.length - line.trimStart().length
                segs.add(MdSegment.BulletItem(indent / 2, line.trimStart().drop(2)))
            }
            line.matches(Regex("^\\s*\\d+[.)].? .*")) -> {
                val num = line.trimStart().takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                segs.add(MdSegment.NumberedItem(num, line.trimStart().dropWhile { it.isDigit() || it == '.' || it == ')' }.trimStart()))
            }
            line.startsWith(">") && line.length > 1 -> segs.add(MdSegment.Quote(line.drop(1).trimStart()))
            line.matches(Regex("^[-*_]{3,}\\s*$")) -> segs.add(MdSegment.Rule)
            line.isBlank() -> segs.add(MdSegment.Blank)
            else -> segs.add(MdSegment.Paragraph(line))
        }
        i++
    }
    return segs
}

@Composable
fun MarkdownViewer(markdown: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val segments  = remember(markdown) { parseMd(markdown) }
    val scrollState = rememberScrollState()
    Column(
        modifier.verticalScroll(scrollState).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        segments.forEach { seg ->
            when (seg) {
                is MdSegment.Heading -> {
                    if (seg.level == 1) Spacer(Modifier.height(8.dp))
                    Text(
                        seg.text, color = OllamaText,
                        fontSize = when (seg.level) { 1 -> 22.sp; 2 -> 18.sp; 3 -> 15.sp; else -> 13.sp },
                        fontWeight = if (seg.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
                        lineHeight = when (seg.level) { 1 -> 28.sp; 2 -> 24.sp; else -> 20.sp }
                    )
                    if (seg.level == 1) HorizontalDivider(color = OllamaBorder.copy(alpha = 0.5f), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                }
                is MdSegment.Paragraph -> Text(inlineMd(seg.text), color = OllamaText, fontSize = 13.sp, lineHeight = 20.sp)
                is MdSegment.BulletItem -> Row(
                    Modifier.padding(start = (seg.indent * 14).dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.Top
                ) {
                    Text("•", color = OllamaGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(inlineMd(seg.text), color = OllamaText, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                }
                is MdSegment.NumberedItem -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.Top
                ) {
                    Text("${seg.num}.", color = OllamaGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(24.dp))
                    Text(inlineMd(seg.text), color = OllamaText, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                }
                is MdSegment.MdCodeBlock -> {
                    val code = seg.code
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0D1117))
                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(seg.lang.ifBlank { "code" }, color = OllamaGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(code)) },
                                contentPadding = PaddingValues(0.dp), modifier = Modifier.height(18.dp)
                            ) { Text("copy", color = OllamaGreen, fontSize = 9.sp) }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(code, color = Color(0xFF79C0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(2.dp))
                }
                is MdSegment.Quote -> Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF0A1A14))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.width(3.dp).height(18.dp).background(OllamaGreen).clip(RoundedCornerShape(2.dp)))
                    Text(seg.text, color = OllamaTextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                MdSegment.Rule  -> HorizontalDivider(color = OllamaBorder, modifier = Modifier.padding(vertical = 6.dp))
                MdSegment.Blank -> Spacer(Modifier.height(4.dp))
            }
        }
    }
}
