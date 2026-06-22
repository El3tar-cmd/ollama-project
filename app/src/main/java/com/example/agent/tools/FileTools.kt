package com.example.agent.tools

import com.example.data.model.AgentStep
import java.io.File

class FileTools(private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)
    private fun sz(b: Long) = when {
        b < 1024      -> "${b}B"
        b < 1024*1024 -> "${b/1024}KB"
        else          -> "%.1fMB".format(b/1024.0/1024.0)
    }

    fun toolListDir(path: String): AgentStep {
        val dir = File(path.ifBlank { getWorkingDir() })
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

    fun toolReadFile(path: String): AgentStep {
        if (path.isBlank()) return err("read_file: path required")
        val f = File(path)
        if (!f.exists()) return err("Not found: $path")
        if (f.length() > 400_000L) return err("File too large (${sz(f.length())}). Use read_lines.")
        val text = f.readText()
        return ok("📄 $path (${text.lines().size} lines)\n$text")
    }

    fun toolReadLines(path: String, start: Int, end: Int): AgentStep {
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

    fun toolWriteFile(path: String, content: String): AgentStep {
        if (path.isBlank()) return err("write_file: path required")
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            ok("✅ Wrote ${sz(f.length())} → $path (${content.lines().size} lines)")
        } catch (e: Exception) { err("write_file: ${e.message}") }
    }

    fun toolAppendFile(path: String, content: String): AgentStep {
        if (path.isBlank()) return err("append_file: path required")
        return try {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.appendText(content)
            ok("✅ Appended ${content.length} chars → $path")
        } catch (e: Exception) { err("append_file: ${e.message}") }
    }

    fun toolEditFile(path: String, old: String, new: String): AgentStep {
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

    fun toolDeleteFile(path: String): AgentStep {
        if (path.isBlank()) return err("delete_file: path required")
        val f = File(path)
        if (!f.exists()) return err("Not found: $path")
        val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (deleted) ok("🗑️ Deleted: $path")
        else err("Could not delete: $path")
    }

    fun toolMoveFile(src: String, dst: String): AgentStep {
        if (src.isBlank() || dst.isBlank()) return err("move_file: src and dst required")
        val s = File(src); val d = File(dst)
        if (!s.exists()) return err("Source not found: $src")
        d.parentFile?.mkdirs()
        return try {
            if (s.renameTo(d)) ok("✅ Moved: $src → $dst")
            else { s.copyRecursively(d, overwrite = true); s.deleteRecursively(); ok("✅ Moved: $src → $dst") }
        } catch (e: Exception) { err("move_file: ${e.message}") }
    }

    fun toolCreateDir(path: String): AgentStep {
        if (path.isBlank()) return err("create_dir: path required")
        val d = File(path)
        if (d.exists()) return ok("ℹ️ Already exists: $path")
        return if (d.mkdirs()) ok("✅ Created: $path")
        else err("Could not create: $path")
    }
}
