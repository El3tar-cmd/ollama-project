package com.example

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

data class AgentStep(
    val type: String,
    val content: String,
    val isError: Boolean = false
)

class AgentEngine(private val context: Context) {

    private val TAG = "AgentEngine"
    var workingDir: String = context.filesDir.absolutePath

    private val SYSTEM_PROMPT = """
You are a professional AI coding agent running on Android. You have access to tools to complete tasks.

Use tools by responding with JSON blocks like this (one tool per block):
```tool
{"name": "think", "content": "my reasoning here"}
```
```tool
{"name": "list_dir", "path": "/absolute/path"}
```
```tool
{"name": "read_file", "path": "/absolute/path/file.txt"}
```
```tool
{"name": "write_file", "path": "/absolute/path/file.txt", "content": "file content here"}
```
```tool
{"name": "edit_file", "path": "/absolute/path/file.txt", "old": "old text", "new": "new text"}
```
```tool
{"name": "search_files", "dir": "/absolute/path", "query": "search term"}
```
```tool
{"name": "run_command", "args": "list"}
```

Rules:
- Always think before acting
- Working directory: WORKING_DIR
- Use absolute paths
- After completing the task, write a clear summary starting with "## Done"
- If you need information, use tools to get it before answering
""".trimIndent()

    private fun systemPrompt(): String = SYSTEM_PROMPT.replace("WORKING_DIR", workingDir)

    fun parseToolCalls(text: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val regex = Regex("```tool\\s*\\n([\\s\\S]*?)\\n```")
        regex.findAll(text).forEach { match ->
            try {
                val json = JSONObject(match.groupValues[1].trim())
                result.add(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse tool: ${e.message}")
            }
        }
        return result
    }

    suspend fun executeTool(tool: JSONObject): AgentStep = withContext(Dispatchers.IO) {
        val name = tool.optString("name", "")
        return@withContext when (name) {
            "think" -> {
                AgentStep("think", "💭 ${tool.optString("content", "")}")
            }
            "list_dir" -> {
                val path = tool.optString("path", workingDir)
                toolListDir(path)
            }
            "read_file" -> {
                val path = tool.optString("path", "")
                toolReadFile(path)
            }
            "write_file" -> {
                val path = tool.optString("path", "")
                val content = tool.optString("content", "")
                toolWriteFile(path, content)
            }
            "edit_file" -> {
                val path = tool.optString("path", "")
                val old = tool.optString("old", "")
                val new = tool.optString("new", "")
                toolEditFile(path, old, new)
            }
            "search_files" -> {
                val dir = tool.optString("dir", workingDir)
                val query = tool.optString("query", "")
                toolSearchFiles(dir, query)
            }
            "run_command" -> {
                val args = tool.optString("args", "")
                toolRunOllamaCommand(args)
            }
            else -> AgentStep("tool_result", "❌ Unknown tool: $name", isError = true)
        }
    }

    private fun toolListDir(path: String): AgentStep {
        return try {
            val dir = File(path)
            if (!dir.exists()) return AgentStep("tool_result", "❌ Dir not found: $path", isError = true)
            val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
            val sb = StringBuilder("📁 $path\n")
            entries.forEach { f ->
                val prefix = if (f.isDirectory) "📂" else "📄"
                val size = if (f.isFile) " (${formatSize(f.length())})" else ""
                sb.appendLine("  $prefix ${f.name}$size")
            }
            AgentStep("tool_result", sb.toString())
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ Error listing dir: ${e.message}", isError = true)
        }
    }

    private fun toolReadFile(path: String): AgentStep {
        return try {
            val file = File(path)
            if (!file.exists()) return AgentStep("tool_result", "❌ File not found: $path", isError = true)
            if (file.length() > 100_000) return AgentStep("tool_result", "❌ File too large (>${formatSize(file.length())})", isError = true)
            val content = file.readText()
            AgentStep("tool_result", "📄 $path\n```\n$content\n```")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ Error reading: ${e.message}", isError = true)
        }
    }

    private fun toolWriteFile(path: String, content: String): AgentStep {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            AgentStep("tool_result", "✅ Written ${formatSize(file.length())} to $path")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ Error writing: ${e.message}", isError = true)
        }
    }

