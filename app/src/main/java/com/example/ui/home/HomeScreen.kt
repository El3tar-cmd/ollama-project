package com.example.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.MainViewModel
import com.example.R
import com.example.data.model.AppTab
import com.example.ui.agent.AgentScreen
import com.example.ui.chat.ChatScreen
import com.example.ui.components.StatusPill
import com.example.ui.models.ModelsScreen
import com.example.ui.server.ServerScreen
import com.example.ui.terminal.TerminalScreen
import com.example.ui.theme.*

@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val vm: MainViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(context.applicationContext) as T
    })

    var activeTab by remember { mutableStateOf(AppTab.SERVER) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    val isOnline = if (vm.activeBackend == "llamacpp") vm.llamaApiOnline else vm.apiOnline
    val backendLabel = if (vm.activeBackend == "llamacpp") "llama.cpp" else "Ollama"

    Scaffold(containerColor = OllamaBg) { innerPadding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ─── VSCode Activity Bar (left strip) ───────────────────────────
            Column(
                Modifier
                    .width(52.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF1E1E1E)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo at top
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(Color(0xFF252526)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.devhive_logo),
                        contentDescription = "DevHive",
                        modifier = Modifier.size(28.dp)
                    )
                }

                HorizontalDivider(color = Color(0xFF3C3C3C), thickness = 0.5.dp)
                Spacer(Modifier.height(6.dp))

                // Tab icons
                AppTab.values().forEach { tab ->
                    val selected = activeTab == tab
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(if (selected) Color(0xFF2D2D2D) else Color.Transparent)
                            .clickable { activeTab = tab }
                    ) {
                        // Active indicator — left green bar (VSCode style)
                        if (selected) {
                            Box(
                                Modifier
                                    .width(2.dp)
                                    .fillMaxHeight()
                                    .background(OllamaGreen)
                                    .align(Alignment.CenterStart)
                            )
                        }
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) OllamaGreen else Color(0xFF858585),
                            modifier = Modifier
                                .size(22.dp)
                                .align(Alignment.Center)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Connection dot at bottom
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Color(0xFF1E1E1E)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) OllamaGreen else Color(0xFFCC3333))
                    )
                }
            }

            // ─── Vertical divider ───────────────────────────────────────────
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF3C3C3C)))

            // ─── Main content area ──────────────────────────────────────────
            Column(Modifier.weight(1f).fillMaxHeight()) {

                // Thin VSCode-style title bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(Color(0xFF252526))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "DevHive",
                            color = Color(0xFFCCCCCC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("IDE", color = OllamaGreen, fontSize = 11.sp)
                        Text("›", color = Color(0xFF858585), fontSize = 11.sp)
                        Text(
                            activeTab.label,
                            color = Color(0xFF858585),
                            fontSize = 11.sp
                        )
                    }
                    StatusPill(
                        online = isOnline,
                        label  = if (vm.activeBackend == "llamacpp") "llama.cpp" else null
                    )
                }

                HorizontalDivider(color = Color(0xFF3C3C3C), thickness = 0.5.dp)

                // Content area
                Box(Modifier.weight(1f).background(OllamaBg)) {
                    when (activeTab) {
                        AppTab.SERVER   -> ServerScreen(vm, context)
                        AppTab.MODELS   -> ModelsScreen(vm, context)
                        AppTab.CHAT     -> ChatScreen(vm, context)
                        AppTab.AGENT    -> AgentScreen(vm, context)
                        AppTab.TERMINAL -> TerminalScreen(vm, context)
                    }
                }

                // VSCode status bar (bottom, green)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(OllamaGreen.copy(alpha = 0.9f))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⬡", color = OllamaBg, fontSize = 11.sp)
                        Text("DevHive IDE", color = OllamaBg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (isOnline) "● $backendLabel" else "○ offline",
                        color = OllamaBg.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    // ─── Login dialog (unchanged) ────────────────────────────────────────────
    val url = vm.authLoginUrl
    if (url != null) {
        val clipboard = LocalClipboardManager.current
        var browserOpened by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { vm.authLoginUrl = null; vm.isSigningIn = false },
            containerColor = OllamaCard,
            title = { Text("Sign in to Ollama Cloud", color = OllamaText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Open the link below in your browser and sign in to your Ollama account. " +
                        "Then tap \"Done\" once you've authorized this device.",
                        color = OllamaTextDim, fontSize = 13.sp
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(OllamaCardAlt)
                            .padding(8.dp)
                            .clickable {
                                clipboard.setText(AnnotatedString(url))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Text(url, fontSize = 10.sp, color = OllamaGreen, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    if (browserOpened) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A3A2A)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.AccountCircle, null, tint = OllamaGreen, modifier = Modifier.size(14.dp))
                            Text("Browser opened — authorize, then tap Done ↓", color = OllamaGreen, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            catch (_: Exception) {
                                clipboard.setText(AnnotatedString(url))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                            browserOpened = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccountCircle, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Browser")
                    }
                    if (browserOpened) {
                        Button(
                            onClick = { vm.confirmLogin() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A4A2A), contentColor = OllamaGreen),
                            modifier = Modifier.fillMaxWidth(),
                            border = androidx.compose.foundation.BorderStroke(1.dp, OllamaGreen)
                        ) { Text("✓ Done — I've Signed In", fontWeight = FontWeight.Bold) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(url))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy Link", color = OllamaTextDim) }
            }
        )
    }
}
