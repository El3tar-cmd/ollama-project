package com.example.ui.agent

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

// ── Git operation helper ───────────────────────────────────────────────────────
private fun runGitCmd(cwd: String, vararg args: String): Pair<Int, String> {
    return try {
        val gitPaths = listOf("/usr/bin/git", "/usr/local/bin/git", "/bin/git", "git")
        val gitBin = gitPaths.firstOrNull { File(it).exists() }
        val cmd = if (gitBin != null && gitBin != "git")
            listOf(gitBin) + args.toList()
        else
            listOf("/system/bin/sh", "-c", "git ${args.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
        pb.directory(File(cwd))
        pb.environment().apply {
            put("HOME", cwd)
            put("GIT_TERMINAL_PROMPT", "0")
            val pat = System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN") ?: ""
            if (pat.isNotBlank()) put("GIT_ASKPASS", "echo")
        }
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(30, TimeUnit.SECONDS)
        Pair(proc.exitValue(), out.trim())
    } catch (e: Exception) {
        Pair(-1, "git error: ${e.message}")
    }
}

private fun isGitRepo(dir: String) = File(dir, ".git").exists()

// ── Status line parser ─────────────────────────────────────────────────────────
private data class GitFileStatus(val code: String, val file: String) {
    val icon: String get() = when {
        code.startsWith("??") -> "🆕"
        code.startsWith("M ") || code.startsWith(" M") -> "✏️"
        code.startsWith("A ") -> "➕"
        code.startsWith("D ") || code.startsWith(" D") -> "🗑️"
        code.startsWith("R ") -> "🔄"
        code.startsWith("C ") -> "📋"
        else -> "📄"
    }
    val isStaged:   Boolean get() = code.firstOrNull()?.let { it != ' ' && it != '?' } ?: false
    val labelColor: Color   get() = when {
        code.startsWith("??") -> Color(0xFF88AAFF)
        isStaged              -> Color(0xFF44CC88)
        else                  -> Color(0xFFFFAA44)
    }
}

private fun parseGitStatus(raw: String): List<GitFileStatus> =
    raw.lines()
        .filter { it.length > 3 }
        .map { line ->
            val code = line.take(2)
            val file = line.drop(3).trim()
            GitFileStatus(code, file)
        }

// ── Git Panel ─────────────────────────────────────────────────────────────────
@Composable
fun GitPanel(vm: MainViewModel, context: Context) {
    val scope       = rememberCoroutineScope()
    val cwd         = vm.agentWorkingDir

    var statusLines   by remember { mutableStateOf<List<GitFileStatus>>(emptyList()) }
    var branchName    by remember { mutableStateOf("") }
    var diffText      by remember { mutableStateOf("") }
    var logText       by remember { mutableStateOf("") }
    var commitMsg     by remember { mutableStateOf("") }
    var isLoading     by remember { mutableStateOf(false) }
    var activeTab     by remember { mutableStateOf(0) }   // 0=Status 1=Diff 2=Log
    var statusMsg     by remember { mutableStateOf("") }
    var isRepo        by remember { mutableStateOf(false) }

    // Load git info
    fun refresh() {
        if (cwd.isBlank()) return
        scope.launch {
            isLoading = true
            withContext(Dispatchers.IO) {
                isRepo = isGitRepo(cwd)
                if (!isRepo) { isLoading = false; return@withContext }

                val (_, branch) = runGitCmd(cwd, "rev-parse", "--abbrev-ref", "HEAD")
                val (_, status) = runGitCmd(cwd, "status", "--porcelain")
                val (_, diff)   = runGitCmd(cwd, "diff", "HEAD")
                val (_, log)    = runGitCmd(cwd, "log", "--oneline", "--graph", "-15")

                withContext(Dispatchers.Main) {
                    branchName  = branch.ifBlank { "unknown" }
                    statusLines = parseGitStatus(status)
                    diffText    = diff.take(8000)
                    logText     = log
                    isLoading   = false
                }
            }
        }
    }

    fun runOp(label: String, vararg args: String) {
        scope.launch {
            isLoading = true; statusMsg = "⏳ Running: git ${args.joinToString(" ")}…"
            withContext(Dispatchers.IO) {
                val (code, out) = runGitCmd(cwd, *args)
                withContext(Dispatchers.Main) {
                    statusMsg = if (code == 0) "✅ $label: success" else "❌ $label failed: ${out.take(120)}"
                    if (code == 0) Toast.makeText(context, "$label ✓", Toast.LENGTH_SHORT).show()
                    isLoading = false
                    refresh()
                }
            }
        }
    }

    LaunchedEffect(cwd) { refresh() }

    Column(Modifier.fillMaxSize().background(OllamaBg)) {

        // ── Repo header ────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().background(OllamaSurface).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("GIT", color = OllamaGreen, fontSize = 9.sp,
                    letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                if (isRepo) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⎇", color = OllamaGreen, fontSize = 13.sp)
                        Text(branchName, color = OllamaText,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (statusLines.isNotEmpty())
                            Text("${statusLines.size} changed",
                                color = Color(0xFFFFAA44), fontSize = 10.sp)
                    }
                } else {
                    Text("Not a git repository", color = OllamaRed, fontSize = 11.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isRepo) {
                    Button(
                        onClick = { runOp("git init", "init") },
                        colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("Init Repo", color = OllamaBg, fontSize = 11.sp) }
                }
                IconButton(
                    onClick = { refresh() },
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                        .background(OllamaCard)
                ) {
                    if (isLoading)
                        CircularProgressIndicator(
                            color = OllamaGreen,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    else
                        Icon(Icons.Default.Refresh, "Refresh",
                            tint = OllamaGreen, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (statusMsg.isNotBlank()) {
            Text(
                statusMsg,
                color = if (statusMsg.startsWith("✅")) OllamaGreen else OllamaRed,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().background(OllamaCard)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (!isRepo) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🗂️", fontSize = 40.sp)
                    Text("No git repository found", color = OllamaTextDim, fontSize = 14.sp)
                    Text(cwd.substringAfterLast("/"),
                        color = OllamaTextDim.copy(alpha = 0.5f),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Button(
                        onClick = { runOp("git init", "init") },
                        colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen)
                    ) { Text("Initialize Repository", color = OllamaBg, fontWeight = FontWeight.Bold) }
                }
            }
            return
        }

        // ── Tab row: Status / Diff / Log ───────────────────────────────────────
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = OllamaSurface,
            contentColor = OllamaGreen
        ) {
            listOf("📊 Status", "🔍 Diff", "📜 Log").forEachIndexed { i, label ->
                Tab(
                    selected = activeTab == i,
                    onClick = { activeTab = i },
                    text = {
                        Text(label, fontSize = 11.sp,
                            color = if (activeTab == i) OllamaGreen else OllamaTextDim)
                    }
                )
            }
        }

        when (activeTab) {
            // ── STATUS TAB ─────────────────────────────────────────────────────
            0 -> Column(Modifier.fillMaxSize()) {
                // Commit area
                Column(
                    Modifier.fillMaxWidth().background(OllamaCard)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = commitMsg,
                        onValueChange = { commitMsg = it },
                        placeholder = { Text("Commit message…", color = OllamaTextDim, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            color = OllamaText, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OllamaGreen,
                            unfocusedBorderColor = OllamaBorder,
                            cursorColor = OllamaGreen
                        ),
                        maxLines = 2
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { runOp("Stage all", "add", "-A") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1A3A2A)
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, null, tint = OllamaGreen, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stage All", color = OllamaGreen, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                if (commitMsg.isBlank()) {
                                    statusMsg = "❌ Enter a commit message first"
                                    return@Button
                                }
                                runOp("Commit", "commit", "-m", commitMsg)
                                commitMsg = ""
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = OllamaGreen),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Check, null, tint = OllamaBg, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Commit", color = OllamaBg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    statusMsg = "⏳ Pushing…"
                                    withContext(Dispatchers.IO) {
                                        val pat = System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN") ?: ""
                                        val result = if (pat.isNotBlank()) {
                                            val (_, urlOut) = runGitCmd(cwd, "remote", "get-url", "origin")
                                            if (urlOut.contains("github.com")) {
                                                val authedUrl = urlOut.trim()
                                                    .replace(Regex("https://[^@]*@github\\.com/"), "https://github.com/")
                                                    .replace("https://github.com/", "https://x-access-token:$pat@github.com/")
                                                runGitCmd(cwd, "push", authedUrl, "HEAD")
                                            } else runGitCmd(cwd, "push")
                                        } else runGitCmd(cwd, "push")
                                        withContext(Dispatchers.Main) {
                                            val (code, out) = result
                                            statusMsg = if (code == 0) "✅ Pushed successfully"
                                                        else "❌ Push failed: ${out.take(100)}"
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A3A)),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Send, null, tint = OllamaBlue, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Push", color = OllamaBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(color = OllamaBorder)

                if (statusLines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("✅", fontSize = 32.sp)
                            Text("Working tree clean", color = OllamaTextDim, fontSize = 13.sp)
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        items(statusLines) { s ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(s.icon, fontSize = 14.sp)
                                Text(
                                    s.code,
                                    color = s.labelColor,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(s.labelColor.copy(alpha = 0.12f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                                Text(
                                    s.file,
                                    color = OllamaTextDim,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                // Stage / unstage individual file
                                IconButton(
                                    onClick = {
                                        if (s.isStaged) runOp("Unstage", "restore", "--staged", s.file)
                                        else runOp("Stage", "add", s.file)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        if (s.isStaged) Icons.Default.Clear else Icons.Default.Add,
                                        null,
                                        tint = if (s.isStaged) OllamaRed else OllamaGreen,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = OllamaBorder, thickness = 0.5.dp)
                        }
                    }
                }
            }

            // ── DIFF TAB ───────────────────────────────────────────────────────
            1 -> {
                val scrollState = rememberScrollState()
                Box(Modifier.fillMaxSize()) {
                    if (diffText.isBlank()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No diff to show", color = OllamaTextDim, fontSize = 13.sp)
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                            diffText.lines().forEach { line ->
                                val color = when {
                                    line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF44CC88)
                                    line.startsWith("-") && !line.startsWith("---") -> OllamaRed
                                    line.startsWith("@@")                           -> Color(0xFF88AAFF)
                                    line.startsWith("diff ")                        -> OllamaGreen
                                    else                                            -> OllamaTextDim
                                }
                                val bg = when {
                                    line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF44CC88).copy(alpha = 0.07f)
                                    line.startsWith("-") && !line.startsWith("---") -> OllamaRed.copy(alpha = 0.07f)
                                    else -> Color.Transparent
                                }
                                Text(
                                    line,
                                    color = color,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.fillMaxWidth().background(bg)
                                        .padding(horizontal = 8.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── LOG TAB ────────────────────────────────────────────────────────
            2 -> {
                val scrollState = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp)) {
                    if (logText.isBlank()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No commits yet", color = OllamaTextDim, fontSize = 13.sp)
                        }
                    } else {
                        logText.lines().forEach { line ->
                            val isCommit = line.contains(Regex("[0-9a-f]{7}"))
                            Text(
                                line,
                                color = if (isCommit) OllamaText else OllamaGreen,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
