package com.example.ui.models

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.LlamaCppServer
import com.example.MainViewModel
import com.example.ui.components.OllamaTextField
import com.example.ui.components.SectionCard
import com.example.ui.theme.*

@Composable
fun ModelsScreen(vm: MainViewModel, context: Context) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

        SectionCard("PULL MODEL", "Download from Ollama registry") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OllamaTextField(
                    vm.customModelPullName, { vm.customModelPullName = it }, "Model name (e.g. qwen2:0.5b)",
                    Modifier.weight(1f), tag = "model_pull_input",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); vm.pullModel(context) })
                )
                Button(
                    onClick = { focusManager.clearFocus(); vm.pullModel(context) },
                    enabled = !vm.isPullingActive && vm.apiOnline,
                    colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen, contentColor = OllamaBg),
                    modifier = Modifier.height(56.dp).testTag("pull_start_btn")
                ) { Text("Pull", fontWeight = FontWeight.Bold) }
            }

            if (vm.isPullingActive) {
                Text(vm.pullProgressStatus, color = OllamaGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (vm.pullProgressPercent >= 0)
                    LinearProgressIndicator({ vm.pullProgressPercent / 100f }, Modifier.fillMaxWidth(), color = OllamaGreen, trackColor = OllamaBorder)
                else
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = OllamaGreen, trackColor = OllamaBorder)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("qwen2:0.5b", "gemma:2b", "tinyllama", "phi3:mini").forEach { name ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(OllamaCardAlt)
                            .border(1.dp, OllamaBorder, RoundedCornerShape(6.dp))
                            .clickable { vm.customModelPullName = name }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(name, fontSize = 9.sp, color = OllamaTextDim, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }

        Text("INSTALLED MODELS", color = OllamaTextDim, fontSize = 10.sp, letterSpacing = 1.sp)

        if (!vm.apiOnline) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null, tint = OllamaTextDim, modifier = Modifier.size(36.dp))
                    Text("Server offline\nStart the daemon to see installed models", color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 13.sp)
                }
            }
        } else if (vm.modelList.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text("No models installed\nPull a model above to get started", color = OllamaTextDim, textAlign = TextAlign.Center, fontSize = 13.sp)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.modelList.forEach { model ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(OllamaCard)
                            .border(1.dp, OllamaBorder, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(model.name, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(String.format("%.2f GB", model.size / 1e9), color = OllamaTextDim, fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    vm.selectedModelChat = model.name
                                    vm.agentModel = model.name
                                    android.widget.Toast.makeText(context, "Selected: ${model.name}", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                border = BorderStroke(1.dp, OllamaGreen),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("Use", color = OllamaGreen, fontSize = 12.sp) }
                            IconButton(onClick = { vm.deleteModel(context, model.name) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, null, tint = OllamaRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        SectionCard("GGUF MODELS", "Download quantized GGUF models for llama.cpp") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Local GGUF models", color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${vm.llamaAvailableGGUFs.size} found", color = OllamaTextDim, fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = { vm.scanGGUFs() },
                    border = BorderStroke(1.dp, OllamaBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("Rescan", color = OllamaTextDim, fontSize = 12.sp) }
            }

            if (vm.llamaAvailableGGUFs.isNotEmpty()) {
                vm.llamaAvailableGGUFs.forEach { file ->
                    val selected = vm.llamaSelectedModel?.absolutePath == file.absolutePath
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) OllamaGreen.copy(alpha = 0.1f) else OllamaCard)
                            .border(1.dp, if (selected) OllamaGreen else OllamaBorder, RoundedCornerShape(8.dp))
                            .clickable { vm.llamaSelectedModel = file }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = if (selected) OllamaGreen else OllamaText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${file.length() / 1_000_000} MB", color = OllamaTextDim, fontSize = 10.sp)
                        }
                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = OllamaGreen, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(Modifier.weight(1f), color = OllamaBorder)
                Text("DOWNLOAD CURATED MODELS", color = OllamaTextDim, fontSize = 9.sp, letterSpacing = 1.sp)
                HorizontalDivider(Modifier.weight(1f), color = OllamaBorder)
            }

            if (vm.isDownloadingGGUF) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.ggufDownloadStatus, color = OllamaGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.cancelGGUFDownload() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("✕ Cancel", color = OllamaRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                LinearProgressIndicator({ vm.ggufDownloadProgress / 100f }, Modifier.fillMaxWidth(), color = OllamaGreen, trackColor = OllamaBorder)
            }

            LlamaCppServer.CURATED_GGUF_MODELS.forEach { model ->
                val alreadyDownloaded = vm.llamaAvailableGGUFs.any { it.name == model.fileName }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(OllamaCard)
                        .border(1.dp, OllamaBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.displayName, color = OllamaText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("${model.size} · ${model.fileName}", color = OllamaTextDim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (alreadyDownloaded) Text("✓ Downloaded", color = OllamaGreen, fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            if (alreadyDownloaded) vm.llamaSelectedModel = vm.llamaAvailableGGUFs.first { it.name == model.fileName }
                            else vm.downloadGGUF(context, model)
                        },
                        enabled = !vm.isDownloadingGGUF,
                        border = BorderStroke(1.dp, if (alreadyDownloaded) OllamaGreen else OllamaBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(if (alreadyDownloaded) "Select" else "Download", color = if (alreadyDownloaded) OllamaGreen else OllamaText, fontSize = 12.sp)
                    }
                }
            }
        }

        SectionCard("DOWNLOAD FROM HUGGINGFACE", "Enter repo and filename to download any model") {
            val focusManager2 = LocalFocusManager.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OllamaTextField(
                    vm.hfRepo, { vm.hfRepo = it },
                    "owner/repo (e.g. bartowski/Llama-3.2-1B-Instruct)",
                    Modifier.weight(1f), tag = "hf_repo_input",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OllamaTextField(
                    vm.hfFile, { vm.hfFile = it },
                    "filename (e.g. model.gguf)",
                    Modifier.weight(1f), tag = "hf_file_input",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager2.clearFocus(); vm.downloadFromHF(context) })
                )
                val hfProgress = vm.hfDownloadProgress
                Button(
                    onClick = {
                        focusManager2.clearFocus()
                        if (hfProgress >= 0f) vm.cancelHFDownload()
                        else vm.downloadFromHF(context)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hfProgress >= 0f) OllamaRed else OllamaGreen,
                        contentColor = OllamaBg
                    ),
                    modifier = Modifier.height(56.dp)
                ) { Text(if (hfProgress >= 0f) "Cancel" else "Download", fontWeight = FontWeight.Bold) }
            }
            Text("POPULAR REPOS", color = OllamaTextDim, fontSize = 9.sp, letterSpacing = 1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("bartowski", "unsloth", "TheBloke", "QuantFactory").forEach { org ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                            .background(OllamaCardAlt).border(1.dp, OllamaBorder, RoundedCornerShape(4.dp))
                            .clickable { vm.hfRepo = "$org/" }.padding(horizontal = 4.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(org, fontSize = 9.sp, color = OllamaTextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            val hfProg = vm.hfDownloadProgress
            if (hfProg >= 0f) {
                LinearProgressIndicator({ hfProg }, Modifier.fillMaxWidth(), color = OllamaGreen, trackColor = OllamaBorder)
            }
            val hfStatus = vm.hfDownloadStatus
            if (hfStatus.isNotBlank()) {
                Text(
                    hfStatus,
                    color = when {
                        hfStatus.startsWith("✅") -> OllamaGreen
                        hfStatus.startsWith("❌") -> OllamaRed
                        else -> OllamaTextDim
                    },
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
            Text("Downloaded to /Download/ • Tap Scan to find file after download", color = OllamaTextDim, fontSize = 9.sp)
        }
    }
}
