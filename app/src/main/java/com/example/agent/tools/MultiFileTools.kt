package com.example.agent.tools

import com.example.data.model.AgentStep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MultiFileTools(private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)
    private fun sz(b: Long) = when {
        b < 1024      -> "${b}B"
        b < 1024*1024 -> "${b/1024}KB"
        else          -> "%.1fMB".format(b/1024.0/1024.0)
    }

    /**
     * multi_file_writer — write multiple files in one call.
     * Input JSON: {"name":"multi_file_writer","files":[{"path":"...","content":"..."},...]}
     */
    fun toolMultiFileWriter(filesArray: JSONArray): AgentStep {
        if (filesArray.length() == 0) return err("multi_file_writer: files array is empty")
        val results = mutableListOf<String>()
        var errorCount = 0

        for (i in 0 until filesArray.length()) {
            val item    = filesArray.optJSONObject(i) ?: continue
            val path    = item.optString("path", "").trim()
            val content = item.optString("content", "")

            if (path.isBlank()) { results.add("  ❌ Item $i: missing path"); errorCount++; continue }

            try {
                val f = File(path)
                f.parentFile?.mkdirs()
                f.writeText(content)
                results.add("  ✅ ${f.name}  (${sz(f.length())}, ${content.lines().size} lines)")
            } catch (e: Exception) {
                results.add("  ❌ $path: ${e.message}")
                errorCount++
            }
        }

        val header = if (errorCount == 0)
            "✅ multi_file_writer: wrote ${filesArray.length()} file(s)"
        else
            "⚠️ multi_file_writer: ${filesArray.length() - errorCount} ok, $errorCount failed"

        return ok("$header\n${results.joinToString("\n")}")
    }

    /**
     * multi_line_editor — apply multiple find/replace edits to a single file.
     * Input JSON: {"name":"multi_line_editor","path":"...","edits":[{"old":"...","new":"..."},...]}
     */
    fun toolMultiLineEditor(path: String, editsArray: JSONArray): AgentStep {
        if (path.isBlank()) return err("multi_line_editor: path required")
        val f = File(path)
        if (!f.exists()) return err("multi_line_editor: file not found: $path")
        if (editsArray.length() == 0) return err("multi_line_editor: edits array is empty")

        return try {
            var text = f.readText()
            val report = mutableListOf<String>()
            var totalReplaced = 0

            for (i in 0 until editsArray.length()) {
                val edit    = editsArray.optJSONObject(i) ?: continue
                val oldText = edit.optString("old", "")
                val newText = edit.optString("new", "")

                if (oldText.isBlank()) { report.add("  ⚠️ Edit $i: 'old' is blank, skipped"); continue }

                val count = text.split(oldText).size - 1
                if (count == 0) {
                    report.add("  ❌ Edit $i: text not found — \"${oldText.take(60)}\"")
                } else {
                    text = text.replace(oldText, newText)
                    report.add("  ✅ Edit $i: replaced $count occurrence(s)")
                    totalReplaced += count
                }
            }

            f.writeText(text)
            ok("✅ multi_line_editor: $totalReplaced replacement(s) in ${f.name}\n${report.joinToString("\n")}")
        } catch (e: Exception) { err("multi_line_editor: ${e.message}") }
    }

    /**
     * MULTI_WRITE — parse a block of multiple WRITE_FILE>> sections and write them all.
     * This is a helper for the parser, not called directly by the LLM.
     */
    fun writeFilesFromMap(files: Map<String, String>): AgentStep {
        if (files.isEmpty()) return err("No files to write")
        val results = mutableListOf<String>()
        var ok = 0
        files.forEach { (path, content) ->
            try {
                val f = File(path); f.parentFile?.mkdirs(); f.writeText(content)
                results.add("  ✅ ${f.name} (${sz(f.length())})")
                ok++
            } catch (e: Exception) { results.add("  ❌ $path: ${e.message}") }
        }
        return AgentStep("tool_result", "✅ Wrote $ok/${files.size} file(s)\n${results.joinToString("\n")}")
    }
}
