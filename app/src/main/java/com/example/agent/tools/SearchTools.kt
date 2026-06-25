package com.example.agent.tools

import com.example.data.model.AgentStep
import java.io.File

class SearchTools(private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)
    private fun sz(b: Long) = when {
        b < 1024      -> "${b}B"
        b < 1024*1024 -> "${b/1024}KB"
        else          -> "%.1fMB".format(b/1024.0/1024.0)
    }

    fun toolProjectOverview(dir: String): AgentStep {
        val root = File(dir.ifBlank { getWorkingDir() })
        if (!root.exists()) return err("Dir not found: $dir")
        val files = root.walkTopDown()
            .onEnter { it.name !in setOf(".git", ".gradle", "build", "node_modules") }
            .filter { it.isFile }
            .take(1500)
            .toList()
        val dirs = root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
        val extCounts = files
            .mapNotNull { it.extension.takeIf { ext -> ext.isNotBlank() }?.lowercase() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(10)
        val important = listOf(
            "settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts",
            "README.md", "package.json", "Cargo.toml", "pyproject.toml",
            "AndroidManifest.xml"
        ).mapNotNull { name ->
            files.firstOrNull { it.name == name || it.path.endsWith("/$name") }
        }.distinctBy { it.absolutePath }.take(20)

        return ok(buildString {
            appendLine("🧭 project_overview: ${root.absolutePath}")
            appendLine("Top dirs: ${dirs.take(20).joinToString(", ").ifBlank { "(none)" }}")
            appendLine("Files scanned: ${files.size}")
            appendLine("Languages/files: ${extCounts.joinToString(", ") { "${it.key}:${it.value}" }.ifBlank { "(none)" }}")
            if (important.isNotEmpty()) {
                appendLine("Important files:")
                important.forEach { appendLine("  ${it.absolutePath} (${sz(it.length())})") }
            }
        }.trimEnd())
    }

    fun toolTodoScan(dir: String, maxResults: Int): AgentStep {
        val root = File(dir.ifBlank { getWorkingDir() })
        if (!root.exists()) return err("Dir not found: $dir")
        val regex = Regex("""\b(TODO|FIXME|HACK|BUG|XXX)\b[:\s-]*(.*)""", RegexOption.IGNORE_CASE)
        val limit = maxResults.coerceIn(1, 200)
        val hits = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { it.name !in setOf(".git", ".gradle", "build", "node_modules") }
            .filter { it.isFile && it.length() < 1_000_000L }
            .take(1000)
            .forEach { f ->
                try {
                    f.readLines().forEachIndexed { i, line ->
                        if (regex.containsMatchIn(line)) {
                            hits.add("${f.absolutePath}:${i + 1}: ${line.trim().take(140)}")
                            if (hits.size >= limit) return@forEach
                        }
                    }
                } catch (_: Exception) {}
            }
        return if (hits.isEmpty()) ok("✅ todo_scan: no TODO/FIXME/HACK/BUG markers found")
        else ok("📝 todo_scan: ${hits.size} marker(s)\n${hits.joinToString("\n")}")
    }

    // ── Project Search (filename + content) ───────────────────────────────────
    fun toolSearchFiles(dir: String, query: String): AgentStep {
        val root = File(dir.ifBlank { getWorkingDir() })
        if (!root.exists()) return err("Dir not found: $dir")
        if (query.isBlank()) return err("project_search: query required")
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile }.take(500).forEach { f ->
            when {
                f.name.contains(query, true) -> hits.add("📄 [name] ${f.absolutePath}")
                f.length() < 500_000L -> try {
                    if (f.readText().contains(query, true)) hits.add("📝 [content] ${f.absolutePath}")
                } catch (_: Exception) {}
            }
        }
        return if (hits.isEmpty()) ok("🔍 project_search: No matches for \"$query\"")
        else ok("🔍 project_search [\"$query\"]: ${hits.size} match(es)\n${hits.take(40).joinToString("\n")}")
    }

    // ── Regex Search (grep) ───────────────────────────────────────────────────
    fun toolGrep(dir: String, pattern: String, glob: String): AgentStep {
        if (pattern.isBlank()) return err("regex_search: pattern required")
        val root = File(dir.ifBlank { getWorkingDir() })
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
        return if (hits.isEmpty()) ok("🔍 regex_search: no matches for /$pattern/")
        else ok("🔍 regex_search: ${hits.size} match(es)\n${hits.joinToString("\n")}")
    }

    // ── Semantic Search (relevance-scored keyword search) ─────────────────────
    fun toolSemanticSearch(dir: String, query: String): AgentStep {
        val root = File(dir.ifBlank { getWorkingDir() })
        if (!root.exists()) return err("Dir not found: $dir")
        if (query.isBlank()) return err("semantic_search: query required")

        // Tokenize query into keywords
        val stopWords = setOf("the", "a", "an", "is", "in", "on", "at", "to", "for",
                               "of", "and", "or", "with", "from", "this", "that")
        val keywords = query.lowercase()
            .split(Regex("[\\s,;.!?]+"))
            .filter { it.length > 2 && it !in stopWords }

        if (keywords.isEmpty()) return toolSearchFiles(dir, query)

        data class Hit(val path: String, val score: Int, val snippets: List<String>)
        val hits = mutableListOf<Hit>()

        root.walkTopDown()
            .filter { it.isFile && it.length() < 500_000L }
            .take(400)
            .forEach { f ->
                var score     = 0
                val snippets  = mutableListOf<String>()

                // Filename bonus
                keywords.forEach { kw -> if (f.name.contains(kw, true)) score += 3 }

                // Content scoring
                if (f.length() < 300_000L) {
                    try {
                        f.readLines().take(200).forEachIndexed { i, line ->
                            val lower = line.lowercase()
                            var lineScore = 0
                            keywords.forEach { kw -> if (lower.contains(kw)) lineScore++ }
                            if (lineScore > 0) {
                                score += lineScore
                                if (snippets.size < 3) snippets.add("  L${i+1}: ${line.trim().take(80)}")
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (score > 0) hits.add(Hit(f.absolutePath, score, snippets))
            }

        if (hits.isEmpty()) return ok("🔍 semantic_search [\"$query\"]: No relevant files found.")

        val sorted = hits.sortedByDescending { it.score }.take(15)
        val sb = StringBuilder("🔍 semantic_search [\"$query\"]: ${sorted.size} relevant file(s)\n")
        sorted.forEach { h ->
            sb.appendLine("  ⭐ score=${h.score}  ${h.path}")
            h.snippets.forEach { sb.appendLine(it) }
        }

        return ok(sb.trimEnd().toString())
    }
}
