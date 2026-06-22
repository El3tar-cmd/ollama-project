package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun StatusPill(online: Boolean, label: String? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (online) Color(0xFF1A3A2A) else Color(0xFF3A1A1A))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) OllamaGreen else OllamaRed))
        val statusText = if (label != null)
            if (online) "$label ●" else "$label ○"
        else
            if (online) "Online" else "Offline"
        Text(statusText, color = if (online) OllamaGreen else OllamaRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OllamaCard)
            .border(1.dp, OllamaBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(title, color = OllamaGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (subtitle != null) Text(subtitle, color = OllamaTextDim, fontSize = 12.sp)
        }
        content()
    }
}

@Composable
fun OllamaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    tag: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        maxLines = maxLines,
        modifier = modifier.then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = OllamaGreen,
            unfocusedBorderColor = OllamaBorder,
            focusedLabelColor    = OllamaGreen,
            unfocusedLabelColor  = OllamaTextDim,
            cursorColor          = OllamaGreen,
            focusedTextColor     = OllamaText,
            unfocusedTextColor   = OllamaText
        ),
        shape = RoundedCornerShape(8.dp)
    )
}
