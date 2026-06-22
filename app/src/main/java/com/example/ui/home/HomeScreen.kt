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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        containerColor = OllamaBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OllamaSurface, titleContentColor = OllamaText),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF5C518)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.devhive_logo),
                                contentDescription = "DevHive Logo",
                                modifier = Modifier.size(30.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        }
                        Text("DevHive", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = OllamaText)
                        Text("IDE", fontWeight = FontWeight.Light, fontSize = 18.sp, color = OllamaGreen)
                    }
                },
                actions = {
                    StatusPill(
                        online = if (vm.activeBackend == "llamacpp") vm.llamaApiOnline else vm.apiOnline,
                        label  = if (vm.activeBackend == "llamacpp") "llama.cpp" else null
                    )
                    Spacer(Modifier.width(12.dp))
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = OllamaSurface, tonalElevation = 0.dp) {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected  = activeTab == tab,
                        onClick   = { activeTab = tab },
                        icon      = { Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(20.dp)) },
                        label     = { Text(tab.label, fontSize = 10.sp) },
                        colors    = NavigationBarItemDefaults.colors(
                            selectedIconColor   = OllamaGreen,
                            selectedTextColor   = OllamaGreen,
                            indicatorColor      = OllamaCard,
                            unselectedIconColor = OllamaTextDim,
                            unselectedTextColor = OllamaTextDim
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(OllamaBg)) {
            when (activeTab) {
                AppTab.SERVER   -> ServerScreen(vm, context)
                AppTab.MODELS   -> ModelsScreen(vm, context)
                AppTab.CHAT     -> ChatScreen(vm, context)
                AppTab.AGENT    -> AgentScreen(vm, context)
                AppTab.TERMINAL -> TerminalScreen(vm, context)
            }
        }
    }

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
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(OllamaCardAlt).padding(8.dp)
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
