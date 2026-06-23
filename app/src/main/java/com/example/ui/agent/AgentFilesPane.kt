package com.example.ui.agent

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.MainViewModel
import com.example.ui.chat.MarkdownViewer
import com.example.ui.editor.EnhancedCodeEditor
import com.example.ui.editor.getFileIcon
import com.example.ui.editor.getLanguageFromExtension
import com.example.ui.terminal.formatFileSize
import com.example.ui.theme.*
import java.io.File

// ── Tree data model ───────────────────────────────────────────────────────────
private data class TreeItem(val file: File, val depth: Int)

private fun buildFlatTree(dir: File, expandedDirs: Set<String>, depth: Int = 0): List<TreeItem> {
    val children = dir.listFiles()
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        ?: return emptyList()
    return children.flatMap { file ->
        val item = TreeItem(file, depth)
        if (file.isDirectory && file.absolutePath in expandedDirs) {
            listOf(item) + buildFlatTree(file, expandedDirs, depth + 1)
        } else {
            listOf(item)
        }
    }
}

// ── HTML WebView Preview ──────────────────────────────────────────────────────
@Composable
private fun HtmlPreviewPane(html: String, modifier: Modifier = Modifier) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(html) {
        webViewRef?.loadDataWithBaseURL(
            "file:///android_asset/", html, "text/html", "UTF-8", null
        )
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled  = true
                settings.domStorageEnabled  = true
                settings.allowFileAccess    = true
                webViewClient               = WebViewClient()
                webViewRef                  = this
                loadDataWithBaseURL(
                    "file:///android_asset/", html, "text/html", "UTF-8", null
                )
            }
        },
        modifier = modifier
    )
}

