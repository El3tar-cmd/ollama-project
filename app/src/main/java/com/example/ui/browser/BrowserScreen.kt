package com.example.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import com.example.MainViewModel
import com.example.ui.theme.*

// ── Tab data model ────────────────────────────────────────────────────────────
data class BrowserTab(
    val id: Int,
    var url: String  = "about:blank",
    var title: String = "New Tab"
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: MainViewModel) {

    val tabs    = remember { mutableStateListOf(BrowserTab(0, "about:blank", "New Tab")) }
    var activeIdx by remember { mutableIntStateOf(0) }
    var urlInput  by remember { mutableStateOf("") }
    var progress  by remember { mutableFloatStateOf(0f) }
    var pageTitle by remember { mutableStateOf("New Tab") }
    var canGoBack    by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val focusManager  = LocalFocusManager.current
    val urlFocus      = remember { FocusRequester() }

    // WebView instance — persisted across recompositions
    val webView = remember {
        mutableStateOf<WebView?>(null)
    }

    fun navigate(rawUrl: String) {
        val url = when {
            rawUrl.isBlank()                                       -> return
            rawUrl.startsWith("http://") ||
            rawUrl.startsWith("https://") ||
            rawUrl.startsWith("localhost") ||
            rawUrl.startsWith("127.") ||
            rawUrl.startsWith("about:")                           -> rawUrl
            rawUrl.matches(Regex("""[\w.-]+\.\w{2,}(/.*)?"""))   -> "https://$rawUrl"
            else -> "https://www.google.com/search?q=${java.net.URLEncoder.encode(rawUrl, "UTF-8")}"
        }
        urlInput = url
        tabs[activeIdx] = tabs[activeIdx].copy(url = url)
        webView.value?.loadUrl(url)
        focusManager.clearFocus()
    }

    fun newTab(url: String = "about:blank") {
        val id = (tabs.maxOfOrNull { it.id } ?: 0) + 1
        tabs.add(BrowserTab(id, url, "New Tab"))
        activeIdx = tabs.size - 1
        urlInput  = url
        webView.value?.loadUrl(url)
    }

    fun closeTab(idx: Int) {
        if (tabs.size <= 1) { newTab(); return }
        tabs.removeAt(idx)
        activeIdx = (idx - 1).coerceAtLeast(0)
        val tab = tabs[activeIdx]
        urlInput = tab.url
        webView.value?.loadUrl(tab.url)
    }

    // Quick-access localhost shortcuts
    val serverPort = vm.linuxSession.serverPort
    val shortcuts  = buildList {
        serverPort?.let { add("localhost:$it" to "Server :$it") }
        add("localhost:8080" to ":8080")
        add("localhost:3000" to ":3000")
        add("localhost:5000" to ":5000")
        add("localhost:8000" to ":8000")
    }.distinctBy { it.first }

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
                    value = urlInput,
                    onValueChange = { urlInput = it },
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
                    keyboardActions = KeyboardActions(onGo = { navigate(urlInput) })
                )

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
                                activeIdx = idx
                                urlInput  = tab.url
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
                Spacer(Modifier.weight(1f))
                // Home button
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

        // ── WebView ────────────────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled          = true
                        domStorageEnabled           = true
                        allowFileAccess             = true
                        allowContentAccess          = true
                        setSupportMultipleWindows(false)
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = true
                        useWideViewPort             = true
                        loadWithOverviewMode        = true
                        builtInZoomControls         = true
                        displayZoomControls         = false
                        mixedContentMode            =
                            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            urlInput  = url ?: ""
                            progress  = 0.1f
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            progress     = 0f
                            canGoBack    = view.canGoBack()
                            canGoForward = view.canGoForward()
                            val curr = url ?: ""
                            tabs[activeIdx] = tabs[activeIdx].copy(url = curr)
                            urlInput = curr
                        }
                        override fun shouldOverrideUrlLoading(
                            view: WebView, request: WebResourceRequest
                        ): Boolean = false
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress / 100f
                        }
                        override fun onReceivedTitle(view: WebView, title: String?) {
                            val t = title?.take(30) ?: "Tab"
                            pageTitle = t
                            tabs[activeIdx] = tabs[activeIdx].copy(title = t)
                        }
                    }

                    webView.value = this

                    // Load the current tab's URL
                    val initUrl = tabs[activeIdx].url
                    if (initUrl != "about:blank") loadUrl(initUrl)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            update = { /* WebView manages its own state */ }
        )
    }
}
