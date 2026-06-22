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
    // Concise enough for 1B models, powerful enough for 70B.
    // Uses two formats: TOOL>> for JSON params, WRITE_FILE>> for raw file content.
    private val SYSTEM_PROMPT = """
You are DevHive Agent — a precise AI coding assistant on Android.
Working directory: {{WD}}

You act ONLY through tool calls. Never describe an action — call the tool.

━━━━━━━━━━━━━━━━━━━━━━━━━━━
FORMAT A — Any tool call:
━━━━━━━━━━━━━━━━━━━━━━━━━━━
TOOL>>
{"name":"bash","cmd":"ls -la","cwd":"{{WD}}"}
<<TOOL

━━━━━━━━━━━━━━━━━━━━━━━━━━━
FORMAT B — Write a file (ALWAYS use this for file content, never JSON write_file):
━━━━━━━━━━━━━━━━━━━━━━━━━━━
WRITE_FILE>>{{WD}}/hello.py
print("hello world")
# any characters allowed here: _ * ` # [ ] { } \
# no JSON escaping needed
<<WRITE_FILE

━━━━━━━━━━━━━━━━━━━━━━━━━━━
TOOLS — use in FORMAT A:
━━━━━━━━━━━━━━━━━━━━━━━━━━━
{"name":"list_dir","path":"{{WD}}"}
{"name":"read_file","path":"{{WD}}/file.txt"}
{"name":"read_lines","path":"{{WD}}/file.txt","start":1,"end":50}
{"name":"append_file","path":"{{WD}}/file.txt","content":"short text only"}
{"name":"edit_file","path":"{{WD}}/file.txt","old":"exact old text","new":"replacement text"}
{"name":"delete_file","path":"{{WD}}/file.txt"}
{"name":"move_file","src":"{{WD}}/a.txt","dst":"{{WD}}/b.txt"}
{"name":"create_dir","path":"{{WD}}/newdir"}
{"name":"bash","cmd":"echo hello","cwd":"{{WD}}"}
{"name":"fetch_url","url":"https://example.com","method":"GET"}
{"name":"search_files","dir":"{{WD}}","query":"keyword"}
{"name":"grep","dir":"{{WD}}","pattern":"regex","glob":"*.py"}
{"name":"think","content":"my step-by-step reasoning"}
{"name":"ask_user","question":"what I need the user to clarify"}
{"name":"web_search","query":"how to do X in kotlin"}
{"name":"memory_save","key":"topic","value":"info to remember"}
{"name":"memory_recall"}
{"name":"complete","summary":"brief description of what was accomplished"}

━━━━━━━━━━━━━━━━━━━━━━━━━━━
RULES:
━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. Every response MUST end with a TOOL>> block OR a WRITE_FILE>> block.
2. NEVER write file content in plain text — always use WRITE_FILE>> format.
3. WRITE_FILE>> replaces write_file JSON tool — never use write_file in TOOL>>.
4. append_file is only for SHORT additions (< 3 lines). Longer? Use WRITE_FILE>>.
5. Read a file before editing or appending it.
6. Use absolute paths starting with {{WD}}.
7. One tool call per response. Do the most impactful step first.
8. Call complete when all tasks are fully done.
9. If a tool fails, use think to diagnose, then retry differently.

Platform: Android arm64 · Shell: /system/bin/sh
""".trimIndent()

    private fun systemPrompt(): String {
        val base = SYSTEM_PROMPT.replace("{{WD}}", workingDir)
        val mem  = File(workingDir, "agent_memory.md")
        return if (mem.exists()) {
            val txt = mem.readText().trim().take(1500)
            "$base\n\n=== YOUR MEMORY ===\n$txt\n=== END MEMORY ==="
        } else base
    }

    // ─── Parser ───────────────────────────────────────────────────────────────
    /**
     * Extracts tool calls from model output.
     *
     * Priority order:
     *   1. WRITE_FILE>>path\ncontent\n<<WRITE_FILE  — verbatim file content
     *   2. TOOL>>\n{json}\n<<TOOL                   — primary JSON format
     *   3. ```tool\n{json}\n```                      — legacy fallback
     *   4. ```json\n{"name":...}\n```               — legacy fallback
     *   5. Bare balanced JSON objects with "name"   — last-resort fallback
     */
    fun parseToolCalls(text: String): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        val seen    = mutableSetOf<String>()

        fun add(obj: JSONObject) {
            val key = obj.optString("name") + "|" + obj.toString()
            if (key !in seen) { seen.add(key); results.add(obj) }
        }

        // 1. WRITE_FILE>> path \n content \n <<WRITE_FILE
        Regex("""WRITE_FILE>>([^\n]+)\n([\s\S]*?)<<WRITE_FILE""")
            .findAll(text).forEach { m ->
                val path    = m.groupValues[1].trim()
                var content = m.groupValues[2]
                // strip one trailing newline that the model adds before <<WRITE_FILE
                if (content.endsWith("\n")) content = content.dropLast(1)
                if (path.isNotBlank()) {
                    add(JSONObject().apply {
                        put("name",    "write_file")
                        put("path",    path)
                        put("content", content)
                    })
                }
            }

        // 2. TOOL>> \n {json} \n <<TOOL
        Regex("""TOOL>>\s*\n([\s\S]*?)\n?<<TOOL""")
            .findAll(text).forEach { m ->
                val raw = m.groupValues[1].trim()
                if (raw.contains("\"name\""))
                    try { add(JSONObject(raw)) } catch (_: Exception) {}
            }

        // 3. ```tool \n {json} \n ```
        Regex("""```tool\s*\n([\s\S]*?)\n?```""")
            .findAll(text).forEach { m ->
                val raw = m.groupValues[1].trim()
                if (raw.contains("\"name\""))
                    try { add(JSONObject(raw)) } catch (_: Exception) {}
            }

        // 4. ```json \n {"name":...} \n ```
        Regex("""```json\s*\n([\s\S]*?)\n?```""")
            .findAll(text).forEach { m ->
                val raw = m.groupValues[1].trim()
                if (raw.contains("\"name\""))
                    try { add(JSONObject(raw)) } catch (_: Exception) {}
            }

        // 5. Bare balanced JSON objects — only when nothing else found
        if (results.isEmpty()) {
            val buf   = StringBuilder()
            var depth = 0
            for (ch in text) {
                when (ch) {
                    '{' -> { depth++; buf.append(ch) }
                    '}' -> {
                        buf.append(ch)
                        if (--depth == 0 && buf.isNotBlank()) {
                            val s = buf.toString().trim()
                            if (s.contains("\"name\""))
                                try { add(JSONObject(s)) } catch (_: Exception) {}
                            buf.clear()
                        }
                    }
                    else -> if (depth > 0) buf.append(ch)
                }
            }
        }

        if (results.isEmpty())
            Log.d(TAG, "parseToolCalls: no tool calls found (${text.length} chars preview: ${text.take(120)})")
        return results
    }

    // ─── Tool dispatcher ──────────────────────────────────────────────────────
    suspend fun executeTool(tool: JSONObject): AgentStep = withContext(Dispatchers.IO) {
        when (val name = tool.optString("name", "")) {

            // Thinking
            "think"  -> AgentStep("think", "💭 ${tool.optString("content")}")
            "plan"   -> {
                val arr = tool.optJSONArray("steps")
                val sb  = StringBuilder("📋 Plan\n")
                if (arr != null) for (i in 0 until arr.length()) sb.appendLine("  ${i+1}. ${arr.optString(i)}")
                AgentStep("think", sb.trimEnd().toString())
            }

            // File tools
            "list_dir", "list"
                     -> toolListDir(tool.optString("path", workingDir))
            "read_file"
                     -> toolReadFile(tool.optString("path", ""))
            "read_lines"
                     -> toolReadLines(tool.optString("path",""), tool.optInt("start",1), tool.optInt("end",50))
            "write_file"
                     -> toolWriteFile(tool.optString("path",""), tool.optString("content",""))
            "append_file"
                     -> toolAppendFile(tool.optString("path",""), tool.optString("content",""))
            "edit_file"
                     -> toolEditFile(tool.optString("path",""), tool.optString("old",""), tool.optString("new",""))
            "delete_file"
                     -> toolDeleteFile(tool.optString("path",""))
            "move_file"
                     -> toolMoveFile(tool.optString("src",""), tool.optString("dst",""))
            "create_dir"
                     -> toolCreateDir(tool.optString("path",""))

            // Execution
            "bash"          -> toolBash(tool.optString("cmd",""), tool.optString("cwd", workingDir))
            "git"           -> toolBash("git ${tool.optString("args","status")}", workingDir)
            "fetch_url"     -> toolFetchUrl(tool.optString("url",""), tool.optString("method","GET"), tool.optString("body",""))
            "calculate"     -> toolCalculate(tool.optString("expr",""))

            // Search
            "search_files", "search"
                            -> toolSearchFiles(
                                tool.optString("dir", tool.optString("path", workingDir)),
                                tool.optString("query","")
                            )
            "grep"          -> toolGrep(
                                tool.optString("dir", workingDir),
                                tool.optString("pattern",""),
                                tool.optString("glob","")
                            )
            "web_search"    -> toolWebSearch(tool.optString("query",""))

            // Memory
            "memory_save"   -> toolMemorySave(
                                tool.optString("key","note").trim()
                                    .replace(Regex("[^a-zA-Z0-9_\\- ]"), ""),
                                tool.optString("value","")
                            )
            "memory_recall" -> toolMemoryRecall()
            "memory_clear"  -> toolMemoryClear(tool.optString("key",""))

            // Interaction
            "ask_user" -> {
                val q  = tool.optString("question", "What should I do next?")
                val fn = askUserFn
                if (fn != null) AgentStep("tool_result", "💬 User replied: ${fn(q)}")
                else AgentStep("tool_result", "ℹ️ No user available — continuing.")
            }

            // Control
            "complete" -> {
                val summary = tool.optString("summary", "Task complete.")
                recordTaskDone(summary)
                AgentStep("complete", "✅ $summary")
            }

            else -> AgentStep("tool_result", "❌ Unknown tool: \"$name\"", isError = true)
        }
    }

    // ─── File tools ───────────────────────────────────────────────────────────

    private fun toolListDir(path: String): AgentStep {
        val dir = File(path.ifBlank { workingDir })
        if (!dir.exists()) return err("Not found: $path")
        val entries = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: emptyList()
        val sb = StringBuilder("📁 $path (${entries.size} items)\n")
        entries.forEach { f ->
            val tag   = if (f.isDirectory) "📂" else "📄"
            val extra = if (f.isFile) "  ${sz(f.length())}" else "/"
            sb.appendLine("  $tag ${f.name}$extra")
        }
        return ok(sb.trimEnd().toString())
    }

    private fun toolReadFile(path: String): AgentStep {
        if (path.isBlank()) return err("read_file: path required")
        val f = File(path)
        if (!f.exists()) return err("Not found: $path")
        if (f.length() > 400_000L) return err("File too large (${sz(f.length())}). Use read_lines.")
        val text = f.readText()
        return ok("📄 $path (${text.lines().size} lines)\n$text")
    }

    private fun toolReadLines(path: String, start: Int, end: Int): AgentStep {
        if (path.isBlank()) return err("read_lines: path required")
        val f = File(path)
        if (!f.exists()) return err("Not found: $path")
        val lines = f.readLines()
        val total = lines.size
        val s     = (start - 1).coerceIn(0, total)
        val e     = end.coerceIn(s, total)
        val slice = lines.subList(s, e)
        val out   = slice.mapIndexed { i, l -> "${s + i + 1}: $l" }.joinToString("\n")
        return ok("📄 $path lines $start–${s + slice.size}/$total\n$out")
    }

    private fun toolWriteFile(path: String, content: String): AgentStep {
        if (path.isBlank()) return err("write_file: path required")
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            ok("✅ Wrote ${sz(f.length())} → $path (${content.lines().size} lines)")
        } catch (e: Exception) { err("write_file: ${e.message}") }
    }

    private fun toolAppendFile(path: String, content: String): AgentStep {
        if (path.isBlank()) return err("append_file: path required")
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.appendText(content)
            ok("✅ Appended ${content.length} chars → $path")
        } catch (e: Exception) { err("append_file: ${e.message}") }
    }

    private fun toolEditFile(path: String, old: String, new: String): AgentStep {
        if (path.isBlank() || old.isBlank()) return err("edit_file: path and old required")
        return try {
            val f = File(path)
            if (!f.exists()) return err("Not found: $path")
            val text  = f.readText()
            val count = text.split(old).size - 1
            if (count == 0) return err("edit_file: text not found — use read_file to verify exact content")
            f.writeText(text.replace(old, new))
            ok("✅ Replaced $count occurrence(s) in $path")
        } catch (e: Exception) { err("edit_file: ${e.message}") }
    }

    private fun toolDeleteFile(path: String): AgentStep {
        if (path.isBlank()) return err("delete_file: path required")
        val f = File(path)
        if (!f.exists()) return err("Not found: $path")
        val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (deleted) ok("🗑️ Deleted: $path")
        else err("Could not delete: $path")
    }

    private fun toolMoveFile(src: String, dst: String): AgentStep {
        if (src.isBlank() || dst.isBlank()) return err("move_file: src and dst required")
        val s = File(src); val d = File(dst)
        if (!s.exists()) return err("Source not found: $src")
        d.parentFile?.mkdirs()
        return try {
            if (s.renameTo(d)) ok("✅ Moved: $src → $dst")
            else { s.copyRecursively(d, overwrite = true); s.deleteRecursively(); ok("✅ Moved: $src → $dst") }
        } catch (e: Exception) { err("move_file: ${e.message}") }
    }

    private fun toolCreateDir(path: String): AgentStep {
        if (path.isBlank()) return err("create_dir: path required")
        val d = File(path)
        if (d.exists()) return ok("ℹ️ Already exists: $path")
        return if (d.mkdirs()) ok("✅ Created: $path")
        else err("Could not create: $path")
    }

    // ─── Execution tools ──────────────────────────────────────────────────────

    private fun toolBash(cmd: String, cwd: String): AgentStep {
        if (cmd.isBlank()) return err("bash: cmd required")
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.directory(File(if (File(cwd).exists()) cwd else workingDir))
            pb.environment().apply {
                put("HOME",            context.filesDir.absolutePath)
                put("TMPDIR",          context.cacheDir.absolutePath)
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                put("OLLAMA_MODELS",   File(context.filesDir, "ollama_models").absolutePath)
            }
            pb.redirectErrorStream(true)
            val proc    = pb.start()
            val output  = proc.inputStream.bufferedReader().readText()
            val timeout = !proc.waitFor(30, TimeUnit.SECONDS)
            if (timeout) { proc.destroy(); return err("bash: timed out\n${output.take(300)}") }
            val icon = if (proc.exitValue() == 0) "✅" else "⚠️"
            ok("$icon exit=${proc.exitValue()}\n\$ $cmd\n${output.take(3000).trimEnd()}")
        } catch (e: Exception) { err("bash: ${e.message}") }
    }

    private val fetchClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun toolFetchUrl(url: String, method: String, body: String): AgentStep {
        if (url.isBlank()) return err("fetch_url: url required")
        return try {
            val mth = method.uppercase().ifBlank { if (body.isNotBlank()) "POST" else "GET" }
            val reqBody = when {
                mth == "GET" || mth == "HEAD" -> null
                body.isNotBlank() -> body.toByteArray().toRequestBody(
                    if (body.trimStart().startsWith("{") || body.trimStart().startsWith("["))
                        "application/json".toMediaType()
                    else "text/plain".toMediaType()
                )
                else -> ByteArray(0).toRequestBody("text/plain".toMediaType())
            }
            fetchClient.newCall(
                Request.Builder().url(url).method(mth, reqBody)
                    .header("User-Agent", "Mozilla/5.0 DevHiveIDE/1.0")
                    .header("Accept", "*/*")
                    .build()
            ).execute().use { resp ->
                ok("🌐 HTTP ${resp.code} $mth $url\n${resp.body?.string()?.take(3000) ?: "(empty)"}")
            }
        } catch (e: Exception) { err("fetch_url: ${e.message}") }
    }

    private fun toolCalculate(expr: String): AgentStep {
        if (expr.isBlank()) return err("calculate: expr required")
        return try { ok("🔢 $expr = ${evalExpr(expr.trim())}") }
        catch (e: Exception) { err("calculate: ${e.message}") }
    }

    // ─── Search tools ─────────────────────────────────────────────────────────

    private fun toolSearchFiles(dir: String, query: String): AgentStep {
        val root = File(dir.ifBlank { workingDir })
        if (!root.exists()) return err("Dir not found: $dir")
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile }.take(500).forEach { f ->
            when {
                f.name.contains(query, true) -> hits.add("📄 ${f.absolutePath}")
                f.length() < 500_000L -> try {
                    if (f.readText().contains(query, true)) hits.add("📝 ${f.absolutePath}")
                } catch (_: Exception) {}
            }
        }
        return if (hits.isEmpty()) ok("🔍 No matches for \"$query\"")
        else ok("🔍 ${hits.size} match(es):\n${hits.take(40).joinToString("\n")}")
    }

    private fun toolWebSearch(query: String): AgentStep {
        if (query.isBlank()) return err("web_search: query required")
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val step = toolFetchUrl(url, "GET", "")
            val body = step.content.substringAfter("\n").trim()
            try {
                val json = org.json.JSONObject(body)
                val sb = StringBuilder("🔍 Web search: \"$query\"\n\n")
                val abstract = json.optString("AbstractText","")
                val absUrl   = json.optString("AbstractURL","")
                if (abstract.isNotBlank()) {
                    sb.appendLine(abstract)
                    if (absUrl.isNotBlank()) sb.appendLine("Source: $absUrl")
                    sb.appendLine()
                }
                val results = json.optJSONArray("Results")
                if (results != null && results.length() > 0) {
                    sb.appendLine("Results:")
                    for (i in 0 until minOf(results.length(), 5)) {
                        val r = results.optJSONObject(i) ?: continue
                        sb.appendLine("• ${r.optString("Text","")}")
                        sb.appendLine("  ${r.optString("FirstURL","")}")
                    }
                    sb.appendLine()
                }
                val related = json.optJSONArray("RelatedTopics")
                if (sb.length < 120 && related != null) {
                    for (i in 0 until minOf(related.length(), 4)) {
                        val r = related.optJSONObject(i) ?: continue
                        val txt = r.optString("Text","")
                        if (txt.isNotBlank()) sb.appendLine("• $txt")
                    }
                }
                ok(sb.trimEnd().toString().ifBlank { "No results found for: $query" })
            } catch (_: Exception) { step }
        } catch (e: Exception) { err("web_search: ${e.message}") }
    }

    private fun toolGrep(dir: String, pattern: String, glob: String): AgentStep {
        if (pattern.isBlank()) return err("grep: pattern required")
        val root = File(dir.ifBlank { workingDir })
        if (!root.exists()) return err("Dir not found: $dir")
        val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) }
                    catch (_: Exception) { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
        val globR = if (glob.isNotBlank())
            Regex("^${glob.replace(".", "\\.").replace("*", ".*").replace("?", ".")}$")
        else null
        val hits = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && (globR == null || globR.matches(it.name)) && it.length() < 2_000_000L }
            .take(300).forEach { f ->
                try {
                    f.readLines().forEachIndexed { i, l ->
                        if (regex.containsMatchIn(l)) {
                            hits.add("${f.absolutePath}:${i+1}: ${l.trim().take(100)}")
                            if (hits.size >= 80) return@forEach
                        }
                    }
                } catch (_: Exception) {}
            }
        return if (hits.isEmpty()) ok("🔍 grep: no matches for /$pattern/")
        else ok("🔍 grep: ${hits.size} match(es)\n${hits.joinToString("\n")}")
    }

    // ─── Memory tools ─────────────────────────────────────────────────────────

    private fun toolMemorySave(key: String, value: String): AgentStep {
        if (key.isBlank() || value.isBlank()) return err("memory_save: key and value required")
        return try {
            val f  = File(workingDir, "agent_memory.md")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            val existing = if (f.exists()) f.readText() else ""
            val marker   = "## $key"
            val entry    = "$marker\n_${ts}_\n$value\n"
            val updated  = if (existing.contains(marker)) {
                val a = existing.indexOf(marker)
                val b = existing.indexOf("\n## ", a + 1).let { if (it < 0) existing.length else it }
                existing.substring(0, a) + entry + existing.substring(b).trimStart()
            } else {
                if (existing.isBlank()) "# Agent Memory\n\n$entry"
                else existing.trimEnd() + "\n\n$entry"
            }
            f.parentFile?.mkdirs(); f.writeText(updated)
            ok("🧠 Memory saved: [$key]")
        } catch (e: Exception) { err("memory_save: ${e.message}") }
    }

    private fun toolMemoryRecall(): AgentStep {
        val f = File(workingDir, "agent_memory.md")
        return if (f.exists()) ok("🧠 Memory:\n${f.readText().take(3000)}")
        else ok("🧠 No memory saved yet.")
    }

    private fun toolMemoryClear(key: String): AgentStep {
        val f = File(workingDir, "agent_memory.md")
        if (!f.exists()) return ok("ℹ️ No memory file.")
        return try {
            if (key.isBlank()) { f.delete(); ok("🗑️ All memory cleared.") }
            else {
                val existing = f.readText()
                val marker   = "## $key"
                if (!existing.contains(marker)) return ok("ℹ️ Key not found: $key")
                val a = existing.indexOf(marker)
                val b = existing.indexOf("\n## ", a + 1).let { if (it < 0) existing.length else it }
                f.writeText(existing.substring(0, a).trimEnd() + "\n" + existing.substring(b).trimStart())
                ok("🗑️ Cleared: $key")
            }
        } catch (e: Exception) { err("memory_clear: ${e.message}") }
    }

    private fun recordTaskDone(summary: String) {
        try {
            val f  = File(context.filesDir, "agent_task_history.txt")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            f.appendText("[$ts] $summary\n")
            val txt = f.readText()
            if (txt.length > 5000) f.writeText(txt.takeLast(5000))
        } catch (_: Exception) {}
    }

    // ─── Math evaluator (no eval — pure Kotlin) ───────────────────────────────

    private fun evalExpr(e: String): String {
        val r = evalMath(e.replace("\\s".toRegex(), "").replace("**", "^"))
        return if (r == kotlin.math.floor(r) && !r.isInfinite()) r.toLong().toString()
        else "%.10g".format(r).trimEnd('0').trimEnd('.')
    }

    private fun evalMath(raw: String): Double {
        var e = raw

        // Functions: sqrt, abs, floor, ceil, round
        data class Fn(val name: String, val fn: (Double) -> Double)
        val fns = listOf(
            Fn("sqrt")  { v -> kotlin.math.sqrt(v) },
            Fn("abs")   { v -> kotlin.math.abs(v)  },
            Fn("floor") { v -> kotlin.math.floor(v) },
            Fn("ceil")  { v -> kotlin.math.ceil(v)  },
            Fn("round") { v -> kotlin.math.round(v).toDouble() }
        )
        for (fn in fns) {
            val tag = "${fn.name}("
            while (e.contains(tag)) {
                val idx = e.indexOf(tag)
                var d = 1; var j = idx + tag.length
                while (j < e.length && d > 0) { if (e[j]=='(') d++ else if (e[j]==')') d--; j++ }
                val inner = e.substring(idx + tag.length, j - 1)
                e = e.substring(0, idx) + fn.fn(evalMath(inner)) + e.substring(j)
            }
        }

        // Parentheses
        while (e.contains('(')) {
            val last  = e.lastIndexOf('(')
            val close = e.indexOf(')', last)
            if (close < 0) break
            e = e.substring(0, last) + evalMath(e.substring(last+1, close)) + e.substring(close+1)
        }

        // Tokenise into numbers + operators
        val nums = mutableListOf<Double>()
        val ops  = mutableListOf<Char>()
        var i = 0
        while (i < e.length) {
            val c = e[i]
            if (c.isDigit() || c == '.' ||
                (c == '-' && (i == 0 || e[i-1] in "+-*/^%"))) {
                var j = i + 1
                while (j < e.length && (e[j].isDigit() || e[j] == '.')) j++
                nums.add(e.substring(i, j).toDouble()); i = j
            } else if (c in "+-*/^%") { ops.add(c); i++ }
            else i++
        }
        if (nums.isEmpty()) return e.toDoubleOrNull() ?: throw IllegalArgumentException("Cannot parse: $e")

        // Precedence: ^ first, then * / %, then + -
        var oi = ops.indexOf('^')
        while (oi >= 0) {
            nums[oi] = Math.pow(nums[oi], nums[oi+1])
            nums.removeAt(oi+1); ops.removeAt(oi); oi = ops.indexOf('^')
        }
        oi = ops.indexOfFirst { it in "*/%" }
        while (oi >= 0) {
            nums[oi] = when (ops[oi]) { '*' -> nums[oi]*nums[oi+1]; '/' -> nums[oi]/nums[oi+1]; else -> nums[oi]%nums[oi+1] }
            nums.removeAt(oi+1); ops.removeAt(oi)
            oi = ops.indexOfFirst { it in "*/%" }
        }
        var r = nums[0]
        ops.forEachIndexed { k, op -> r = if (op == '+') r + nums[k+1] else r - nums[k+1] }
        return r
    }

    // ─── Agent loop ───────────────────────────────────────────────────────────

    private var askUserFn: (suspend (String) -> String)? = null

    suspend fun runAgentLoop(
        userTask: String,
        model: String,
        baseUrl: String,
        maxSteps: Int = 25,
        cloudApiKey: String = "",
        backend: String = "ollama",
        onAskUser: (suspend (String) -> String)? = null,
        onStep: suspend (AgentStep) -> Unit
    ) {
        askUserFn = onAskUser

        // Always start fresh — clearing the chat truly clears context
        val messages = mutableListOf(
            ChatMessage("system", systemPrompt()),
            ChatMessage("user",   userTask)
        )

        val api   = OllamaApi()
        val cloud = model.endsWith(":cloud")
        var steps = 0
        var errs  = 0

        while (steps < maxSteps) {
            steps++
            var response = ""
            var streamOk = true
            var streamErr = ""

            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val onTok: (String) -> Unit = { response += it }
                    val onDone: (Boolean, String) -> Unit = { s, m ->
                        if (!s) { streamOk = false; streamErr = m }
                        if (!cont.isCompleted) cont.resume(Unit)
                    }
                    val call = when {
                        cloud && cloudApiKey.isNotBlank() ->
                            api.cloudChatStream(cloudApiKey, model, messages,
                                onTokenGenerated = onTok, onComplete = onDone)
                        backend == "llamacpp" ->
                            LlamaCppApi().chatStream(baseUrl, messages,
                                onToken = onTok, onComplete = onDone)
                        else ->
                            api.chatStream(baseUrl, model, messages,
                                onTokenGenerated = onTok, onComplete = onDone)
                    }
                    cont.invokeOnCancellation { call.cancel() }
                }
            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex }
            catch (ex: Exception) {
                onStep(AgentStep("error", "❌ LLM error: ${ex.message}", true)); break
            }

            if (!streamOk) {
                errs++
                onStep(AgentStep("error", "❌ $streamErr", true))
                if (errs >= 3) { onStep(AgentStep("info", "⚠️ Too many errors — is Ollama running?")); break }
                kotlinx.coroutines.delay(1000); continue
            }
            if (response.isBlank()) {
                errs++
                onStep(AgentStep("error", "❌ Model returned empty response.", true))
                if (errs >= 3) break
                kotlinx.coroutines.delay(800); continue
            }
            errs = 0

            // Parse
            val toolCalls = parseToolCalls(response)

            // Show non-tool text (strip all known tool block formats)
            val display = response
                .replace(Regex("""WRITE_FILE>>[^\n]+\n[\s\S]*?<<WRITE_FILE"""), "")
                .replace(Regex("""TOOL>>\s*\n[\s\S]*?\n?<<TOOL"""), "")
                .replace(Regex("""```tool\s*\n[\s\S]*?\n?```"""), "")
                .replace(Regex("""```json\s*\n[\s\S]*?\n?```"""), "")
                .trim()
            if (display.isNotBlank()) onStep(AgentStep("assistant", display))

            // No tool found — nudge model (max 2 nudges before giving up)
            if (toolCalls.isEmpty()) {
                messages.add(ChatMessage("assistant", response))
                val nudges = messages.count { it.role == "user" && it.content.startsWith("[NUDGE]") }
                if (nudges < 2) {
                    messages.add(ChatMessage("user",
                        "[NUDGE] You must output a tool call. Examples:\n" +
                        "TOOL>>\n{\"name\":\"list_dir\",\"path\":\"$workingDir\"}\n<<TOOL\n\n" +
                        "Or to finish:\nTOOL>>\n{\"name\":\"complete\",\"summary\":\"what was done\"}\n<<TOOL"
                    ))
                    continue
                }
                break
            }

            // Execute tools
            val resultBuf = StringBuilder()
            var done = false
            for (call in toolCalls) {
                val toolName = call.optString("name", "?")
                onStep(AgentStep("tool_call", "🔧 $toolName"))
                val result = executeTool(call)
                onStep(result)
                resultBuf.appendLine(result.content).appendLine("---")
                if (result.type == "complete") { done = true; break }
            }

            messages.add(ChatMessage("assistant", response))
            if (done) break

            messages.add(ChatMessage("user",
                "Tool results:\n$resultBuf\nProceed. Output the next TOOL>> or WRITE_FILE>> block."))

            // Trim context window: keep system + original task + last 12 turns
            if (messages.size > 30) {
                val head = messages.take(2)
                val tail = messages.takeLast(12)
                messages.clear()
                messages.addAll(head)
                messages.add(ChatMessage("system", "[Earlier steps trimmed — context management]"))
                messages.addAll(tail)
            }
        }

        if (steps >= maxSteps)
            onStep(AgentStep("info", "⚠️ Max steps ($maxSteps) reached. Task may be incomplete."))
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun ok(msg: String)  = AgentStep("tool_result", msg, isError = false)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)

    private fun sz(b: Long) = when {
        b < 1024      -> "${b}B"
        b < 1024*1024 -> "${b/1024}KB"
        else          -> "%.1fMB".format(b/1024.0/1024.0)
    }
}
