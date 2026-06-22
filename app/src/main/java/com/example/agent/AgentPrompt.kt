package com.example.agent

import android.util.Log
import org.json.JSONObject
import java.io.File

internal const val TAG_AGENT = "AgentEngine"

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

fun buildSystemPrompt(workingDir: String): String {
    val base = SYSTEM_PROMPT.replace("{{WD}}", workingDir)
    val mem  = File(workingDir, "agent_memory.md")
    return if (mem.exists()) {
        val txt = mem.readText().trim().take(1500)
        "$base\n\n=== YOUR MEMORY ===\n$txt\n=== END MEMORY ==="
    } else base
}

fun parseToolCalls(text: String): List<JSONObject> {
    val results = mutableListOf<JSONObject>()
    val seen    = mutableSetOf<String>()

    fun add(obj: JSONObject) {
        val key = obj.optString("name") + "|" + obj.toString()
        if (key !in seen) { seen.add(key); results.add(obj) }
    }

    Regex("""WRITE_FILE>>([^\n]+)\n([\s\S]*?)<<WRITE_FILE""")
        .findAll(text).forEach { m ->
            val path    = m.groupValues[1].trim()
            var content = m.groupValues[2]
            if (content.endsWith("\n")) content = content.dropLast(1)
            if (path.isNotBlank()) {
                add(JSONObject().apply {
                    put("name",    "write_file")
                    put("path",    path)
                    put("content", content)
                })
            }
        }

    Regex("""TOOL>>\s*\n([\s\S]*?)\n?<<TOOL""")
        .findAll(text).forEach { m ->
            val raw = m.groupValues[1].trim()
            if (raw.contains("\"name\""))
                try { add(JSONObject(raw)) } catch (_: Exception) {}
        }

    Regex("""```tool\s*\n([\s\S]*?)\n?```""")
        .findAll(text).forEach { m ->
            val raw = m.groupValues[1].trim()
            if (raw.contains("\"name\""))
                try { add(JSONObject(raw)) } catch (_: Exception) {}
        }

    Regex("""```json\s*\n([\s\S]*?)\n?```""")
        .findAll(text).forEach { m ->
            val raw = m.groupValues[1].trim()
            if (raw.contains("\"name\""))
                try { add(JSONObject(raw)) } catch (_: Exception) {}
        }

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
        Log.d(TAG_AGENT, "parseToolCalls: no tool calls found (${text.length} chars preview: ${text.take(120)})")
    return results
}
