package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun ServerScreen(vm: MainViewModel, context: Context) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status header
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(OllamaCard)
                .border(1.dp, if (vm.serviceActive) Color(0xFF1E4030) else OllamaBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DAEMON", color = OllamaTextDim, fontSize = 10.sp, letterSpacing = 1.sp)
                Text(
                    if (vm.serviceActive) "Running · Port 11434" else "Stopped",
                    color = if (vm.serviceActive) OllamaGreen else OllamaRed,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                Text(
                    if (vm.binaryInstalled) "ARM64 binary ready" else if (vm.isPreparingBinary) "Copying binary..." else "Binary missing",
                    color = OllamaTextDim, fontSize = 11.sp
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = vm.serviceActive,
                    onCheckedChange = { vm.toggleOllamaService(context) },
                    enabled = vm.binaryInstalled && !vm.isPreparingBinary,
                    modifier = Modifier.testTag("daemon_toggle"),
                    colors = SwitchDefaults.colors(checkedThumbColor = OllamaBg, checkedTrackColor = OllamaGreen)
                )
                if (vm.isPreparingBinary) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = OllamaGreen, strokeWidth = 2.dp)
                }
            }
        }

        // Binary engine
        SectionCard("BINARY ENGINE", "Ollama ARM64 executable for Android") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        if (vm.binaryInstalled) "✓ Installed" else "✗ Not found",
                        color = if (vm.binaryInstalled) OllamaGreen else OllamaRed,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    if (vm.isInstalling) Text(vm.setupStatus, color = OllamaTextDim, fontSize = 11.sp)
                }
                Button(
                    onClick = { vm.triggerBinaryInstall(context) },
                    enabled = !vm.isInstalling,
                    colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg),
                    modifier = Modifier.testTag("binary_download_btn")
                ) {
                    if (vm.isInstalling) CircularProgressIndicator(Modifier.size(14.dp), color = OllamaBg, strokeWidth = 2.dp)
                    else Text(if (vm.binaryInstalled) "Reinstall" else "Install", fontWeight = FontWeight.Bold)
                }
            }
            if (vm.isInstalling && vm.setupProgress >= 0) {
                LinearProgressIndicator(
                    progress = { vm.setupProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = OllamaGreen, trackColor = OllamaBorder
                )
            }
        }

        // Network config
        SectionCard("NETWORK CONFIG") {
            OllamaTextField(vm.hostUrlState, { vm.hostUrlState = it }, "OLLAMA_HOST", Modifier.fillMaxWidth(), tag = "host_input",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next))
            OllamaTextField(vm.originsState, { vm.originsState = it }, "OLLAMA_ORIGINS (CORS)", Modifier.fillMaxWidth(), tag = "origins_input",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            Text("Set host to 0.0.0.0:11434 to expose server to your local Wi-Fi network.", color = OllamaTextDim, fontSize = 11.sp)
        }

        // Cloud auth
        SectionCard("CLOUD AUTH", "Required for cloud models (e.g. glm-4.7:cloud)") {
            if (vm.isLoggedIn) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A3A2A)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = OllamaGreen, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (vm.cloudApiKey.isNotBlank()) "✅ API key active" else "Logged in (no API key yet)",
                            color = OllamaGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        if (vm.cloudApiKey.isNotBlank())
                            Text("Cloud models route directly to Ollama Cloud", color = OllamaGreen.copy(alpha = 0.7f), fontSize = 10.sp)
                        else
                            Text("Add an API key below to use cloud models", color = OllamaRed.copy(alpha = 0.8f), fontSize = 10.sp)
                    }
                }
            }

            if (vm.cloudAuthStatus.isNotBlank()) {
                Text(vm.cloudAuthStatus, color = if (vm.cloudAuthStatus.startsWith("✅")) OllamaGreen else OllamaRed, fontSize = 12.sp)
            }

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(OllamaCardAlt).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("API Key", color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Recommended — works instantly", color = OllamaGreen, fontSize = 10.sp)
                    }
                    TextButton(
                        onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ollama.com/settings/keys"))) }
                            catch (_: Exception) {}
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Get Key →", color = OllamaGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OllamaTextField(
                        value = vm.manualApiKeyInput,
                        onValueChange = { vm.manualApiKeyInput = it },
                        label = "ollama_…",
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = { vm.validateAndSaveApiKey(context, vm.manualApiKeyInput) }
                        )
                    )
                    Button(
                        onClick = { vm.validateAndSaveApiKey(context, vm.manualApiKeyInput) },
                        enabled = vm.manualApiKeyInput.isNotBlank() && !vm.isValidatingKey,
                        colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg)
                    ) {
                        if (vm.isValidatingKey) CircularProgressIndicator(Modifier.size(14.dp), color = OllamaBg, strokeWidth = 2.dp)
                        else Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
                if (vm.cloudApiKey.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = OllamaGreen, modifier = Modifier.size(12.dp))
                        Text("Key saved  •  ${vm.cloudApiKey.take(8)}…", color = OllamaTextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(Modifier.weight(1f), color = OllamaBorder)
                Text("OR", color = OllamaTextDim, fontSize = 10.sp, letterSpacing = 1.sp)
                HorizontalDivider(Modifier.weight(1f), color = OllamaBorder)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.triggerLogin(context) },
                    modifier = Modifier.weight(1f),
                    enabled = !vm.isSigningIn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (vm.isLoggedIn) OllamaCard else OllamaSurface,
                        contentColor   = if (vm.isLoggedIn) OllamaTextDim else OllamaText
                    ),
                    border = BorderStroke(1.dp, OllamaBorder)
                ) {
                    if (vm.isSigningIn) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = OllamaText, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Waiting…")
                    } else {
                        Icon(Icons.Default.AccountCircle, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (vm.isLoggedIn) "Re-login" else "Login via Browser", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (vm.isLoggedIn) {
                    OutlinedButton(
                        onClick = { vm.triggerLogout(context) },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, OllamaBorder)
                    ) { Text("Logout", color = OllamaTextDim) }
                }
            }
            Text(
                "Browser login registers an SSH key — requires newer Ollama binary for cloud models.\nAPI key above works immediately.",
                color = OllamaTextDim, fontSize = 10.sp
            )
        }

        // llama.cpp backend section
        SectionCard("llama.cpp BACKEND", "Vulkan GPU accelerated • GGUF models") {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(OllamaCardAlt).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("ollama" to "Ollama", "llamacpp" to "llama.cpp").forEach { (key, label) ->
                    val selected = vm.activeBackend == key
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                            .background(if (selected) OllamaGreen else Color.Transparent)
                            .clickable { vm.activeBackend = key }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (selected) OllamaBg else OllamaTextDim,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                    }
                }
            }

            if (vm.activeBackend == "llamacpp") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (vm.llamaApiOnline) "● Running · Port ${vm.llamaPort}"
                            else if (vm.llamaServiceActive) "● Starting…"
                            else "○ Stopped",
                            color = if (vm.llamaApiOnline) OllamaGreen else OllamaTextDim, fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        if (vm.llamaSelectedModel != null) {
                            Text("Model: ${vm.llamaSelectedModel!!.name}", color = OllamaTextDim, fontSize = 10.sp)
                        } else {
                            Text("⚠ Go to Models tab → download a GGUF model → select it", color = Color(0xFFFFAA33), fontSize = 11.sp)
                        }
                        if (vm.llamaHealthStatus.isNotBlank() && !vm.llamaApiOnline) {
                            Text(vm.llamaHealthStatus, color = OllamaTextDim, fontSize = 11.sp)
                        }
                    }
                    Switch(
                        checked = vm.llamaServiceActive || vm.llamaApiOnline,
                        onCheckedChange = { vm.toggleLlamaService(context) },
                        enabled = vm.llamaSelectedModel != null,
                        colors = SwitchDefaults.colors(checkedThumbColor = OllamaBg, checkedTrackColor = OllamaGreen)
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OllamaTextField(vm.llamaPort, { vm.llamaPort = it }, "Port", Modifier.width(100.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OllamaTextField(vm.llamaGpuLayers.toString(), { vm.llamaGpuLayers = it.toIntOrNull() ?: 99 }, "GPU Layers", Modifier.width(110.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OllamaTextField(vm.llamaContextSize.toString(), { vm.llamaContextSize = it.toIntOrNull() ?: 4096 }, "Context", Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OllamaTextField(vm.llamaThreads.toString(), { vm.llamaThreads = it.toIntOrNull() ?: 4 }, "Threads", Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
                    OllamaTextField(vm.llamaBatchSize.toString(), { vm.llamaBatchSize = it.toIntOrNull() ?: 512 }, "Batch", Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Temperature", color = OllamaText, fontSize = 13.sp)
                    Text(String.format("%.2f", vm.llamaTemperature), color = OllamaGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = vm.llamaTemperature, onValueChange = { vm.llamaTemperature = it },
                    valueRange = 0f..2f, steps = 39,
                    colors = SliderDefaults.colors(thumbColor = OllamaGreen, activeTrackColor = OllamaGreen, inactiveTrackColor = OllamaBorder)
                )

                LaunchedEffect(Unit) { vm.checkVulkanSupport(context) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("GPU Acceleration (Vulkan)", color = OllamaText, fontSize = 13.sp)
                        Text(
                            if (vm.deviceHasVulkan) "✅ Supported — set GPU Layers > 0 to offload"
                            else "⚠ Not detected — CPU inference only",
                            color = if (vm.deviceHasVulkan) OllamaGreen else Color(0xFFFFAA33), fontSize = 10.sp
                        )
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (vm.deviceHasVulkan) OllamaGreen else OllamaRed))
                }
                Text(
                    "llama-server runs as a separate process with OpenAI-compatible API on port ${vm.llamaPort}.",
                    color = OllamaTextDim, fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