    private fun toolEditFile(path: String, old: String, new: String): AgentStep {
        return try {
            val file = File(path)
            if (!file.exists()) return AgentStep("tool_result", "❌ File not found: $path", isError = true)
            val content = file.readText()
            if (!content.contains(old)) return AgentStep("tool_result", "❌ Target text not found in file", isError = true)
            file.writeText(content.replace(old, new))
            AgentStep("tool_result", "✅ Edited $path successfully")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ Error editing: ${e.message}", isError = true)
        }
    }

    private fun toolSearchFiles(dir: String, query: String): AgentStep {
        return try {
            val root = File(dir)
            if (!root.exists()) return AgentStep("tool_result", "❌ Dir not found: $dir", isError = true)
            val results = mutableListOf<String>()
            root.walkTopDown().filter { it.isFile }.take(500).forEach { file ->
                if (file.name.contains(query, ignoreCase = true)) {
                    results.add("📄 ${file.absolutePath}")
                } else {
                    try {
                        if (file.length() < 500_000 && file.readText().contains(query, ignoreCase = true)) {
                            results.add("📝 ${file.absolutePath} (content match)")
                        }
                    } catch (_: Exception) {}
                }
            }
            if (results.isEmpty()) {
                AgentStep("tool_result", "🔍 No results for \"$query\" in $dir")
            } else {
                AgentStep("tool_result", "🔍 Found ${results.size} results:\n${results.take(30).joinToString("\n")}")
            }
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ Search error: ${e.message}", isError = true)
        }
    }

    private fun toolRunOllamaCommand(args: String): AgentStep {
        return try {
            val ollamaFile = File(context.filesDir, "bin/ollama")
            if (!ollamaFile.exists()) return AgentStep("tool_result", "❌ Ollama binary not installed", isError = true)
            val cmdArgs = args.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            val pb = ProcessBuilder(listOf(ollamaFile.absolutePath) + cmdArgs)
            pb.environment()["OLLAMA_MODELS"] = File(context.filesDir, "ollama_models").absolutePath
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            AgentStep("tool_result", "⚡ ollama $args\n$output")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ Command error: ${e.message}", isError = true)
        }
    }

    suspend fun runAgentLoop(
        userTask: String,
        model: String,
        baseUrl: String,
        maxSteps: Int = 20,
        onStep: suspend (AgentStep) -> Unit
    ) {
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("system", systemPrompt()))
        messages.add(ChatMessage("user", userTask))

        var steps = 0
        val api = OllamaApi()

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
                        onComplete = { _, _ ->
                            if (!cont.isCompleted) cont.resume(Unit)
                        }
                    )
                }
            } catch (e: Exception) {
                onStep(AgentStep("error", "❌ LLM error: ${e.message}", isError = true))
                break
            }

            if (fullResponse.isBlank()) break

            val toolCalls = parseToolCalls(fullResponse)
            val textWithoutTools = fullResponse.replace(Regex("```tool\\s*\\n[\\s\\S]*?\\n```"), "").trim()

            if (textWithoutTools.isNotBlank()) {
                onStep(AgentStep("assistant", textWithoutTools))
            }

            if (toolCalls.isEmpty()) {
                messages.add(ChatMessage("assistant", fullResponse))
                break
            }

            val toolResults = StringBuilder()
            toolCalls.forEach { toolCall ->
                val toolName = toolCall.optString("name")
                onStep(AgentStep("tool_call", "🔧 Calling: $toolName"))
                val result = executeTool(toolCall)
                onStep(result)
                toolResults.appendLine(result.content)
                toolResults.appendLine("---")
            }

            messages.add(ChatMessage("assistant", fullResponse))
            messages.add(ChatMessage("user", "Tool results:\n$toolResults\n\nContinue with the task."))
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
        }
    }
}
