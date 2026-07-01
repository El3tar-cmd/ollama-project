package com.example.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.BrowserTabState
import com.example.MainViewModel
import com.example.ui.editor.getFileIcon
import com.example.ui.theme.*
import java.io.File

// ── File tree helpers ─────────────────────────────────────────────────────────
private data class BrowserFileItem(val file: File, val depth: Int)

private fun buildBrowserFileTree(
    dir: File,
    expandedDirs: Set<String>,
    depth: Int = 0
): List<BrowserFileItem> {
    val children = dir.listFiles()
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        ?: return emptyList()
    return children.flatMap { f ->
        val item = BrowserFileItem(f, depth)
        if (f.isDirectory && f.absolutePath in expandedDirs)
            listOf(item) + buildBrowserFileTree(f, expandedDirs, depth + 1)
        else listOf(item)
    }
}

private val WEB_EXTENSIONS = setOf(
    "html", "htm", "xhtml",
    "js", "mjs", "ts",
    "css", "less", "scss",
    "json", "xml", "svg",
    "png", "jpg", "jpeg", "gif", "webp",
    "md", "txt"
)

@Composable
private fun BrowserStartPage(
    shortcuts: List<Pair<String, String>>,
    onOpen: (String) -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(OllamaBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Browser",
            color = OllamaGreen,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            shortcuts.forEach { (url, label) ->
                OutlinedButton(
                    onClick = { onOpen("http://$url") },
                    border = BorderStroke(1.dp, OllamaBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(label, color = OllamaGreen, fontSize = 11.sp)
                }
            }
            OutlinedButton(
                onClick = onOpenFiles,
                border = BorderStroke(1.dp, OllamaBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("Files", color = Color(0xFFFFD966), fontSize = 11.sp)
            }
        }
    }
}

// ── File Manager Bottom Sheet ─────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileManagerSheet(
    vm: MainViewModel,
    onFileSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val rootDir       = File(vm.agentWorkingDir.ifBlank { "/sdcard" })
    val expandedDirs  = remember { mutableStateOf(setOf(rootDir.absolutePath)) }
    var currentRoot   by remember { mutableStateOf(rootDir) }

    val treeItems = remember(currentRoot.absolutePath, expandedDirs.value) {
        buildBrowserFileTree(currentRoot, expandedDirs.value)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1A1A1A),
        dragHandle = {
            // custom handle
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.width(40.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF555555))
                )
            }
        }
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {

            // ── Header ────────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF252526))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("📁", fontSize = 16.sp)
                Text(
                    "File Manager",
                    color = OllamaGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                // Navigate up
                if (currentRoot.parentFile?.canRead() == true) {
                    IconButton(
                        onClick = { currentRoot = currentRoot.parentFile!! },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, "Up",
                            tint = OllamaTextDim, modifier = Modifier.size(16.dp))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = OllamaTextDim, fontSize = 11.sp)
                }
            }

            // Current path breadcrumb
            Text(
                currentRoot.absolutePath,
                color = Color(0xFF555555),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141414))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            HorizontalDivider(color = Color(0xFF2C2C2C))

            // ── File List ─────────────────────────────────────────────────────
            if (treeItems.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Empty folder", color = OllamaTextDim, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(treeItems, key = { it.file.absolutePath }) { item ->
                        val f          = item.file
                        val isDir      = f.isDirectory
                        val isExpanded = f.absolutePath in expandedDirs.value
                        val ext        = f.name.substringAfterLast('.', "").lowercase()
                        val isWeb      = ext in WEB_EXTENSIONS
                        val isHtml     = ext in setOf("html", "htm", "xhtml")

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isHtml) OllamaGreen.copy(0.06f) else Color.Transparent)
                                .clickable {
                                    if (isDir) {
                                        val path = f.absolutePath
                                        expandedDirs.value = if (isExpanded)
                                            expandedDirs.value - path
                                        else expandedDirs.value + path
                                    } else if (isWeb) {
                                        val fileUrl = "file://${f.absolutePath}"
                                        onFileSelected(fileUrl)
                                        onDismiss()
                                    }
                                }
                                .padding(
                                    start = (8 + item.depth * 14).dp,
                                    end = 8.dp, top = 7.dp, bottom = 7.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Expand arrow for dirs
                            if (isDir) {
                                Icon(
                                    if (isExpanded) Icons.Default.KeyboardArrowDown
                                    else Icons.Default.KeyboardArrowRight,
                                    null, tint = OllamaTextDim.copy(0.6f),
                                    modifier = Modifier.size(14.dp)
                                )
                            } else {
                                Spacer(Modifier.width(14.dp))
                            }

                            Text(
                                if (isDir) (if (isExpanded) "📂" else "📁")
                                else getFileIcon(f),
                                fontSize = 13.sp
                            )

                            Text(
                                f.name,
                                color = when {
                                    isHtml -> Color(0xFFFFD966)
                                    isDir  -> OllamaText
                                    isWeb  -> OllamaTextDim
                                    else   -> Color(0xFF555555)
                                },
                                fontSize = 12.sp,
                                fontWeight = if (isHtml) FontWeight.SemiBold
                                    else if (isDir) FontWeight.Medium
                                    else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            // "Open" badge for HTML files
                            if (isHtml) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(OllamaGreen.copy(0.15f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text("Open", color = OllamaGreen, fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace)
                                }
                            } else if (isDir) {
                                Text(
                                    "${f.listFiles()?.size ?: 0}",
                                    color = Color(0xFF444444),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // ── Bottom hint ───────────────────────────────────────────────────
            HorizontalDivider(color = Color(0xFF2C2C2C))
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF141414))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🟡", fontSize = 10.sp)
                Text("HTML files open in browser with CSS & JS loaded",
                    color = Color(0xFF555555), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Browser Screen ────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: MainViewModel) {

    val tabs = vm.browserTabs
    val activeIdx = vm.browserActiveIdx.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    if (vm.browserActiveIdx != activeIdx) vm.browserActiveIdx = activeIdx
    var progress  by remember { mutableFloatStateOf(0f) }
    var pageTitle by remember { mutableStateOf("New Tab") }
    var canGoBack    by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showFileManager by remember { mutableStateOf(false) }
    val focusManager  = LocalFocusManager.current
    val urlFocus      = remember { FocusRequester() }

    val webView = remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            webView.value?.let { view ->
                vm.browserWebViewState = Bundle().also { view.saveState(it) }
            }
        }
    }

    fun navigate(rawUrl: String) {
        val url = when {
            rawUrl.isBlank()                                       -> return
            rawUrl.startsWith("file://")                          -> rawUrl
            rawUrl.startsWith("http://") ||
            rawUrl.startsWith("https://") ||
            rawUrl.startsWith("about:")                           -> rawUrl
            rawUrl.startsWith("localhost") ||
            rawUrl.startsWith("127.")                              -> "http://$rawUrl"
            rawUrl.matches(Regex("""[\w.-]+\.\w{2,}(/.*)?"""))   -> "https://$rawUrl"
            else -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(rawUrl, "UTF-8")}"
        }
        vm.browserUrlInput = url
        vm.updateBrowserTab(activeIdx, url = url)
        webView.value?.loadUrl(url)
        focusManager.clearFocus()
    }

    fun newTab(url: String = "about:blank") {
        val id = (tabs.maxOfOrNull { it.id } ?: 0) + 1
        tabs.add(BrowserTabState(id, url, "New Tab"))
        vm.browserActiveIdx = tabs.size - 1
        vm.browserUrlInput = url
        webView.value?.loadUrl(url)
    }

    fun closeTab(idx: Int) {
        if (tabs.size <= 1) { newTab(); return }
        tabs.removeAt(idx)
        vm.browserActiveIdx = (idx - 1).coerceAtLeast(0)
        val tab = tabs[vm.browserActiveIdx]
        vm.browserUrlInput = tab.url
        webView.value?.loadUrl(tab.url)
    }

    val serverPort = vm.linuxSession.serverPort
    val shortcuts  = buildList {
        serverPort?.let { add("localhost:$it" to "Server :$it") }
        add("localhost:8080" to ":8080")
        add("localhost:3000" to ":3000")
        add("localhost:5000" to ":5000")
        add("localhost:8000" to ":8000")
    }.distinctBy { it.first }

    // ── File Manager Sheet ────────────────────────────────────────────────────
    if (showFileManager) {
        FileManagerSheet(
            vm             = vm,
            onFileSelected = { fileUrl -> navigate(fileUrl) },
            onDismiss      = { showFileManager = false }
        )
    }

    Column(Modifier.fillMaxSize().background(OllamaBg)) {

        // ── Toolbar ────────────────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Back
                IconButton(
                    onClick = { if (canGoBack) webView.value?.goBack() },
                    modifier = Modifier.size(36.dp),
                    enabled = canGoBack
                ) {
                    Icon(Icons.Default.ArrowBack, "Back",
                        tint = if (canGoBack) Color(0xFFCCCCCC) else Color(0xFF555555),
                        modifier = Modifier.size(18.dp))
                }
                // Forward
                IconButton(
                    onClick = { if (canGoForward) webView.value?.goForward() },
                    modifier = Modifier.size(36.dp),
                    enabled = canGoForward
                ) {
                    Icon(Icons.Default.ArrowForward, "Forward",
                        tint = if (canGoForward) Color(0xFFCCCCCC) else Color(0xFF555555),
                        modifier = Modifier.size(18.dp))
                }
                // Refresh / Stop
                IconButton(
                    onClick = {
                        if (progress in 0.01f..0.99f) webView.value?.stopLoading()
                        else webView.value?.reload()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (progress in 0.01f..0.99f) Icons.Default.Clear else Icons.Default.Refresh,
                        "Reload", tint = Color(0xFFCCCCCC), modifier = Modifier.size(18.dp)
                    )
                }

                // URL bar
                OutlinedTextField(
                    value = vm.browserUrlInput,
                    onValueChange = { vm.browserUrlInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(urlFocus),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 12.sp,
                        color      = Color(0xFFE0E0E0)
                    ),
                    placeholder = {
                        Text("Search or enter URL…", color = Color(0xFF666666),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = OllamaGreen,
                        unfocusedBorderColor    = Color(0xFF3C3C3C),
                        cursorColor             = OllamaGreen,
                        focusedContainerColor   = Color(0xFF252526),
                        unfocusedContainerColor = Color(0xFF252526)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigate(vm.browserUrlInput) })
                )

                // File Manager button
                IconButton(
                    onClick = { showFileManager = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.List, "Files",
                        tint = Color(0xFFFFD966), modifier = Modifier.size(18.dp))
                }

                // New tab
                IconButton(
                    onClick = { newTab() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Add, "New Tab",
                        tint = OllamaGreen, modifier = Modifier.size(18.dp))
                }
            }

            // Progress bar
            if (progress in 0.01f..0.99f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier  = Modifier.fillMaxWidth().height(2.dp),
                    color     = OllamaGreen,
                    trackColor = Color(0xFF3C3C3C)
                )
            }

            // ── Tabs row ────────────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(Color(0xFF252526))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                tabs.forEachIndexed { idx, tab ->
                    val sel = idx == activeIdx
                    Row(
                        Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel) Color(0xFF2D2D2D) else Color.Transparent)
                            .border(
                                0.5.dp,
                                if (sel) OllamaGreen.copy(0.6f) else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                vm.browserActiveIdx = idx
                                vm.browserUrlInput = tab.url
                                webView.value?.loadUrl(tab.url)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            tab.title.take(20),
                            color    = if (sel) Color(0xFFE0E0E0) else Color(0xFF888888),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (tabs.size > 1) {
                            Icon(
                                Icons.Default.Close, "Close",
                                tint     = Color(0xFF888888),
                                modifier = Modifier
                                    .size(12.dp)
                                    .clickable { closeTab(idx) }
                            )
                        }
                    }
                }
            }

            // ── Localhost shortcuts ──────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Quick:", color = Color(0xFF555555), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace)
                shortcuts.forEach { (url, label) ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (serverPort != null && url.contains(serverPort.toString()))
                                    OllamaGreen.copy(0.15f) else Color(0xFF2A2A2A)
                            )
                            .border(
                                0.5.dp,
                                if (serverPort != null && url.contains(serverPort.toString()))
                                    OllamaGreen.copy(0.5f) else Color(0xFF3C3C3C),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { navigate("http://$url") }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(label, color = OllamaGreen, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                // 📁 Files shortcut
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF2A2A1A))
                        .border(0.5.dp, Color(0xFF554433), RoundedCornerShape(4.dp))
                        .clickable { showFileManager = true }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("📁 Files", color = Color(0xFFFFD966), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF2A2A2A))
                        .clickable { navigate("https://google.com") }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("Google", color = Color(0xFF888888), fontSize = 10.sp)
                }
            }

            HorizontalDivider(color = Color(0xFF3C3C3C), thickness = 0.5.dp)
        }

        val currentUrl = tabs.getOrNull(activeIdx)?.url ?: "about:blank"
        if (currentUrl == "about:blank") {
            BrowserStartPage(
                shortcuts = shortcuts,
                onOpen = ::navigate,
                onOpenFiles = { showFileManager = true },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            // ── WebView ────────────────────────────────────────────────────────────
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(AndroidColor.WHITE)
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        settings.apply {
                            javaScriptEnabled          = true
                            domStorageEnabled           = true
                            databaseEnabled             = true
                            allowFileAccess             = true
                            allowContentAccess          = true
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(false)
                            @Suppress("DEPRECATION")
                            allowUniversalAccessFromFileURLs = true
                            @Suppress("DEPRECATION")
                            allowFileAccessFromFileURLs = true
                            useWideViewPort             = true
                            loadWithOverviewMode        = true
                            builtInZoomControls         = true
                            displayZoomControls         = false
                            mixedContentMode            =
                                android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                vm.browserUrlInput = url ?: ""
                                progress  = 0.1f
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                progress     = 0f
                                canGoBack    = view.canGoBack()
                                canGoForward = view.canGoForward()
                                val curr = url ?: ""
                                vm.updateBrowserTab(vm.browserActiveIdx, url = curr)
                                vm.browserUrlInput = curr
                                view.postInvalidate()
                            }
                            override fun onPageCommitVisible(view: WebView, url: String?) {
                                super.onPageCommitVisible(view, url)
                                view.postInvalidate()
                            }
                            override fun shouldOverrideUrlLoading(
                                view: WebView, request: WebResourceRequest
                            ): Boolean {
                                val scheme = request.url.scheme.orEmpty()
                                return scheme !in setOf("http", "https", "file", "about")
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                val t = title?.take(30) ?: "Tab"
                                pageTitle = t
                                vm.updateBrowserTab(vm.browserActiveIdx, title = t)
                            }
                        }

                        webView.value = this
                        val restored = vm.browserWebViewState?.let { restoreState(it) != null } == true
                        if (!restored && currentUrl != "about:blank") loadUrl(currentUrl)
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                update = { view ->
                    if (view.url != currentUrl &&
                        view.originalUrl != currentUrl
                    ) {
                        view.loadUrl(currentUrl)
                    }
                }
            )
        }
    }
}