// ── File Sidebar ──────────────────────────────────────────────────────────────
@Composable
private fun FileSidebar(vm: MainViewModel) {
    val expandedDirs = remember { mutableStateOf(setOf<String>()) }
    val rootDir = File(vm.agentWorkingDir.ifBlank { "/" })
    val parentDir = rootDir.parentFile

    // Reading agentFileTree.size causes recomposition when agent adds/deletes files
    val fileCount = vm.agentFileTree.size
    val treeItems = remember(vm.agentWorkingDir, expandedDirs.value, fileCount) {
        buildFlatTree(rootDir, expandedDirs.value)
    }

    Column(
        Modifier.width(160.dp).fillMaxHeight().background(Color(0xFF0F0F0F))
    ) {
        // ── Header: folder name + back button ─────────────────────────────────
        Row(
            Modifier.fillMaxWidth().background(OllamaSurface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Back button — only show when not at a root/top-level directory
            if (parentDir != null && parentDir.canRead()) {
                IconButton(
                    onClick = { vm.updateAgentWorkingDir(parentDir.absolutePath) },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowBack, "Go up",
                        tint = OllamaTextDim,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Spacer(Modifier.size(22.dp))
            }
            Text("📁", fontSize = 11.sp)
            Text(
                rootDir.name.ifBlank { "/" },
                color = OllamaGreen.copy(alpha = 0.9f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)

        if (treeItems.isEmpty() && rootDir.listFiles()?.isEmpty() == true) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Empty\nfolder",
                    color = OllamaTextDim.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(4.dp)) {
                items(treeItems, key = { it.file.absolutePath }) { item ->
                    val file = item.file
                    val depth = item.depth
                    val isDir = file.isDirectory
                    val isExpanded = file.absolutePath in expandedDirs.value
                    val isOpenInTab = vm.openFiles.contains(file)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    isOpenInTab -> OllamaGreen.copy(alpha = 0.12f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable {
                                if (isDir) {
                                    val path = file.absolutePath
                                    expandedDirs.value = if (isExpanded) {
                                        expandedDirs.value - path
                                    } else {
                                        expandedDirs.value + path
                                    }
                                } else {
                                    vm.openInNewTab(file)
                                }
                            }
                            .padding(
                                start = (6 + depth * 10).dp,
                                end = 6.dp,
                                top = 5.dp,
                                bottom = 5.dp
                            )
                    ) {
                        // Expand/collapse chevron for directories
                        if (isDir) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown
                                              else Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = OllamaTextDim.copy(alpha = 0.7f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                        } else {
                            Spacer(Modifier.width(14.dp))
                        }

                        Text(
                            if (isDir) (if (isExpanded) "📂" else "📁") else getFileIcon(file),
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            file.name,
                            color = when {
                                isOpenInTab -> OllamaGreen
                                isDir -> OllamaText.copy(alpha = 0.9f)
                                else -> OllamaTextDim
                            },
                            fontSize = 10.sp,
                            fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

// ── Agent Files Pane ──────────────────────────────────────────────────────────
@Composable
fun AgentFilesPane(vm: MainViewModel, context: Context) {
    var showFileSidebar by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {

        // ── Header: toggle button + tabs (always visible) ─────────────────────
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

            if (vm.openFiles.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
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
                            IconButton(
                                onClick = { vm.closeTab(index) },
                                modifier = Modifier.size(16.dp)
                            ) {
                                Icon(Icons.Default.Close, "Close tab", tint = OllamaTextDim,
                                    modifier = Modifier.size(11.dp))
                            }
                        }
                    }
                }
            } else {
                Text(
                    "Open a file from the sidebar →",
                    color = OllamaTextDim.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
            }
        }

        HorizontalDivider(color = OllamaBorder, thickness = 1.dp)

        Row(Modifier.fillMaxSize()) {
            if (showFileSidebar) {
                FileSidebar(vm)
                VerticalDivider(color = OllamaBorder, thickness = 1.dp)
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    vm.agentSelectedFile != null -> FileEditorArea(vm, context)
                    else -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("📂", fontSize = 40.sp)
                                Text("No file open", color = OllamaTextDim, fontSize = 14.sp)
                                Text(
                                    "Tap ☰ to browse files\nor let the agent open one.",
                                    color = OllamaTextDim.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Editor + HTML preview ─────────────────────────────────────────────────────
@Composable
private fun FileEditorArea(vm: MainViewModel, context: Context) {
    val file     = vm.agentSelectedFile ?: return
    val language = getLanguageFromExtension(file.name)
    val isHtml   = language == "html"
    val isMd     = file.name.endsWith(".md", ignoreCase = true) ||
                   file.name.endsWith(".markdown", ignoreCase = true)

    var showPreview by remember(file.absolutePath) { mutableStateOf(isHtml || isMd) }
    var htmlKey     by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(getFileIcon(file), fontSize = 16.sp)
                Text(
                    file.name, color = OllamaGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("[$language]", color = OllamaTextDim, fontSize = 10.sp)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isHtml || isMd) {
                    TextButton(
                        onClick = { showPreview = !showPreview },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (showPreview) "✏️ Edit" else "👁 Preview",
                            color = if (isHtml) Color(0xFFFFCC44) else OllamaBlue,
                            fontSize = 11.sp
                        )
                    }
                }
                if (isHtml && showPreview) {
                    IconButton(
                        onClick = {
                            vm.saveCurrentFile()
                            htmlKey++
                            Toast.makeText(context, "HTML rebuilt ✓", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1A1A0A))
                    ) {
                        Icon(Icons.Default.Refresh, "Rebuild preview",
                            tint = Color(0xFFFFCC44), modifier = Modifier.size(16.dp))
                    }
                }
                OutlinedButton(
                    onClick = {
                        vm.saveCurrentFile()
                        if (isHtml && showPreview) htmlKey++
                        Toast.makeText(context, "Saved ✓", Toast.LENGTH_SHORT).show()
                    },
                    border = androidx.compose.foundation.BorderStroke(1.dp, OllamaGreen),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("Save", color = OllamaGreen, fontSize = 12.sp) }
                TextButton(onClick = { vm.closeTab(vm.activeTabIndex) }) {
                    Text("Close", color = OllamaTextDim, fontSize = 12.sp)
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isHtml && showPreview) Color.White else com.example.ui.theme.TerminalBg)
                .border(1.dp,
                    if (isHtml && showPreview) Color(0xFFFFCC44).copy(alpha = 0.4f) else OllamaBorder,
                    RoundedCornerShape(8.dp))
        ) {
            when {
                isHtml && showPreview -> {
                    key(htmlKey) {
                        HtmlPreviewPane(html = vm.agentFileContent, modifier = Modifier.fillMaxSize())
                    }
                }
                isMd && showPreview -> {
                    MarkdownViewer(vm.agentFileContent, Modifier.fillMaxSize())
                }
                else -> {
                    EnhancedCodeEditor(
                        code         = vm.agentFileContent,
                        onCodeChange = { vm.agentFileContent = it },
                        language     = language,
                        modifier     = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
