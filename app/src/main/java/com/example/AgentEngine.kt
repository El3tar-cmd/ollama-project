package com.example

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class AgentStep(
    val type: String,
    val content: String,
    val isError: Boolean = false
)

class AgentEngine(private val context: Context) {

    private val TAG = "AgentEngine"
    var workingDir: String = context.filesDir.absolutePath

    // ─── System prompt — works with any model (qwen, phi, llama, gemma…) ───
    private val SYSTEM_PROMPT = """
You are Devhive Agent — an expert AI coding assistant running on Android via Ollama.

## How to use tools
Write ONE JSON tool block per action, inside triple-backtick markers:
```tool
{"name": "TOOL_NAME", "param1": "value1", "param2": "value2"}
```

## Available tools

### File system
```tool
{"name": "list_dir", "path": "/absolute/path"}
```
```tool
{"name": "read_file", "path": "/absolute/path/file.txt"}
```
```tool
{"name": "write_file", "path": "/absolute/path/file.txt", "content": "full file content"}
```
```tool
{"name": "append_file", "path": "/absolute/path/file.txt", "content": "text to append"}
```
```tool
{"name": "edit_file", "path": "/absolute/path/file.txt", "old": "exact text to replace", "new": "replacement text"}
```
```tool
{"name": "delete_file", "path": "/absolute/path/file_or_dir"}
```
```tool
{"name": "move_file", "src": "/absolute/path/old.txt", "dst": "/absolute/path/new.txt"}
```
```tool
{"name": "create_dir", "path": "/absolute/path/new_dir"}
```
```tool
{"name": "search_files", "dir": "/absolute/path", "query": "search term"}
```

### Shell execution
```tool
{"name": "bash", "cmd": "ls -la && echo done"}
```
```tool
{"name": "run_command", "args": "list"}
```

### Network
```tool
{"name": "fetch_url", "url": "https://example.com/api", "method": "GET"}
```

### Reasoning
```tool
{"name": "think", "content": "my reasoning before acting"}
```

## Rules
1. ALWAYS think before acting
2. ALWAYS use absolute paths (working dir: WORKING_DIR)
3. Read files before editing them
4. After finishing ALL steps, write a clear summary starting with "## Done"
5. If a tool fails, adjust your approach and try again
6. Never invent file contents — use read_file first

## Environment
- Working directory: WORKING_DIR
- Platform: Android (arm64)
- Shell: /system/bin/sh
""".trimIndent()

    private fun systemPrompt() = SYSTEM_PROMPT.replace("WORKING_DIR", workingDir)

    // ─── Tool call parser ────────────────────────────────────────────────────
    fun parseToolCalls(text: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val regex = Regex("```tool\\s*\\n([\\s\\S]*?)\\n```")
        regex.findAll(text).forEach { match ->
            try {
                result.add(JSONObject(match.groupValues[1].trim()))
            } catch (e: Exception) {
                Log.w(TAG, "Bad tool JSON: ${e.message}")
            }
        }
        return result
    }

    // ─── Tool dispatcher ─────────────────────────────────────────────────────
    suspend fun executeTool(tool: JSONObject): AgentStep = withContext(Dispatchers.IO) {
        when (val name = tool.optString("name", "")) {
            "think"       -> AgentStep("think", "💭 ${tool.optString("content", "")}")
            "list_dir"    -> toolListDir(tool.optString("path", workingDir))
            "read_file"   -> toolReadFile(tool.optString("path", ""))
            "write_file"  -> toolWriteFile(tool.optString("path", ""), tool.optString("content", ""))
            "append_file" -> toolAppendFile(tool.optString("path", ""), tool.optString("content", ""))
            "edit_file"   -> toolEditFile(tool.optString("path", ""), tool.optString("old", ""), tool.optString("new", ""))
            "delete_file" -> toolDeleteFile(tool.optString("path", ""))
            "move_file"   -> toolMoveFile(tool.optString("src", ""), tool.optString("dst", ""))
            "create_dir"  -> toolCreateDir(tool.optString("path", ""))
            "search_files"-> toolSearchFiles(tool.optString("dir", workingDir), tool.optString("query", ""))
            "bash"        -> toolBash(tool.optString("cmd", ""), tool.optString("cwd", workingDir))
            "run_command" -> toolRunOllamaCommand(tool.optString("args", ""))
            "fetch_url"   -> toolFetchUrl(tool.optString("url", ""), tool.optString("method", "GET"), tool.optString("body", ""))
            else          -> AgentStep("tool_result", "❌ Unknown tool: $name", isError = true)
        }
    }

    // ─── Tool implementations ─────────────────────────────────────────────────

