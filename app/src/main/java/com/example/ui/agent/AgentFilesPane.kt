package com.example.ui.agent

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.ui.chat.MarkdownViewer
import com.example.ui.editor.EnhancedCodeEditor
import com.example.ui.editor.getFileIcon
import com.example.ui.editor.getLanguageFromExtension
import com.example.ui.terminal.formatFileSize
import com.example.ui.theme.*
import java.io.File
@Composable
fun AgentFilesPane(vm: MainViewModel, context: Context) {
    if (vm.openFiles.isNotEmpty()) {
        var showFileSidebar by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(OllamaSurface),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { showFileSidebar = !showFileSidebar },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Menu, "Toggle file tree",
                        tint = if (showFileSidebar) OllamaGreen else OllamaTextDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
                LazyRow(
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                    itemsIndexed(vm.openFiles) { index, file ->
                        val isActive = index == vm.activeTabIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isActive) OllamaCard else Color.Transparent)
                                .border(
                                    width = if (isActive) 1.dp else 0.dp,
                                    color = if (isActive) OllamaGreen.copy(alpha = 0.4f) else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable { vm.switchToTab(index) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(getFileIcon(file), fontSize = 12.sp)
                            Text(
                                file.name,
                                color = if (isActive) OllamaGreen else OllamaTextDim,
                                fontSize = 11.sp, maxLines = 1,
                                modifier = Modifier.widthIn(max = 100.dp),
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = { vm.closeTab(index) }, modifier = Modifier.size(16.dp)) {
                                Icon(Icons.Default.Close, "Close tab", tint = OllamaTextDim, modifier = Modifier.size(11.dp))
                            }
                        }
                    }
            }
            HorizontalDivider(color = OllamaBorder, thickness = 1.dp)
            Row(Modifier.fillMaxSize()) {
                if (showFileSidebar) {
                    Column(
                        Modifier.width(160.dp).fillMaxHeight().background(Color(0xFF0F0F0F))
                    ) {
                            Modifier.fillMaxWidth().background(OllamaSurface).padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                            Text("📁", fontSize = 11.sp)
                                File(vm.agentWorkingDir).name,
                                color = OllamaTextDim, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)
                        LazyColumn(Modifier.fillMaxSize().padding(4.dp)) {
                            items(vm.agentFileTree) { f ->
                                val isOpenInTab = vm.openFiles.contains(f)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isOpenInTab) OllamaGreen.copy(alpha = 0.1f) else Color.Transparent)
                                        .clickable { vm.openInNewTab(f) }
                                        .padding(horizontal = 6.dp, vertical = 5.dp)
                                ) {
                                    Text(getFileIcon(f), fontSize = 11.sp)
                                    Text(
                                        f.name,
                                        color = if (isOpenInTab) OllamaGreen else OllamaTextDim,
                                        fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                    VerticalDivider(color = OllamaBorder, thickness = 1.dp)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (vm.agentSelectedFile != null) {
                        val file = vm.agentSelectedFile!!
                        val language = getLanguageFromExtension(file.name)
                        val isMarkdown = file.name.endsWith(".md", ignoreCase = true) ||
                                file.name.endsWith(".markdown", ignoreCase = true)
                        var isPreview by remember { mutableStateOf(isMarkdown) }
                        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(getFileIcon(file), fontSize = 16.sp)
                                        file.name, color = OllamaGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                                    Text("[$language]", color = OllamaTextDim, fontSize = 10.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (isMarkdown) {
                                        TextButton(
                                            onClick = { isPreview = !isPreview },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(if (isPreview) "✏️ Edit" else "👁 Preview", color = OllamaBlue, fontSize = 11.sp)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { vm.saveCurrentFile(); Toast.makeText(context, "Saved ✓", Toast.LENGTH_SHORT).show() },
                                        border = androidx.compose.foundation.BorderStroke(1.dp, OllamaGreen),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("Save", color = OllamaGreen, fontSize = 12.sp) }
                                    TextButton(onClick = { vm.closeTab(vm.activeTabIndex) }) {
                                        Text("Close", color = OllamaTextDim, fontSize = 12.sp)
                            if (isPreview && isMarkdown) {
                                Box(
                                    Modifier.fillMaxWidth().weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(OllamaCard)
                                        .border(1.dp, OllamaBorder, RoundedCornerShape(8.dp))
                                    MarkdownViewer(vm.agentFileContent, Modifier.fillMaxSize())
                            } else {
                                        .background(TerminalBg)
                                    EnhancedCodeEditor(
                                        code = vm.agentFileContent,
                                        onCodeChange = { vm.agentFileContent = it },
                                        language = language,
                                        modifier = Modifier.fillMaxSize()
        }
    } else if (vm.agentSelectedFile != null) {
        val isMarkdown = vm.agentSelectedFile!!.name.endsWith(".md", ignoreCase = true) ||
                vm.agentSelectedFile!!.name.endsWith(".markdown", ignoreCase = true)
        var isPreview by remember { mutableStateOf(isMarkdown) }
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${if (isMarkdown) "📝" else "✏️"} ${vm.agentSelectedFile!!.name}",
                    color = if (isMarkdown) OllamaBlue else OllamaGreen,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isMarkdown) {
                        TextButton(
                            onClick = { isPreview = !isPreview },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            Text(if (isPreview) "✏️ Edit" else "👁 Preview", color = OllamaBlue, fontSize = 11.sp)
                    OutlinedButton(
                        onClick = { vm.saveCurrentFile(); Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() },
                        border = androidx.compose.foundation.BorderStroke(1.dp, OllamaGreen),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("Save", color = OllamaGreen, fontSize = 12.sp) }
                    TextButton(onClick = { vm.agentSelectedFile = null }) {
                        Text("Close", color = OllamaTextDim, fontSize = 12.sp)
            if (isPreview && isMarkdown) {
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(OllamaCard)
                        .border(1.dp, OllamaBorder, RoundedCornerShape(8.dp))
                    MarkdownViewer(vm.agentFileContent, Modifier.fillMaxSize())
            } else {
                        .background(TerminalBg)
                    OutlinedTextField(
                        value = vm.agentFileContent,
                        onValueChange = { vm.agentFileContent = it },
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = OllamaText
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            cursorColor = OllamaGreen, focusedTextColor = OllamaText, unfocusedTextColor = OllamaText
                        )
    } else {
        var deleteTarget by remember { mutableStateOf<File?>(null) }
        var renameTarget by remember { mutableStateOf<File?>(null) }
        var newNameInput by remember { mutableStateOf("") }
        deleteTarget?.let { tgt ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                containerColor   = OllamaCard,
                title = { Text("Delete ${if (tgt.isDirectory) "folder" else "file"}?", color = OllamaText, fontWeight = FontWeight.Bold) },
                text  = { Text("\"${tgt.name}\" will be permanently deleted.", color = OllamaTextDim, fontSize = 13.sp) },
                confirmButton = {
                    Button(onClick = { vm.deleteFile(tgt); deleteTarget = null }, colors = ButtonDefaults.buttonColors(containerColor = OllamaRed)) {
                        Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = OllamaTextDim) } }
            )
        renameTarget?.let { tgt ->
            LaunchedEffect(tgt) { newNameInput = tgt.name }
                onDismissRequest = { renameTarget = null },
                title = { Text("Rename", color = OllamaText, fontWeight = FontWeight.Bold) },
                text  = {
                        value = newNameInput, onValueChange = { newNameInput = it },
                        label = { Text("New name", color = OllamaTextDim) }, singleLine = true,
                            focusedBorderColor = OllamaGreen, unfocusedBorderColor = OllamaBorder,
                            focusedLabelColor = OllamaGreen, cursorColor = OllamaGreen,
                            focusedTextColor = OllamaText, unfocusedTextColor = OllamaText
                    Button(
                        onClick  = { vm.renameFile(tgt, newNameInput); renameTarget = null },
                        enabled  = newNameInput.trim().isNotBlank() && newNameInput.trim() != tgt.name,
                        colors   = ButtonDefaults.buttonColors(containerColor = OllamaGreen)
                    ) { Text("Rename", color = OllamaBg, fontWeight = FontWeight.Bold) }
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = OllamaTextDim) } }
        var newFileInput        by remember { mutableStateOf("") }
        var showNewFileDialog   by remember { mutableStateOf(false) }
        var showNewFolderDialog by remember { mutableStateOf(false) }
        if (showNewFileDialog) {
            LaunchedEffect(Unit) { newFileInput = "" }
                onDismissRequest = { showNewFileDialog = false },
                containerColor = OllamaCard,
                title = { Text("New File", color = OllamaText, fontWeight = FontWeight.Bold) },
                text = {
                        value = newFileInput, onValueChange = { newFileInput = it },
                        label = { Text("Filename (e.g. main.py)", color = OllamaTextDim) }, singleLine = true,
                        onClick = {
                            val name = newFileInput.trim()
                            if (name.isNotBlank()) {
                                val f = File(vm.agentWorkingDir, name)
                                try { f.createNewFile(); vm.refreshFileTree(); vm.openInNewTab(f) } catch (_: Exception) {}
                            showNewFileDialog = false
                        },
                        enabled = newFileInput.trim().isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen)
                    ) { Text("Create", color = OllamaBg, fontWeight = FontWeight.Bold) }
                dismissButton = { TextButton(onClick = { showNewFileDialog = false }) { Text("Cancel", color = OllamaTextDim) } }
        if (showNewFolderDialog) {
                onDismissRequest = { showNewFolderDialog = false },
                title = { Text("New Folder", color = OllamaText, fontWeight = FontWeight.Bold) },
                        label = { Text("Folder name", color = OllamaTextDim) }, singleLine = true,
                                val d = File(vm.agentWorkingDir, name)
                                try { d.mkdirs(); vm.refreshFileTree() } catch (_: Exception) {}
                            showNewFolderDialog = false
                dismissButton = { TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel", color = OllamaTextDim) } }
                Modifier.fillMaxWidth().background(OllamaSurface).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                val parent = File(vm.agentWorkingDir).parentFile
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                    if (parent != null) {
                        IconButton(onClick = { vm.updateAgentWorkingDir(parent.absolutePath) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ArrowBack, null, tint = OllamaTextDim, modifier = Modifier.size(16.dp))
                    Text(
                        File(vm.agentWorkingDir).name, color = OllamaText, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showNewFileDialog = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Add, "New File", tint = OllamaGreen, modifier = Modifier.size(18.dp))
                    IconButton(onClick = { showNewFolderDialog = true }, modifier = Modifier.size(30.dp)) {
                        Text("📁+", fontSize = 13.sp)
                    IconButton(onClick = { vm.refreshFileTree() }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Refresh, null, tint = OllamaTextDim, modifier = Modifier.size(16.dp))
            Text(
                vm.agentWorkingDir, color = OllamaTextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(OllamaSurface).padding(horizontal = 12.dp, vertical = 2.dp)
            HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)
            if (vm.agentFileTree.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("📂", fontSize = 36.sp)
                        Text("Empty folder", color = OllamaTextDim, fontSize = 13.sp)
                        TextButton(onClick = { showNewFileDialog = true }) {
                            Text("+ New File", color = OllamaGreen, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(vm.agentFileTree) { file ->
                        val isActive = vm.agentSelectedFile?.absolutePath == file.absolutePath
                            Modifier
                                .fillMaxWidth()
                                .background(if (isActive) OllamaGreen.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable { vm.openInNewTab(file) }
                                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                            Text(getFileIcon(file), fontSize = 15.sp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    color = when {
                                        isActive         -> OllamaGreen
                                        file.isDirectory -> OllamaBlue
                                        else             -> OllamaText
                                    },
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                if (file.isFile) Text(formatFileSize(file.length()), color = OllamaTextDim, fontSize = 10.sp)
                            IconButton(onClick = { renameTarget = file }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Edit, "Rename", tint = OllamaTextDim, modifier = Modifier.size(14.dp))
                            IconButton(onClick = { deleteTarget = file }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, "Delete", tint = OllamaRed.copy(alpha = 0.65f), modifier = Modifier.size(14.dp))
                            if (file.isDirectory) Icon(Icons.Default.ArrowForward, null, tint = OllamaTextDim, modifier = Modifier.size(12.dp))
    }
}
