package com.example.agent.tools

import android.content.Context
import com.example.data.model.AgentStep
import java.io.File

class MemoryTool(private val context: Context, private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)

    fun toolMemorySave(key: String, value: String): AgentStep {
        if (key.isBlank() || value.isBlank()) return err("memory_save: key and value required")
        return try {
            val f  = File(getWorkingDir(), "agent_memory.md")
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

    fun toolMemoryRecall(): AgentStep {
        val f = File(getWorkingDir(), "agent_memory.md")
        return if (f.exists()) ok("🧠 Memory:\n${f.readText().take(3000)}")
        else ok("🧠 No memory saved yet.")
    }

    fun toolMemoryClear(key: String): AgentStep {
        val f = File(getWorkingDir(), "agent_memory.md")
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

    fun recordTaskDone(summary: String) {
        try {
            val f  = File(context.filesDir, "agent_task_history.txt")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
            f.appendText("[$ts] $summary\n")
            val txt = f.readText()
            if (txt.length > 5000) f.writeText(txt.takeLast(5000))
        } catch (_: Exception) {}
    }
}