    private fun toolListDir(path: String): AgentStep {
        return try {
            val dir = File(path)
            if (!dir.exists()) return AgentStep("tool_result", "❌ Not found: $path", isError = true)
            val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
            val sb = StringBuilder("📁 $path (${entries.size} entries)\n")
            entries.forEach { f ->
                val prefix = if (f.isDirectory) "📂" else "📄"
                val extra  = if (f.isFile) "  ${formatSize(f.length())}" else "/"
                sb.appendLine("  $prefix ${f.name}$extra")
            }
            AgentStep("tool_result", sb.toString().trimEnd())
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ list_dir error: ${e.message}", isError = true)
        }
    }

    private fun toolReadFile(path: String): AgentStep {
        return try {
            val file = File(path)
            if (!file.exists()) return AgentStep("tool_result", "❌ Not found: $path", isError = true)
            val limit = 500_000L
            if (file.length() > limit) return AgentStep("tool_result", "❌ File too large (${formatSize(file.length())}). Max: ${formatSize(limit)}", isError = true)
            val content = file.readText()
            val lines   = content.lines().size
            AgentStep("tool_result", "📄 $path ($lines lines, ${formatSize(file.length())})\n```\n$content\n```")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ read_file error: ${e.message}", isError = true)
        }
    }

    private fun toolWriteFile(path: String, content: String): AgentStep {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            AgentStep("tool_result", "✅ Wrote ${formatSize(file.length())} to $path (${content.lines().size} lines)")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ write_file error: ${e.message}", isError = true)
        }
    }

    private fun toolAppendFile(path: String, content: String): AgentStep {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.appendText(content)
            AgentStep("tool_result", "✅ Appended ${content.length} chars to $path")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ append_file error: ${e.message}", isError = true)
        }
    }

    private fun toolEditFile(path: String, old: String, new: String): AgentStep {
        return try {
            val file = File(path)
            if (!file.exists()) return AgentStep("tool_result", "❌ Not found: $path", isError = true)
            val content = file.readText()
            val count = content.split(old).size - 1
            if (count == 0) return AgentStep("tool_result", "❌ Target text not found in file. Use read_file first.", isError = true)
            file.writeText(content.replace(old, new))
            AgentStep("tool_result", "✅ Replaced $count occurrence(s) in $path")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ edit_file error: ${e.message}", isError = true)
        }
    }

    private fun toolDeleteFile(path: String): AgentStep {
        return try {
            val f = File(path)
            if (!f.exists()) return AgentStep("tool_result", "❌ Not found: $path", isError = true)
            val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
            if (deleted) AgentStep("tool_result", "🗑️ Deleted: $path")
            else AgentStep("tool_result", "❌ Could not delete: $path", isError = true)
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ delete_file error: ${e.message}", isError = true)
        }
    }

    private fun toolMoveFile(src: String, dst: String): AgentStep {
        return try {
            val s = File(src)
            val d = File(dst)
            if (!s.exists()) return AgentStep("tool_result", "❌ Source not found: $src", isError = true)
            d.parentFile?.mkdirs()
            if (s.renameTo(d)) AgentStep("tool_result", "✅ Moved: $src → $dst")
            else {
                s.copyRecursively(d, overwrite = true)
                s.deleteRecursively()
                AgentStep("tool_result", "✅ Moved (copy+delete): $src → $dst")
            }
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ move_file error: ${e.message}", isError = true)
        }
    }

    private fun toolCreateDir(path: String): AgentStep {
        return try {
            val dir = File(path)
            if (dir.exists()) return AgentStep("tool_result", "ℹ️ Already exists: $path")
            if (dir.mkdirs()) AgentStep("tool_result", "✅ Created directory: $path")
            else AgentStep("tool_result", "❌ Could not create directory: $path", isError = true)
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ create_dir error: ${e.message}", isError = true)
        }
    }

    private fun toolSearchFiles(dir: String, query: String): AgentStep {
        return try {
            val root = File(dir)
            if (!root.exists()) return AgentStep("tool_result", "❌ Dir not found: $dir", isError = true)
            val results = mutableListOf<String>()
            root.walkTopDown()
                .filter { it.isFile }
                .take(1000)
                .forEach { file ->
                    when {
                        file.name.contains(query, ignoreCase = true) ->
                            results.add("📄 ${file.absolutePath}")
                        file.length() < 1_000_000 -> {
                            try {
                                if (file.readText().contains(query, ignoreCase = true))
                                    results.add("📝 ${file.absolutePath} (content)")
                            } catch (_: Exception) {}
                        }
                    }
                }
            if (results.isEmpty()) AgentStep("tool_result", "🔍 No matches for \"$query\" in $dir")
            else AgentStep("tool_result", "🔍 Found ${results.size} match(es):\n${results.take(50).joinToString("\n")}")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ search_files error: ${e.message}", isError = true)
        }
    }

    private fun toolBash(cmd: String, cwd: String): AgentStep {
        return try {
            if (cmd.isBlank()) return AgentStep("tool_result", "❌ bash: empty command", isError = true)
            val pb = ProcessBuilder(listOf("/system/bin/sh", "-c", cmd))
            pb.directory(File(if (File(cwd).exists()) cwd else workingDir))
            pb.environment()["HOME"]            = context.filesDir.absolutePath
            pb.environment()["TMPDIR"]          = context.cacheDir.absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            pb.environment()["OLLAMA_MODELS"]   = File(context.filesDir, "ollama_models").absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val timedOut = !proc.waitFor(30, TimeUnit.SECONDS)
            if (timedOut) {
                proc.destroy()
                return AgentStep("tool_result", "⏱️ bash: command timed out after 30s\n${output.take(500)}", isError = true)
            }
            val exitCode = proc.exitValue()
            val icon = if (exitCode == 0) "✅" else "⚠️"
            AgentStep("tool_result", "$icon bash exit=$exitCode\n$ $cmd\n${output.take(4000).trimEnd()}")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ bash error: ${e.message}", isError = true)
        }
    }

    private fun toolRunOllamaCommand(args: String): AgentStep {
        return try {
            val executor = OllamaExecutor(context)
            val binary = File(context.applicationInfo.nativeLibraryDir, "libollama.so")
                .takeIf { it.exists() }
                ?: File(context.filesDir, "bin/ollama")
            if (!binary.exists()) return AgentStep("tool_result", "❌ Ollama binary not found", isError = true)
            val cmdArgs = args.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            val pb = ProcessBuilder(listOf(binary.absolutePath) + cmdArgs)
            pb.environment()["OLLAMA_MODELS"]   = File(context.filesDir, "ollama_models").absolutePath
            pb.environment()["HOME"]            = context.filesDir.absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(60, TimeUnit.SECONDS)
            AgentStep("tool_result", "⚡ ollama $args\n${output.take(2000).trimEnd()}")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ run_command error: ${e.message}", isError = true)
        }
    }

    private fun toolFetchUrl(urlStr: String, method: String, body: String): AgentStep {
        return try {
            if (urlStr.isBlank()) return AgentStep("tool_result", "❌ fetch_url: empty URL", isError = true)
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = 10_000
            conn.readTimeout    = 30_000
            conn.setRequestProperty("User-Agent", "OllamaDevhive/1.0")
            conn.setRequestProperty("Accept", "*/*")
            if (body.isNotEmpty() && method.uppercase() != "GET") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            conn.connect()
            val code = conn.responseCode
            val response = try {
                conn.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader()?.readText() ?: "(no body)"
            }
            conn.disconnect()
            AgentStep("tool_result", "🌐 $method $urlStr → HTTP $code\n${response.take(3000).trimEnd()}")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ fetch_url error: ${e.message}", isError = true)
        }
    }

    // ─── Agent loop ───────────────────────────────────────────────────────────
    suspend fun runAgentLoop(
        userTask: String,
        model: String,
        baseUrl: String,
        maxSteps: Int = 25,
        onStep: suspend (AgentStep) -> Unit
    ) {
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt()))
        messages.add(ChatMessage("user", userTask))

        val api = OllamaApi()
        var steps = 0

        while (steps < maxSteps) {
            steps++
            var fullResponse = ""

            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    api.chatStream(
                        baseUrl = baseUrl,
                        modelName = model,
                        messages = messages,
                        onTokenGenerated = { token -> fullResponse += token },
                        onComplete = { _, _ -> if (!cont.isCompleted) cont.resume(Unit) }
                    )
                }
            } catch (e: Exception) {
                onStep(AgentStep("error", "❌ LLM error: ${e.message}", isError = true))
                break
            }

            if (fullResponse.isBlank()) break

            val toolCalls = parseToolCalls(fullResponse)
            val textOnly  = fullResponse.replace(Regex("```tool\\s*\\n[\\s\\S]*?\\n```"), "").trim()

            if (textOnly.isNotBlank()) {
                onStep(AgentStep("assistant", textOnly))
            }

            // No tool calls → model finished
            if (toolCalls.isEmpty()) {
                messages.add(ChatMessage("assistant", fullResponse))
                break
            }

            // Execute all tool calls
            val toolResults = StringBuilder()
            toolCalls.forEach { toolCall ->
                val toolName = toolCall.optString("name", "?")
                onStep(AgentStep("tool_call", "🔧 $toolName"))
                val result = executeTool(toolCall)
                onStep(result)
                toolResults.appendLine(result.content)
                toolResults.appendLine("---")
            }

            messages.add(ChatMessage("assistant", fullResponse))
            messages.add(ChatMessage("user", "Tool results:\n$toolResults\nContinue."))
        }

        if (steps >= maxSteps) {
            onStep(AgentStep("info", "⚠️ Reached max steps ($maxSteps). Task may be incomplete."))
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024          -> "${bytes}B"
        bytes < 1024 * 1024   -> "${bytes / 1024}KB"
        else                  -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    }
}
