package com.example.agent.tools

import com.example.data.model.AgentStep
import java.io.File

class SearchTools(private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)

    fun toolSearchFiles(dir: String, query: String): AgentStep {
        val root = File(dir.ifBlank { getWorkingDir() })
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

    fun toolGrep(dir: String, pattern: String, glob: String): AgentStep {
        if (pattern.isBlank()) return err("grep: pattern required")
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
        return if (hits.isEmpty()) ok("🔍 grep: no matches for /$pattern/")
        else ok("🔍 grep: ${hits.size} match(es)\n${hits.joinToString("\n")}")
    }
}
