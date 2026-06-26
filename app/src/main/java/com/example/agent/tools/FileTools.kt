package com.example.agent.tools

import com.example.data.model.AgentStep
import java.io.File

class FileTools(private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)

    private fun validatePath(path: String): File {
        val workingDir = File(getWorkingDir()).canonicalFile
        val targetFile = File(path).canonicalFile
        if (!targetFile.absolutePath.startsWith(workingDir.absolutePath)) {
            throw SecurityException("Path Traversal Attempt: $path is outside the workspace")
        }
        return targetFile
    }
    private fun sz(b: Long) = when {
        b < 1024      -> "${b}B"
        b < 1024*1024 -> "${b/1024}KB"
        else          -> "%.1fMB".format(b/1024.0/1024.0)
    }

    // ── Directory Explorer ────────────────────────────────────────────────────
    fun toolListDir(path: String): AgentStep {
        val dir = try {
            validatePath(path.ifBlank { getWorkingDir() })
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
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

    // ── Tree (recursive directory tree) ───────────────────────────────────────
    fun toolTree(path: String, maxDepth: Int): AgentStep {
        val root = try {
            validatePath(path.ifBlank { getWorkingDir() })
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
        if (!root.exists()) return err("Not found: $path")
        val depth = maxDepth.coerceIn(1, 6)
        val sb    = StringBuilder("🌳 ${root.absolutePath}\n")
        var count = 0

        fun walk(dir: File, prefix: String, currentDepth: Int) {
            if (currentDepth > depth || count > 200) return
            val children = dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?: return
            children.forEachIndexed { i, f ->
                val isLast   = i == children.size - 1
                val connector = if (isLast) "└── " else "├── "
                val icon      = if (f.isDirectory) "📂" else "📄"
                sb.appendLine("$prefix$connector$icon ${f.name}${if (f.isFile) "  (${sz(f.length())})" else "/"}")
                count++
                if (f.isDirectory && currentDepth < depth) {
                    walk(f, prefix + if (isLast) "    " else "│   ", currentDepth + 1)
                }
            }
        }

        walk(root, "", 1)
        if (count > 200) sb.appendLine("... (truncated at 200 items)")
        return ok(sb.trimEnd().toString())
    }

    // ── File Reader ───────────────────────────────────────────────────────────
    fun toolReadFile(path: String): AgentStep {
        if (path.isBlank()) return err("read_file: path required")
        val f = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
        if (!f.exists()) return err("Not found: $path")
        if (f.length() > 400_000L) return err("File too large (${sz(f.length())}). Use line_reader.")
        val text = f.readText()
        return ok("📄 $path (${text.lines().size} lines)\n$text")
    }

    // ── Line Reader ───────────────────────────────────────────────────────────
    fun toolReadLines(path: String, start: Int, end: Int): AgentStep {
        if (path.isBlank()) return err("line_reader: path required")
        val f = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
        if (!f.exists()) return err("Not found: $path")
        val lines = f.readLines()
        val total = lines.size
        val s: Int
        val e: Int
        
        // Handle interval formats like "(min,max)" or "min-max" in start parameter
        val startStr = start.toString()
        val intervalMatch = Regex("""^\(?(\d+)[-,:]?\s*(\d+)\)?$""").find(startStr)
        if (intervalMatch != null) {
            s = intervalMatch.groupValues[1].toIntOrNull()?.coerceIn(1, total) ?: 1
            e = intervalMatch.groupValues[2].toIntOrNull()?.coerceIn(s, total) ?: total
        } else {
            s = (start - 1).coerceIn(0, total)
            e = end.coerceIn(s, total)
        }
        
        val slice = lines.subList(s, e.coerceAtLeast(s))
        val out   = slice.mapIndexed { i, l -> "${s + i + 1}: $l" }.joinToString("\n")
        return ok("📄 $path lines $start–${s + slice.size}/$total\n$out")
    }

    fun toolReadLine(path: String, line: Int): AgentStep {
        if (path.isBlank()) return err("read_line: path required")
        val n = line.coerceAtLeast(1)
        return toolReadLines(path, n, n)
    }

    // ── Head (first N lines) ──────────────────────────────────────────────────
    fun toolHeadFile(path: String, lines: Int): AgentStep {
        if (path.isBlank()) return err("head_file: path required")
        val f = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
        if (!f.exists()) return err("Not found: $path")
        val n    = lines.coerceIn(1, 500)
        val all  = f.readLines()
        val out  = all.take(n).mapIndexed { i, l -> "${i + 1}: $l" }.joinToString("\n")
        return ok("📄 $path — first $n/${all.size} lines\n$out")
    }

    // ── Tail (last N lines) ───────────────────────────────────────────────────
    fun toolTailFile(path: String, lines: Int): AgentStep {
        if (path.isBlank()) return err("tail_file: path required")
        val f = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
        if (!f.exists()) return err("Not found: $path")
        val n    = lines.coerceIn(1, 500)
        val all  = f.readLines()
        val from = maxOf(0, all.size - n)
        val out  = all.drop(from).mapIndexed { i, l -> "${from + i + 1}: $l" }.joinToString("\n")
        return ok("📄 $path — last $n/${all.size} lines\n$out")
    }

    // ── File Info (size, modified, lines, permissions) ────────────────────────
    fun toolFileInfo(path: String): AgentStep {
        if (path.isBlank()) return err("file_info: path required")
        val f = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
        if (!f.exists()) return err("Not found: $path")
        val ts  = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(f.lastModified()))
        val lines = if (f.isFile && f.length() < 2_000_000L) {
            try { f.readLines().size } catch (_: Exception) { -1 }
        } else -1

        val sb = StringBuilder("ℹ️ File Info: $path\n")
        sb.appendLine("  Type    : ${if (f.isDirectory) "directory" else "file"}")
        sb.appendLine("  Size    : ${sz(f.length())} (${f.length()} bytes)")
        sb.appendLine("  Modified: $ts")
        if (f.isFile) sb.appendLine("  Lines   : ${if (lines >= 0) "$lines" else "N/A (too large)"}")
        sb.appendLine("  Read    : ${f.canRead()} | Write: ${f.canWrite()} | Execute: ${f.canExecute()}")
        if (f.isDirectory) sb.appendLine("  Children: ${f.listFiles()?.size ?: 0}")
        return ok(sb.trimEnd().toString())
    }

    // ── File Writer ───────────────────────────────────────────────────────────
    fun toolWriteFile(path: String, content: String): AgentStep {
        if (path.isBlank()) return err("file_writer: path required")
        return try {
            val f = validatePath(path)
            f.parentFile?.mkdirs()
            f.writeText(content)
            ok("✅ Wrote ${sz(f.length())} → $path (${content.lines().size} lines)")
        } catch (e: Exception) { err("file_writer: ${e.message}") }
    }

    // ── Append File ───────────────────────────────────────────────────────────
    fun toolAppendFile(path: String, content: String): AgentStep {
        if (path.isBlank()) return err("append_file: path required")
        return try {
            val f = validatePath(path)
            f.parentFile?.mkdirs()
            f.appendText(content)
            ok("✅ Appended ${content.length} chars → $path")
        } catch (e: Exception) { err("append_file: ${e.message}") }
    }

    // ── Line Editor (single find/replace) ─────────────────────────────────────
    fun toolEditFile(path: String, old: String, new: String): AgentStep {
        if (path.isBlank() || old.isBlank()) return err("line_editor: path and old required")
        return try {
            val f = validatePath(path)
            if (!f.exists()) return err("Not found: $path")
            val text  = f.readText()
            val count = text.split(old).size - 1
            if (count == 0) return err("line_editor: text not found — use file_reader to verify exact content")
            f.writeText(text.replace(old, new))
            ok("✅ Replaced $count occurrence(s) in $path")
        } catch (e: Exception) { err("line_editor: ${e.message}") }
    }

    // ── Replace All (replace every occurrence in file) ────────────────────────
    fun toolReplaceAll(path: String, old: String, new: String): AgentStep {
        if (path.isBlank() || old.isBlank()) return err("replace_all: path and old required")
        return try {
            val f = validatePath(path)
            if (!f.exists()) return err("Not found: $path")
            val text  = f.readText()
            val count = text.split(old).size - 1
            if (count == 0) return ok("ℹ️ replace_all: pattern not found in $path")
            f.writeText(text.replace(old, new))
            ok("✅ replace_all: replaced $count occurrence(s) in ${f.name}")
        } catch (e: Exception) { err("replace_all: ${e.message}") }
    }

    fun toolReplaceLines(path: String, start: Int, end: Int, content: String): AgentStep {
        if (path.isBlank()) return err("replace_lines: path required")
        if (start < 1 || end < start) return err("replace_lines: invalid start/end")
        return try {
            val f = validatePath(path)
            if (!f.exists()) return err("Not found: $path")
            val lines = f.readLines().toMutableList()
            if (start > lines.size) return err("replace_lines: start line $start beyond file length ${lines.size}")
            val from = start - 1
            val toExclusive = end.coerceAtMost(lines.size)
            val replacement = content.trimEnd('\n').split('\n')
            repeat(toExclusive - from) { lines.removeAt(from) }
            lines.addAll(from, replacement)
            f.writeText(lines.joinToString("\n") + "\n")
            ok("✅ replace_lines: replaced $start-${toExclusive} in $path with ${replacement.size} line(s)")
        } catch (e: Exception) { err("replace_lines: ${e.message}") }
    }

    fun toolInsertBefore(path: String, marker: String, content: String): AgentStep =
        insertNearMarker(path, marker, content, before = true)

    fun toolInsertAfter(path: String, marker: String, content: String): AgentStep =
        insertNearMarker(path, marker, content, before = false)

    private fun insertNearMarker(path: String, marker: String, content: String, before: Boolean): AgentStep {
        if (path.isBlank() || marker.isBlank()) return err("${if (before) "insert_before" else "insert_after"}: path and marker required")
        return try {
            val f = validatePath(path)
            if (!f.exists()) return err("Not found: $path")
            val text = f.readText()
            val idx = text.indexOf(marker)
            if (idx < 0) return err("marker not found — read nearby lines and use an exact marker")
            val insertAt = if (before) idx else idx + marker.length
            val beforeText = text.substring(0, insertAt)
            val afterText = text.substring(insertAt)
            val insert = buildString {
                if (beforeText.isNotEmpty() && !beforeText.endsWith('\n')) append('\n')
                append(content.trimEnd('\n'))
                if (afterText.isNotEmpty() && !afterText.startsWith('\n')) append('\n')
            }
            f.writeText(beforeText + insert + afterText)
            ok("✅ ${if (before) "insert_before" else "insert_after"}: inserted ${content.lines().size} line(s) in $path")
        } catch (e: Exception) { err("${if (before) "insert_before" else "insert_after"}: ${e.message}") }
    }

    fun toolFileOutline(path: String, maxLines: Int): AgentStep {
        if (path.isBlank()) return err("file_outline: path required")
        val f = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
        if (!f.exists()) return err("Not found: $path")
        if (!f.isFile) return err("file_outline: not a file: $path")
        val patterns = listOf(
            Regex("""^\s*(class|interface|object|enum class|data class)\s+\w+"""),
            Regex("""^\s*(public|private|protected|internal)?\s*(suspend\s+)?fun\s+\w+"""),
            Regex("""^\s*(val|var)\s+\w+"""),
            Regex("""^\s*(import|package)\s+""")
        )
        val limit = maxLines.coerceIn(20, 300)
        val hits = mutableListOf<String>()
        f.readLines().forEachIndexed { i, line ->
            if (patterns.any { it.containsMatchIn(line) }) {
                hits.add("${i + 1}: ${line.trim().take(140)}")
                if (hits.size >= limit) return@forEachIndexed
            }
        }
        return if (hits.isEmpty()) ok("🧭 file_outline: no outline symbols found in $path")
        else ok("🧭 file_outline: ${hits.size} symbol/import line(s) in $path\n${hits.joinToString("\n")}")
    }

    // ── Delete File ───────────────────────────────────────────────────────────
    fun toolDeleteFile(path: String): AgentStep {
        if (path.isBlank()) return err("delete_file: path required")
        val f = try {
            validatePath(path)
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
        if (!f.exists()) return err("Not found: $path")
        val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (deleted) ok("🗑️ Deleted: $path")
        else err("Could not delete: $path")
    }

    // ── Copy File ─────────────────────────────────────────────────────────────
    fun toolCopyFile(src: String, dst: String): AgentStep {
        if (src.isBlank() || dst.isBlank()) return err("copy_file: src and dst required")
        val s = try { validatePath(src) } catch (e: SecurityException) { return err(e.message ?: "Invalid path") }
        val d = try { validatePath(dst) } catch (e: SecurityException) { return err(e.message ?: "Invalid path") }
        if (!s.exists()) return err("Source not found: $src")
        return try {
            d.parentFile?.mkdirs()
            s.copyRecursively(d, overwrite = true)
            ok("✅ Copied: $src → $dst (${sz(d.length())})")
        } catch (e: Exception) { err("copy_file: ${e.message}") }
    }

    // ── Move File ─────────────────────────────────────────────────────────────
    fun toolMoveFile(src: String, dst: String): AgentStep {
        if (src.isBlank() || dst.isBlank()) return err("move_file: src and dst required")
        val s = try { validatePath(src) } catch (e: SecurityException) { return err(e.message ?: "Invalid path") }
        val d = try { validatePath(dst) } catch (e: SecurityException) { return err(e.message ?: "Invalid path") }
        if (!s.exists()) return err("Source not found: $src")
        d.parentFile?.mkdirs()
        return try {
            if (s.renameTo(d)) ok("✅ Moved: $src → $dst")
            else { s.copyRecursively(d, overwrite = true); s.deleteRecursively(); ok("✅ Moved: $src → $dst") }
        } catch (e: Exception) { err("move_file: ${e.message}") }
    }

    // ── Create Dir ────────────────────────────────────────────────────────────
    fun toolCreateDir(path: String): AgentStep {
        if (path.isBlank()) return err("create_dir: path required")
        val d = try { validatePath(path) } catch (e: SecurityException) { return err(e.message ?: "Invalid path") }
        if (d.exists()) return ok("ℹ️ Already exists: $path")
        return if (d.mkdirs()) ok("✅ Created: $path")
        else err("Could not create: $path")
    }

    // ── Find Files (glob pattern across directory) ─────────────────────────────
    fun toolFindFiles(dir: String, pattern: String, maxResults: Int): AgentStep {
        val root = try {
            validatePath(dir.ifBlank { getWorkingDir() })
        } catch (e: SecurityException) {
            return err(e.message ?: "Invalid path")
        }
        if (!root.exists()) return err("Dir not found: $dir")
        if (pattern.isBlank()) return err("find_files: pattern required (e.g. *.kt)")

        val globRegex = Regex(
            "^${pattern.replace(".", "\\.").replace("**", "DSTAR").replace("*", "[^/]*").replace("DSTAR", ".*").replace("?", ".")}$"
        )
        val limit = maxResults.coerceIn(1, 200)
        val hits  = mutableListOf<String>()

        root.walkTopDown().filter { it.isFile }.take(2000).forEach { f ->
            val rel = f.relativeTo(root).path
            if (globRegex.matches(f.name) || globRegex.matches(rel)) {
                hits.add("📄 ${f.absolutePath}  (${sz(f.length())})")
                if (hits.size >= limit) return@forEach
            }
        }

        return if (hits.isEmpty()) ok("🔍 find_files: no files matching \"$pattern\"")
        else ok("🔍 find_files [$pattern]: ${hits.size} result(s)\n${hits.joinToString("\n")}")
    }

    // ── File Diff (compare two files line by line) ─────────────────────────────
    fun toolFileDiff(pathA: String, pathB: String): AgentStep {
        if (pathA.isBlank() || pathB.isBlank()) return err("file_diff: two paths required")
        val a = try { validatePath(pathA) } catch (e: SecurityException) { return err(e.message ?: "Invalid path") }
        val b = try { validatePath(pathB) } catch (e: SecurityException) { return err(e.message ?: "Invalid path") }
        if (!a.exists()) return err("Not found: $pathA")
        if (!b.exists()) return err("Not found: $pathB")

        val linesA = a.readLines()
        val linesB = b.readLines()
        val diffs  = mutableListOf<String>()
        val maxLen = maxOf(linesA.size, linesB.size)

        for (i in 0 until maxLen) {
            val la = linesA.getOrNull(i)
            val lb = linesB.getOrNull(i)
            when {
                la == null -> diffs.add("+ [${i + 1}] $lb")
                lb == null -> diffs.add("- [${i + 1}] $la")
                la != lb   -> { diffs.add("- [${i + 1}] $la"); diffs.add("+ [${i + 1}] $lb") }
            }
            if (diffs.size > 100) { diffs.add("... (truncated)"); break }
        }

        return if (diffs.isEmpty())
            ok("✅ file_diff: Files are identical.")
        else ok("📊 file_diff: ${diffs.size / 2} differing line(s)\n${diffs.joinToString("\n")}")
    }

    // ── File Exists check ─────────────────────────────────────────────────────
    fun toolFileExists(path: String): AgentStep {
        if (path.isBlank()) return err("file_exists: path required")
        val f = try { validatePath(path) } catch (e: SecurityException) { return err(e.message ?: "Invalid path") }
        return ok("${if (f.exists()) "✅" else "❌"} ${f.absolutePath} — exists: ${f.exists()}${if (f.exists()) ", isDir: ${f.isDirectory}" else ""}")
    }
}
