package com.example.agent.tools

import android.content.Context
import com.example.data.model.AgentStep
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory System — Two-tier memory:
 *
 * LONG-TERM  (.devhive/memory_long.md):
 *   Persists permanently. Stores project overview, architecture decisions,
 *   what has been done, what remains. Never auto-cleared.
 *   Updated periodically by the agent.
 *
 * SHORT-TERM (.devhive/memory_short.md):
 *   Session-scoped summary. Cleared when the user clears the chat.
 *   Used for quick recall within a session.
 */
class MemoryTool(private val context: Context, private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)
    private fun ts() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun devhiveDir(): File =
        File(getWorkingDir(), ".devhive").also { it.mkdirs() }

    private fun longMemFile()  = File(devhiveDir(), "memory_long.md")
    private fun shortMemFile() = File(devhiveDir(), "memory_short.md")
    // Legacy path — keep reading for backwards compat
    private fun legacyMemFile() = File(getWorkingDir(), "agent_memory.md")

    // ── Save to LONG-TERM memory ───────────────────────────────────────────────
    fun toolMemorySaveLong(key: String, value: String): AgentStep {
        if (key.isBlank() || value.isBlank()) return err("memory_save_long: key and value required")
        return try {
            val f        = longMemFile()
            val existing = if (f.exists()) f.readText() else ""
            val marker   = "## $key"
            val entry    = "$marker\n_${ts()}_\n$value\n"
            val updated  = if (existing.contains(marker)) {
                val a = existing.indexOf(marker)
                val b = existing.indexOf("\n## ", a + 1).let { if (it < 0) existing.length else it }
                existing.substring(0, a) + entry + existing.substring(b).trimStart()
            } else {
                if (existing.isBlank()) "# DevHive Long-Term Memory\n\n$entry"
                else existing.trimEnd() + "\n\n$entry"
            }
            f.writeText(updated)
            ok("🧠 Long-term memory saved: [$key]")
        } catch (e: Exception) { err("memory_save_long: ${e.message}") }
    }

    // ── Save to SHORT-TERM memory ─────────────────────────────────────────────
    fun toolMemorySaveShort(key: String, value: String): AgentStep {
        if (key.isBlank() || value.isBlank()) return err("memory_save_short: key and value required")
        return try {
            val f        = shortMemFile()
            val existing = if (f.exists()) f.readText() else ""
            val marker   = "## $key"
            val entry    = "$marker\n_${ts()}_\n$value\n"
            val updated  = if (existing.contains(marker)) {
                val a = existing.indexOf(marker)
                val b = existing.indexOf("\n## ", a + 1).let { if (it < 0) existing.length else it }
                existing.substring(0, a) + entry + existing.substring(b).trimStart()
            } else {
                if (existing.isBlank()) "# DevHive Short-Term Memory\n\n$entry"
                else existing.trimEnd() + "\n\n$entry"
            }
            // Keep short-term memory compact
            val trimmed = if (updated.length > 8000) updated.takeLast(8000) else updated
            f.writeText(trimmed)
            ok("💡 Short-term memory saved: [$key]")
        } catch (e: Exception) { err("memory_save_short: ${e.message}") }
    }

    // ── Recall LONG-TERM memory ───────────────────────────────────────────────
    fun toolMemoryRecallLong(key: String): AgentStep {
        val f = longMemFile()
        val legacy = legacyMemFile()

        // Merge legacy if exists
        if (legacy.exists() && !f.exists()) {
            try { f.writeText("# DevHive Long-Term Memory\n\n" + legacy.readText()) } catch (_: Exception) {}
        }

        if (!f.exists()) return ok("🧠 Long-term memory: empty.")

        val content = f.readText().take(4000)
        return if (key.isBlank()) {
            ok("🧠 Long-term memory:\n$content")
        } else {
            val marker = "## $key"
            if (!content.contains(marker)) return ok("🧠 Long-term memory: key '$key' not found.")
            val a = content.indexOf(marker)
            val b = content.indexOf("\n## ", a + 1).let { if (it < 0) content.length else it }
            ok("🧠 Long-term [$key]:\n${content.substring(a, b).trim()}")
        }
    }

    // ── Recall SHORT-TERM memory ──────────────────────────────────────────────
    fun toolMemoryRecallShort(): AgentStep {
        val f = shortMemFile()
        return if (f.exists()) ok("💡 Short-term memory:\n${f.readText().take(3000)}")
        else ok("💡 Short-term memory: empty.")
    }

    // ── Recall ALL memory (long + short) ─────────────────────────────────────
    fun toolMemoryRecallAll(): AgentStep {
        val long  = if (longMemFile().exists())  longMemFile().readText().take(2500)  else "(empty)"
        val short = if (shortMemFile().exists()) shortMemFile().readText().take(1500) else "(empty)"
        return ok("🧠 Long-term memory:\n$long\n\n💡 Short-term memory:\n$short")
    }

    // ── Clear SHORT-TERM memory (on chat clear) ───────────────────────────────
    fun toolMemoryClearShort(): AgentStep {
        val f = shortMemFile()
        return if (!f.exists()) ok("ℹ️ Short-term memory already empty.")
        else { f.delete(); ok("🗑️ Short-term memory cleared.") }
    }

    // ── Clear LONG-TERM memory (specific key or all) ──────────────────────────
    fun toolMemoryClearLong(key: String): AgentStep {
        val f = longMemFile()
        if (!f.exists()) return ok("ℹ️ Long-term memory: empty.")
        return try {
            if (key.isBlank()) { f.delete(); ok("🗑️ Long-term memory cleared.") }
            else {
                val existing = f.readText()
                val marker   = "## $key"
                if (!existing.contains(marker)) return ok("ℹ️ Key not found: $key")
                val a = existing.indexOf(marker)
                val b = existing.indexOf("\n## ", a + 1).let { if (it < 0) existing.length else it }
                f.writeText(existing.substring(0, a).trimEnd() + "\n" + existing.substring(b).trimStart())
                ok("🗑️ Long-term memory cleared: $key")
            }
        } catch (e: Exception) { err("memory_clear_long: ${e.message}") }
    }

    // ── Legacy compatibility: memory_save / memory_recall / memory_clear ──────
    fun toolMemorySave(key: String, value: String)   = toolMemorySaveLong(key, value)
    fun toolMemoryRecall()                           = toolMemoryRecallLong("")
    fun toolMemoryClear(key: String)                 = toolMemoryClearLong(key)

    // ── Record task completion ─────────────────────────────────────────────────
    fun recordTaskDone(summary: String) {
        try {
            val f  = File(context.filesDir, "agent_task_history.txt")
            f.appendText("[${ts()}] $summary\n")
            val txt = f.readText()
            if (txt.length > 5000) f.writeText(txt.takeLast(5000))
        } catch (_: Exception) {}

        // Also update long-term memory with last completed task
        try {
            toolMemorySaveLong("last_task", summary)
        } catch (_: Exception) {}
    }

    // ── Load all memory into system prompt ────────────────────────────────────
    fun loadMemoryForPrompt(): String {
        val sb = StringBuilder()
        val long  = longMemFile()
        val short = shortMemFile()
        val legacy = legacyMemFile()

        if (long.exists()) {
            sb.appendLine("\n=== LONG-TERM MEMORY ===")
            sb.appendLine(long.readText().trim().take(2000))
            sb.appendLine("=== END LONG-TERM ===")
        } else if (legacy.exists()) {
            sb.appendLine("\n=== MEMORY (legacy) ===")
            sb.appendLine(legacy.readText().trim().take(1500))
            sb.appendLine("=== END MEMORY ===")
        }

        if (short.exists()) {
            sb.appendLine("\n=== SHORT-TERM MEMORY (this session) ===")
            sb.appendLine(short.readText().trim().take(1500))
            sb.appendLine("=== END SHORT-TERM ===")
        }

        return sb.toString().trim()
    }
}
