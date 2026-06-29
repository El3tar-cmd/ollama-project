package com.example.agent.tools

import android.content.Context
import com.example.data.model.AgentStep
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Advanced Three-Tier Memory System
 *
 * TIER 1 — SEMANTIC (Long-Term, .devhive/memory_long.md)
 *   Stable facts, architecture decisions, project knowledge.
 *   Never auto-cleared. Updated by the agent.
 *
 * TIER 2 — EPISODIC (Session, .devhive/memory_short.md)
 *   What happened in this session: steps taken, errors, solutions found.
 *   Cleared when user clears chat.
 *
 * TIER 3 — PROCEDURAL (How-To, .devhive/memory_procedures.md)
 *   Patterns and recipes: "how to build X", "how to fix Y in this project".
 *   Semi-permanent. Updated when agent discovers a working procedure.
 *
 * TASK HISTORY (.devhive/task_history.md)
 *   Compact log of completed tasks with timestamps. Useful for retrospection.
 */
class MemoryTool(private val context: Context, private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)
    private fun ts() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun devhiveDir(): File =
        File(getWorkingDir(), ".devhive").also { it.mkdirs() }

    // File accessors
    private fun longMemFile()      = File(devhiveDir(), "memory_long.md")
    private fun shortMemFile()     = File(devhiveDir(), "memory_short.md")
    private fun procedureMemFile() = File(devhiveDir(), "memory_procedures.md")
    private fun taskHistoryFile()  = File(devhiveDir(), "task_history.md")
    // Legacy compat
    private fun legacyMemFile()    = File(getWorkingDir(), "agent_memory.md")

    // ── TIER 1: Semantic / Long-term memory ───────────────────────────────────

    fun toolMemorySaveLong(key: String, value: String): AgentStep {
        if (key.isBlank() || value.isBlank())
            return err("memory_save_long: 'key' and 'value' are required")
        return try {
            val f       = longMemFile()
            val current = if (f.exists()) f.readText() else ""
            f.writeText(upsertSection(current, key, value, "DevHive Long-Term Memory"))
            ok("🧠 Long-term memory saved: [$key] (${value.length} chars)")
        } catch (e: Exception) { err("memory_save_long: ${e.message}") }
    }

    fun toolMemoryRecallLong(key: String): AgentStep {
        migrateLegacy()
        val f = longMemFile()
        if (!f.exists()) return ok("🧠 Long-term memory: empty.")
        val content = f.readText()
        return if (key.isBlank()) {
            ok("🧠 Long-Term Memory (Semantic):\n${content.take(6000)}")
        } else {
            val section = extractSection(content, key)
            if (section != null) ok("🧠 [$key]:\n$section")
            else ok("🧠 Key '$key' not found in long-term memory.")
        }
    }

    fun toolMemoryClearLong(key: String): AgentStep {
        val f = longMemFile()
        if (!f.exists()) return ok("ℹ️ Long-term memory already empty.")
        return try {
            if (key.isBlank()) { f.delete(); ok("🗑️ Long-term memory cleared.") }
            else {
                val updated = deleteSection(f.readText(), key)
                f.writeText(updated)
                ok("🗑️ Long-term memory: removed key '$key'")
            }
        } catch (e: Exception) { err("memory_clear_long: ${e.message}") }
    }

    // ── TIER 2: Episodic / Short-term (session) memory ────────────────────────

    fun toolMemorySaveShort(key: String, value: String): AgentStep {
        if (key.isBlank() || value.isBlank())
            return err("memory_save_short: 'key' and 'value' required")
        return try {
            val f       = shortMemFile()
            val current = if (f.exists()) f.readText() else ""
            var updated = upsertSection(current, key, value, "DevHive Session Memory")
            // Keep short-term memory compact
            if (updated.length > 10_000) updated = updated.takeLast(10_000)
            f.writeText(updated)
            ok("💡 Session memory saved: [$key]")
        } catch (e: Exception) { err("memory_save_short: ${e.message}") }
    }

    fun toolMemoryRecallShort(): AgentStep {
        val f = shortMemFile()
        return if (f.exists()) ok("💡 Session Memory (Episodic):\n${f.readText().take(4000)}")
        else ok("💡 Session memory: empty.")
    }

    fun toolMemoryClearShort(): AgentStep {
        val f = shortMemFile()
        return if (!f.exists()) ok("ℹ️ Session memory already empty.")
        else { f.delete(); ok("🗑️ Session memory cleared.") }
    }

    // ── TIER 3: Procedural memory (how-to patterns) ───────────────────────────

    fun toolMemorySaveProcedure(name: String, steps: String): AgentStep {
        if (name.isBlank() || steps.isBlank())
            return err("memory_save_procedure: 'name' and 'steps' required")
        return try {
            val f       = procedureMemFile()
            val current = if (f.exists()) f.readText() else ""
            f.writeText(upsertSection(current, name, steps, "DevHive Procedures"))
            ok("📚 Procedure saved: [$name]")
        } catch (e: Exception) { err("memory_save_procedure: ${e.message}") }
    }

    fun toolMemoryRecallProcedure(name: String): AgentStep {
        val f = procedureMemFile()
        if (!f.exists()) return ok("📚 No procedures saved yet.")
        val content = f.readText()
        return if (name.isBlank()) {
            ok("📚 Procedures:\n${content.take(4000)}")
        } else {
            val section = extractSection(content, name)
            if (section != null) ok("📚 Procedure [$name]:\n$section")
            else ok("📚 Procedure '$name' not found.")
        }
    }

    // ── Recall ALL memory tiers at once ───────────────────────────────────────

    fun toolMemoryRecallAll(): AgentStep {
        migrateLegacy()
        val long  = longMemFile().takeIf { it.exists() }?.readText()?.take(3000)  ?: "(empty)"
        val short = shortMemFile().takeIf { it.exists() }?.readText()?.take(2000) ?: "(empty)"
        val procs = procedureMemFile().takeIf { it.exists() }?.readText()?.take(1500) ?: "(empty)"
        return ok(buildString {
            appendLine("═══════ MEMORY RECALL — ALL TIERS ═══════")
            appendLine()
            appendLine("🧠 TIER 1 — Semantic (Long-Term):")
            appendLine(long)
            appendLine()
            appendLine("💡 TIER 2 — Episodic (Session):")
            appendLine(short)
            appendLine()
            appendLine("📚 TIER 3 — Procedural (How-To):")
            append(procs)
        })
    }

    // ── Smart search across all memory tiers ──────────────────────────────────

    fun toolMemorySearch(query: String): AgentStep {
        if (query.isBlank()) return err("memory_search: query required")
        val keywords = query.lowercase().split(Regex("[\\s,]+")).filter { it.length > 2 }
        val results  = mutableListOf<String>()

        listOf(
            "Long-Term" to longMemFile(),
            "Session"   to shortMemFile(),
            "Procedures" to procedureMemFile()
        ).forEach { (label, file) ->
            if (!file.exists()) return@forEach
            file.readLines().forEachIndexed { i, line ->
                val lower = line.lowercase()
                if (keywords.any { kw -> lower.contains(kw) }) {
                    results.add("[$label:L${i+1}] ${line.trim().take(100)}")
                }
            }
        }

        return if (results.isEmpty())
            ok("🔍 memory_search: No matches for '$query'")
        else
            ok("🔍 Memory search '$query': ${results.size} hit(s)\n${results.take(20).joinToString("\n")}")
    }

    // ── Task history ──────────────────────────────────────────────────────────

    fun recordTaskDone(summary: String) {
        try {
            val f    = taskHistoryFile()
            val line = "[${ts()}] ✅ $summary\n"
            if (!f.exists()) {
                f.writeText("# Task History\n\n$line")
            } else {
                f.appendText(line)
                // Keep compact — last 6000 chars
                val txt = f.readText()
                if (txt.length > 6000) f.writeText(txt.takeLast(6000))
            }
            // Also checkpoint into long-term memory
            toolMemorySaveLong("last_task", summary)
        } catch (_: Exception) {}
    }

    // ── Load memory for system prompt injection ────────────────────────────────

    fun loadMemoryForPrompt(): String {
        migrateLegacy()
        val sb = StringBuilder()

        longMemFile().takeIf { it.exists() }?.readText()?.trim()?.let {
            if (it.isNotBlank()) {
                sb.appendLine("\n━━━ LONG-TERM MEMORY (Semantic) ━━━")
                sb.appendLine(it.take(2500))
                sb.appendLine("━━━ END LONG-TERM MEMORY ━━━")
            }
        }

        shortMemFile().takeIf { it.exists() }?.readText()?.trim()?.let {
            if (it.isNotBlank()) {
                sb.appendLine("\n━━━ SESSION MEMORY (Episodic) ━━━")
                sb.appendLine(it.take(1500))
                sb.appendLine("━━━ END SESSION MEMORY ━━━")
            }
        }

        procedureMemFile().takeIf { it.exists() }?.readText()?.trim()?.let {
            if (it.isNotBlank()) {
                sb.appendLine("\n━━━ PROCEDURES (How-To) ━━━")
                sb.appendLine(it.take(1000))
                sb.appendLine("━━━ END PROCEDURES ━━━")
            }
        }

        return sb.toString().trim()
    }

    // ── Legacy compatibility ──────────────────────────────────────────────────

    fun toolMemorySave(key: String, value: String) = toolMemorySaveLong(key, value)
    fun toolMemoryRecall()                         = toolMemoryRecallLong("")
    fun toolMemoryClear(key: String)               = toolMemoryClearLong(key)

    private fun migrateLegacy() {
        val legacy = legacyMemFile()
        val target = longMemFile()
        if (legacy.exists() && !target.exists()) {
            try {
                target.writeText("# DevHive Long-Term Memory\n\n" + legacy.readText())
            } catch (_: Exception) {}
        }
    }

    // ── Markdown section helpers ───────────────────────────────────────────────

    private fun upsertSection(
        existing: String,
        key: String,
        value: String,
        header: String
    ): String {
        val safeKey = key.trim()
        val marker  = "## $safeKey"
        val entry   = "$marker\n_${ts()}_\n${value.trim()}\n"

        return if (existing.contains(marker)) {
            val a = existing.indexOf(marker)
            val b = existing.indexOf("\n## ", a + 1).let { if (it < 0) existing.length else it }
            existing.substring(0, a) + entry + existing.substring(b).trimStart('\n')
        } else {
            if (existing.isBlank()) "# $header\n\n$entry"
            else existing.trimEnd() + "\n\n$entry"
        }
    }

    private fun extractSection(content: String, key: String): String? {
        val marker = "## $key"
        if (!content.contains(marker)) return null
        val a = content.indexOf(marker)
        val b = content.indexOf("\n## ", a + 1).let { if (it < 0) content.length else it }
        return content.substring(a, b).trim()
    }

    private fun deleteSection(content: String, key: String): String {
        val marker = "## $key"
        if (!content.contains(marker)) return content
        val a = content.indexOf(marker)
        val b = content.indexOf("\n## ", a + 1).let { if (it < 0) content.length else it }
        return (content.substring(0, a).trimEnd() + "\n" + content.substring(b).trimStart()).trim()
    }
}
