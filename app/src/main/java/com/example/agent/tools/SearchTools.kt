package com.example.agent.tools

import com.example.data.model.AgentStep
import java.io.File

class SearchTools(private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)

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
