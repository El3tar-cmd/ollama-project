package com.example

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
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

    // ─── System prompt ────────────────────────────────────────────────────────
    private val SYSTEM_PROMPT = """
You are Devhive Agent — an expert AI coding assistant running on Android via Ollama.
Working directory: WORKING_DIR

IMPORTANT — HOW TO CALL TOOLS:
You MUST call tools by outputting a JSON object, wrapped in a ```tool block.
You can ONLY interact with the system via tool calls. Never describe what you would do — DO IT.

Format (one tool per block):
```tool
{"name": "TOOL_NAME", "key": "value"}
```

═══ THINKING TOOLS ═══
- think:               {"name":"think","content":"step-by-step reasoning before acting"}
- sequential_thinking: {"name":"sequential_thinking","thought":"current reasoning step","step":1,"total_steps":3,"is_final_thought":false,"next_action":"what to do next"}
- plan:                {"name":"plan","steps":["step 1","step 2","step 3"]}
- reflect:             {"name":"reflect","observation":"what happened","adjustment":"what to do differently"}

═══ FILE TOOLS ═══
- list_dir:    {"name":"list_dir","path":"WORKING_DIR"}   (alias: list)
- read_file:   {"name":"read_file","path":"WORKING_DIR/file.txt"}
- read_lines:  {"name":"read_lines","path":"WORKING_DIR/file.txt","start":1,"end":50}
- write_file:  {"name":"write_file","path":"WORKING_DIR/file.txt","content":"full content here"}
- append_file: {"name":"append_file","path":"WORKING_DIR/file.txt","content":"text to append"}
- edit_file:   {"name":"edit_file","path":"WORKING_DIR/file.txt","old":"exact old text","new":"new text"}
- delete_file: {"name":"delete_file","path":"WORKING_DIR/path"}
- move_file:   {"name":"move_file","src":"WORKING_DIR/old","dst":"WORKING_DIR/new"}
- create_dir:  {"name":"create_dir","path":"WORKING_DIR/newdir"}

═══ SEARCH TOOLS ═══
- search_files:{"name":"search_files","dir":"WORKING_DIR","query":"keyword"}  (alias: search)
- grep:        {"name":"grep","dir":"WORKING_DIR","pattern":"regex","glob":"*.py"}

═══ EXECUTION TOOLS ═══
- bash:        {"name":"bash","cmd":"ls -la","cwd":"WORKING_DIR"}
- git:         {"name":"git","args":"status"}
- calculate:   {"name":"calculate","expr":"2 ** 10 + sqrt(144)"}
- fetch_url:   {"name":"fetch_url","url":"https://example.com","method":"GET","body":""}

═══ CONTROL TOOLS ═══
- complete:    {"name":"complete","summary":"concise description of what was accomplished"}

RULES:
1. ALWAYS start complex tasks with plan — lay out all steps first.
2. Use think or sequential_thinking before making non-trivial decisions.
3. Use reflect after unexpected results to adjust strategy.
4. ALL file paths must start with WORKING_DIR — never relative paths.
5. Read a file before editing it.
6. Call complete only when ALL tasks are fully done.
7. If a tool fails, reflect on the error and retry with a fix.
8. NEVER write placeholder/fake content — always real content.
9. ALWAYS output a ```tool block — never just describe what to do.
10. For large files, prefer read_lines with a range (e.g., start:1 end:50) over read_file to avoid huge output.

Platform: Android arm64 | Shell: /system/bin/sh
""".trimIndent()

    private fun systemPrompt() = SYSTEM_PROMPT.replace("WORKING_DIR", workingDir)

    // ─── Tool call parser — handles multiple formats from different models ────
    fun parseToolCalls(text: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seen   = mutableSetOf<String>()

        fun tryAdd(raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isBlank() || trimmed in seen) return
            seen.add(trimmed)
            try {
                val obj = JSONObject(trimmed)
                if (obj.has("name")) result.add(obj)
            } catch (_: Exception) {}
        }

        // Format 1: ```tool\n{...}\n``` (primary)
        Regex("```tool\\s*\\n([\\s\\S]*?)\\n?```").findAll(text).forEach { tryAdd(it.groupValues[1]) }

        // Format 2: ```json\n{...}\n``` with a "name" key (some models use this)
        Regex("```json\\s*\\n([\\s\\S]*?)\\n?```").findAll(text).forEach { m ->
            val raw = m.groupValues[1].trim()
            if (raw.contains("\"name\"")) tryAdd(raw)
        }

        // Format 3: bare fenced block ``` {... } ``` with a "name" key
        Regex("```\\s*\\n(\\{[\\s\\S]*?\\})\\s*\\n?```").findAll(text).forEach { m ->
            val raw = m.groupValues[1].trim()
            if (raw.contains("\"name\"")) tryAdd(raw)
        }

        // Format 4: standalone JSON object on its own line(s) — {"name":"..."}
        // Scan the text line by line accumulating potential JSON blocks
        val lines = text.lines()
        val jsonBuf = StringBuilder()
        var depth = 0
        for (line in lines) {
            for (ch in line) {
                if (ch == '{') depth++
                if (ch == '}') depth--
            }
            if (depth > 0 || jsonBuf.isNotEmpty()) jsonBuf.append(line).append('\n')
            if (depth == 0 && jsonBuf.isNotBlank()) {
                val candidate = jsonBuf.toString().trim()
                if (candidate.contains("\"name\"")) tryAdd(candidate)
                jsonBuf.clear()
            }
        }

        if (result.isEmpty()) {
            Log.d(TAG, "parseToolCalls: no tool calls found in response (${text.length} chars)")
        }
        return result
    }

    // ─── Tool dispatcher ─────────────────────────────────────────────────────
    suspend fun executeTool(tool: JSONObject): AgentStep = withContext(Dispatchers.IO) {
        when (val name = tool.optString("name", "")) {
            // ── Thinking tools ──
            "think"       -> AgentStep("think",
                "💭 ${tool.optString("content", "")}")
            "sequential_thinking" -> {
                val step       = tool.optInt("step", 0)
                val total      = tool.optInt("total_steps", 0)
                val thought    = tool.optString("thought", "")
                val isFinal    = tool.optBoolean("is_final_thought", false)
                val nextAction = tool.optString("next_action", "")
                val header     = if (total > 0) "🧠 Step $step/$total" else "🧠 Thinking"
                val body       = if (!isFinal && nextAction.isNotBlank()) "$thought\n→ Next: $nextAction"
                                 else if (isFinal) "$thought\n✅ Final thought"
                                 else thought
                AgentStep("think", "$header\n$body")
            }
            "plan"        -> toolPlan(tool.optJSONArray("steps"))
            "reflect"     -> AgentStep("think",
                "🔍 Observation: ${tool.optString("observation","")}\n" +
                "🔄 Adjustment: ${tool.optString("adjustment","")}")
            // ── File tools (with aliases) ──
            "list_dir", "list"
                          -> toolListDir(tool.optString("path", tool.optString("dir", workingDir)))
            "read_file"   -> toolReadFile(tool.optString("path", ""))
            "read_lines"  -> toolReadLines(tool.optString("path", ""), tool.optInt("start", 1), tool.optInt("end", 50))
            "write_file"  -> toolWriteFile(tool.optString("path", ""), tool.optString("content", ""))
            "append_file" -> toolAppendFile(tool.optString("path", ""), tool.optString("content", ""))
            "edit_file"   -> toolEditFile(tool.optString("path", ""), tool.optString("old", ""), tool.optString("new", ""))
            "delete_file" -> toolDeleteFile(tool.optString("path", ""))
            "move_file"   -> toolMoveFile(tool.optString("src", ""), tool.optString("dst", ""))
            "create_dir"  -> toolCreateDir(tool.optString("path", ""))
            // ── Search tools (with aliases) ──
            "search_files", "search"
                          -> toolSearchFiles(tool.optString("dir", tool.optString("path", workingDir)), tool.optString("query", ""))
            "grep"        -> toolGrep(
                tool.optString("dir", workingDir),
                tool.optString("pattern", ""),
                tool.optString("glob", ""))
            // ── Execution tools ──
            "bash"        -> toolBash(tool.optString("cmd", ""), tool.optString("cwd", workingDir))
            "git"         -> toolBash("git ${tool.optString("args","status")}", workingDir)
            "calculate"   -> toolCalculate(tool.optString("expr", ""))
            "run_command" -> toolRunOllamaCommand(tool.optString("args", ""))
            "fetch_url"   -> toolFetchUrl(tool.optString("url", ""), tool.optString("method", "GET"), tool.optString("body", ""))
            // ── Control ──
            "complete"    -> AgentStep("complete", "✅ ${tool.optString("summary", "Task completed.")}")
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

    private fun toolReadLines(path: String, start: Int, end: Int): AgentStep {
        return try {
            val file = File(path)
            if (!file.exists()) return AgentStep("tool_result", "❌ Not found: $path", isError = true)
            val lines = file.readLines()
            val total = lines.size
            val s = (start - 1).coerceIn(0, total)
            val e = end.coerceIn(s, total)
            val slice = lines.subList(s, e)
            val out = slice.mapIndexed { i, l -> "${s + i + 1}: $l" }.joinToString("\n")
            AgentStep("tool_result", "📄 $path (lines $start–${s + slice.size} of $total)\n$out")
        } catch (ex: Exception) {
            AgentStep("tool_result", "❌ read_lines error: ${ex.message}", isError = true)
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

    // ─── New tool implementations ─────────────────────────────────────────────

    private fun toolPlan(stepsArr: org.json.JSONArray?): AgentStep {
        if (stepsArr == null || stepsArr.length() == 0)
            return AgentStep("think", "📋 Plan: (empty)", isError = false)
        val sb = StringBuilder("📋 Plan (${stepsArr.length()} steps)\n")
        for (i in 0 until stepsArr.length()) {
            sb.appendLine("  ${i + 1}. ${stepsArr.optString(i)}")
        }
        return AgentStep("think", sb.toString().trimEnd())
    }

    private fun toolGrep(dir: String, pattern: String, glob: String): AgentStep {
        return try {
            if (pattern.isBlank()) return AgentStep("tool_result", "❌ grep: empty pattern", isError = true)
            val root = File(dir)
            if (!root.exists()) return AgentStep("tool_result", "❌ Dir not found: $dir", isError = true)
            val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) }
                        catch (_: Exception) { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
            val results = mutableListOf<String>()
            val globRegex = if (glob.isNotBlank())
                Regex("^" + glob.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$")
            else null

            root.walkTopDown()
                .filter { it.isFile && (globRegex == null || globRegex.matches(it.name)) }
                .take(500)
                .forEach { file ->
                    if (file.length() > 2_000_000) return@forEach
                    try {
                        file.readLines().forEachIndexed { idx, line ->
                            if (regex.containsMatchIn(line)) {
                                results.add("${file.absolutePath}:${idx + 1}: ${line.trim().take(120)}")
                                if (results.size >= 100) return@forEach
                            }
                        }
                    } catch (_: Exception) {}
                }
            if (results.isEmpty())
                AgentStep("tool_result", "🔍 grep: no matches for /$pattern/ in $dir")
            else
                AgentStep("tool_result", "🔍 grep: ${results.size} match(es)\n${results.joinToString("\n")}")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ grep error: ${e.message}", isError = true)
        }
    }

    private fun toolCalculate(expr: String): AgentStep {
        return try {
            if (expr.isBlank()) return AgentStep("tool_result", "❌ calculate: empty expression", isError = true)
            // Safe evaluator — supports +,-,*,/,**,sqrt,abs,floor,ceil,round,mod,%
            val result = evalExpr(expr.trim())
            AgentStep("tool_result", "🔢 $expr = $result")
        } catch (e: Exception) {
            AgentStep("tool_result", "❌ calculate error: ${e.message}\nExpression: $expr", isError = true)
        }
    }

    /** Minimal safe math expression evaluator (no eval, no JS engine). */
    private fun evalExpr(expr: String): String {
        val e = expr
            .replace("\\s".toRegex(), "")
            .replace("sqrt(", "§SQRT§")
            .replace("abs(", "§ABS§")
            .replace("floor(", "§FLOOR§")
            .replace("ceil(", "§CEIL§")
            .replace("round(", "§ROUND§")
            .replace("**", "^")
        val result = evalTokens(e)
        return if (result == result.toLong().toDouble()) result.toLong().toString()
               else "%.10g".format(result).trimEnd('0').trimEnd('.')
    }

    private fun evalTokens(expr: String): Double {
        var e = expr
        // Handle functions
        fun applyFn(tag: String, fn: (Double) -> Double): String {
            while (e.contains(tag)) {
                val idx = e.indexOf(tag)
                var depth = 1; var i = idx + tag.length
                while (i < e.length && depth > 0) {
                    if (e[i] == '(') depth++ else if (e[i] == ')') depth--
                    i++
                }
                val inner = e.substring(idx + tag.length, i - 1)
                val value  = fn(evalTokens(inner))
                e = e.substring(0, idx) + value + e.substring(i)
            }
            return e
        }
        e = applyFn("§SQRT§",  { v -> kotlin.math.sqrt(v) })
        e = applyFn("§ABS§",   { v -> kotlin.math.abs(v) })
        e = applyFn("§FLOOR§", { v -> kotlin.math.floor(v) })
        e = applyFn("§CEIL§",  { v -> kotlin.math.ceil(v) })
        e = applyFn("§ROUND§", { v -> kotlin.math.round(v).toDouble() })

        // Resolve parentheses
        while (e.contains('(')) {
            val last = e.lastIndexOf('(')
            val close = e.indexOf(')', last)
            if (close == -1) break
            val inner = e.substring(last + 1, close)
            e = e.substring(0, last) + evalTokens(inner) + e.substring(close + 1)
        }

        // Tokenize and compute with operator precedence
        val tokens = Regex("(?<=[0-9.)])(?=[+\\-*/^%])|(?<=[+\\-*/^%])(?=[0-9.(])")
            .split(e).filter { it.isNotBlank() }

        // Flatten into numbers and ops
        val nums = mutableListOf<Double>()
        val ops  = mutableListOf<Char>()
        var i = 0
        while (i < e.length) {
            val c = e[i]
            if (c.isDigit() || c == '.' || (c == '-' && (i == 0 || e[i-1] in "+-*/^%"))) {
                var j = i + 1
                while (j < e.length && (e[j].isDigit() || e[j] == '.')) j++
                nums.add(e.substring(i, j).toDouble())
                i = j
            } else if (c in "+-*/^%") {
                ops.add(c); i++
            } else i++
        }
        if (nums.isEmpty()) return e.toDoubleOrNull() ?: throw IllegalArgumentException("Cannot parse: $e")

        // Apply ^ first
        var idx = ops.indexOf('^')
        while (idx != -1) {
            nums[idx] = Math.pow(nums[idx], nums[idx + 1])
            nums.removeAt(idx + 1); ops.removeAt(idx)
            idx = ops.indexOf('^')
        }
        // Then * / %
        idx = ops.indexOfFirst { it in "*/%" }
        while (idx != -1) {
            nums[idx] = when (ops[idx]) {
                '*' -> nums[idx] * nums[idx + 1]
                '/' -> nums[idx] / nums[idx + 1]
                else -> nums[idx] % nums[idx + 1]
            }
            nums.removeAt(idx + 1); ops.removeAt(idx)
            idx = ops.indexOfFirst { it in "*/%" }
        }
        // Then + -
        var result = nums[0]
        for (k in ops.indices) {
            result = if (ops[k] == '+') result + nums[k + 1] else result - nums[k + 1]
        }
        return result
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

    private val fetchClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun toolFetchUrl(urlStr: String, method: String, body: String): AgentStep {
        return try {
            if (urlStr.isBlank()) return AgentStep("tool_result", "❌ fetch_url: empty URL", isError = true)

            val mth = method.uppercase().ifBlank { if (body.isNotBlank()) "POST" else "GET" }
            val reqBody = when {
                mth == "GET" || mth == "HEAD" -> null
                body.isNotBlank() -> body.toByteArray().toRequestBody(
                    if (body.trimStart().startsWith("{") || body.trimStart().startsWith("["))
                        "application/json".toMediaType()
                    else
                        "text/plain".toMediaType()
                )
                else -> ByteArray(0).toRequestBody("text/plain".toMediaType())
            }

            val req = Request.Builder()
                .url(urlStr)
                .method(mth, reqBody)
                .header("User-Agent", "Mozilla/5.0 OllamaDevhive/1.0")
                .header("Accept", "text/html,application/json,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            fetchClient.newCall(req).execute().use { resp ->
                val code     = resp.code
                val ctype    = resp.header("Content-Type", "")
                val respBody = resp.body?.string()?.take(4000) ?: "(empty body)"
                AgentStep("tool_result", "🌐 $mth $urlStr\n→ HTTP $code  |  $ctype\n\n$respBody".trimEnd())
            }
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
        var consecutiveErrors = 0
        val MAX_CONSECUTIVE_ERRORS = 3

        while (steps < maxSteps) {
            steps++
            var fullResponse = ""
            var streamError  = ""
            var streamOk     = true

            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val call = api.chatStream(
                        baseUrl        = baseUrl,
                        modelName      = model,
                        messages       = messages,
                        onTokenGenerated = { token -> fullResponse += token },
                        onComplete     = { success, msg ->
                            if (!success) { streamOk = false; streamError = msg }
                            if (!cont.isCompleted) cont.resume(Unit)
                        }
                    )
                    cont.invokeOnCancellation { call.cancel() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // let coroutine cancellation propagate normally
            } catch (e: Exception) {
                onStep(AgentStep("error", "❌ LLM connection error: ${e.message}", isError = true))
                break
            }

            // Stream returned an error (e.g. HTTP 500, timeout, connection refused)
            if (!streamOk) {
                consecutiveErrors++
                onStep(AgentStep("error", "❌ LLM error: $streamError", isError = true))
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    onStep(AgentStep("info", "⚠️ Too many consecutive errors. Is the daemon running?"))
                    break
                }
                // Short wait before retrying
                kotlinx.coroutines.delay(1000)
                continue
            }

            // Empty response — model sent nothing
            if (fullResponse.isBlank()) {
                consecutiveErrors++
                onStep(AgentStep("error", "❌ Model returned an empty response.", isError = true))
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) break
                kotlinx.coroutines.delay(800)
                continue
            }

            consecutiveErrors = 0  // reset on successful response

            val toolCalls = parseToolCalls(fullResponse)

            // Strip all recognised tool block formats for display
            val textOnly = fullResponse
                .replace(Regex("```tool\\s*\\n[\\s\\S]*?\\n?```"), "")
                .replace(Regex("```json\\s*\\n[\\s\\S]*?\\n?```"), "")
                .replace(Regex("```\\s*\\n\\{[\\s\\S]*?\\}\\s*\\n?```"), "")
                .trim()

            if (textOnly.isNotBlank()) {
                onStep(AgentStep("assistant", textOnly))
            }

            // Model gave only text — nudge it to call a tool (up to 2 times)
            if (toolCalls.isEmpty()) {
                messages.add(ChatMessage("assistant", fullResponse))
                val noToolTries = messages.count { it.role == "user" && it.content.startsWith("REMINDER") }
                if (noToolTries < 2) {
                    val reminder = "REMINDER: You must call a tool using a ```tool block. " +
                        "Do not describe actions in plain text — output a tool call JSON block now. " +
                        "If the task is complete, call: {\"name\":\"complete\",\"summary\":\"what was done\"}"
                    messages.add(ChatMessage("user", reminder))
                    continue
                }
                // Two reminders ignored — model is finished
                break
            }

            // Execute all tool calls
            val toolResults = StringBuilder()
            var taskComplete = false
            toolCalls.forEach { toolCall ->
                val toolName = toolCall.optString("name", "?")
                onStep(AgentStep("tool_call", "🔧 $toolName"))
                val result = executeTool(toolCall)
                onStep(result)
                toolResults.appendLine(result.content)
                toolResults.appendLine("---")
                if (result.type == "complete") taskComplete = true
            }

            messages.add(ChatMessage("assistant", fullResponse))
            if (taskComplete) break
            messages.add(ChatMessage("user", "Tool results:\n$toolResults\nContinue with the next step. Use a ```tool block."))
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
