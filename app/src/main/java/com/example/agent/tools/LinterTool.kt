package com.example.agent.tools

import com.example.data.model.AgentStep
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

class LinterTool {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)

    fun toolLint(path: String): AgentStep {
        if (path.isBlank()) return err("lint: path required")
        val f = File(path)
        if (!f.exists()) return err("lint: file not found: $path")
        if (f.length() > 500_000L) return err("lint: file too large")

        val ext     = f.name.substringAfterLast('.', "").lowercase()
        val content = f.readText()

        return when (ext) {
            "json"                        -> lintJson(f.name, content)
            "kt", "kts"                   -> lintKotlin(f.name, content)
            "py"                          -> lintPython(f.name, content)
            "js", "ts", "jsx", "tsx"      -> lintJavaScript(f.name, content)
            "xml", "html", "htm"          -> lintXml(f.name, content)
            "yaml", "yml"                 -> lintYaml(f.name, content)
            "sh", "bash"                  -> lintShell(f.name, content)
            else -> ok("ℹ️ lint: no linter for .$ext files. Basic check: ${f.length()} bytes, ${content.lines().size} lines.")
        }
    }

    // ── JSON ──────────────────────────────────────────────────────────────────
    private fun lintJson(name: String, content: String): AgentStep {
        return try {
            when {
                content.trimStart().startsWith("[") -> JSONArray(content)
                else                               -> JSONObject(content)
            }
            ok("✅ lint [$name]: Valid JSON — ${content.lines().size} lines")
        } catch (e: JSONException) {
            err("lint [$name]: Invalid JSON — ${e.message}")
        }
    }

    // ── Kotlin ────────────────────────────────────────────────────────────────
    private fun lintKotlin(name: String, content: String): AgentStep {
        val issues = mutableListOf<String>()
        val lines  = content.lines()

        lines.forEachIndexed { i, line ->
            val ln = i + 1
            // Unmatched braces check (basic)
            if (line.trimEnd().endsWith("{") && line.contains("TODO()")) {
                issues.add("  ⚠️ L$ln: TODO inside open block")
            }
            // Common mistake: == null without ?
            if (line.contains("== null") && !line.contains("?.") && !line.contains("?:") &&
                !line.trimStart().startsWith("//")) {
                // Just advisory
            }
            // Trailing whitespace
            if (line.endsWith(" ") || line.endsWith("\t")) {
                issues.add("  ℹ️ L$ln: trailing whitespace")
            }
        }

        // Brace balance
        val opens  = content.count { it == '{' }
        val closes = content.count { it == '}' }
        if (opens != closes) issues.add("  ❌ Unbalanced braces: $opens '{' vs $closes '}'")

        val parOpen  = content.count { it == '(' }
        val parClose = content.count { it == ')' }
        if (parOpen != parClose) issues.add("  ❌ Unbalanced parentheses: $parOpen '(' vs $parClose ')'")

        return if (issues.isEmpty())
            ok("✅ lint [$name]: No obvious Kotlin issues — ${lines.size} lines")
        else
            ok("⚠️ lint [$name]: ${issues.size} issue(s)\n${issues.take(20).joinToString("\n")}")
    }

    // ── Python ────────────────────────────────────────────────────────────────
    private fun lintPython(name: String, content: String): AgentStep {
        val issues = mutableListOf<String>()
        val lines  = content.lines()

        lines.forEachIndexed { i, line ->
            val ln = i + 1
            // Tabs vs spaces
            if (line.startsWith("\t") && content.contains("    "))
                issues.add("  ⚠️ L$ln: mixed tabs/spaces indentation")
            // Bare except
            if (line.trimStart().startsWith("except:"))
                issues.add("  ⚠️ L$ln: bare 'except:' — use 'except Exception as e:'")
            // == True/False
            if (line.contains("== True") || line.contains("== False"))
                issues.add("  ℹ️ L$ln: use 'if x:' instead of '== True/False'")
        }

        // Unmatched parens/brackets
        val parens   = content.count { it == '(' } - content.count { it == ')' }
        val brackets = content.count { it == '[' } - content.count { it == ']' }
        if (parens != 0)   issues.add("  ❌ Unbalanced parentheses (diff: $parens)")
        if (brackets != 0) issues.add("  ❌ Unbalanced brackets (diff: $brackets)")

        return if (issues.isEmpty())
            ok("✅ lint [$name]: No obvious Python issues — ${lines.size} lines")
        else
            ok("⚠️ lint [$name]: ${issues.size} issue(s)\n${issues.take(20).joinToString("\n")}")
    }

    // ── JavaScript / TypeScript ───────────────────────────────────────────────
    private fun lintJavaScript(name: String, content: String): AgentStep {
        val issues = mutableListOf<String>()
        val lines  = content.lines()

        lines.forEachIndexed { i, line ->
            val ln = i + 1
            if (line.trimStart().startsWith("var "))
                issues.add("  ℹ️ L$ln: prefer 'const' or 'let' over 'var'")
            if (line.contains("console.log") && !line.trimStart().startsWith("//"))
                issues.add("  ℹ️ L$ln: console.log found")
            if (line.contains("eval("))
                issues.add("  ❌ L$ln: eval() is dangerous")
        }

        val braces = content.count { it == '{' } - content.count { it == '}' }
        if (braces != 0) issues.add("  ❌ Unbalanced braces (diff: $braces)")

        return if (issues.isEmpty())
            ok("✅ lint [$name]: No obvious JS/TS issues — ${lines.size} lines")
        else
            ok("⚠️ lint [$name]: ${issues.size} issue(s)\n${issues.take(20).joinToString("\n")}")
    }

    // ── XML / HTML ────────────────────────────────────────────────────────────
    private fun lintXml(name: String, content: String): AgentStep {
        val issues = mutableListOf<String>()
        val lines  = content.lines()

        var opens  = 0; var closes = 0
        lines.forEachIndexed { i, line ->
            opens  += Regex("<[a-zA-Z][^/!>]*[^/]>").findAll(line).count()
            closes += Regex("</[a-zA-Z]").findAll(line).count()
            closes += Regex("<[^>]*/\\s*>").findAll(line).count()
        }

        if (!content.trimStart().startsWith("<"))
            issues.add("  ⚠️ Does not start with '<'")

        return if (issues.isEmpty())
            ok("✅ lint [$name]: No obvious XML/HTML issues — ${lines.size} lines")
        else
            ok("⚠️ lint [$name]: ${issues.size} issue(s)\n${issues.joinToString("\n")}")
    }

    // ── YAML ──────────────────────────────────────────────────────────────────
    private fun lintYaml(name: String, content: String): AgentStep {
        val issues = mutableListOf<String>()
        content.lines().forEachIndexed { i, line ->
            val ln = i + 1
            if (line.contains("\t"))
                issues.add("  ❌ L$ln: YAML must not use tabs")
            if (line.trimEnd().endsWith(":") && line.trimStart().startsWith("-"))
                issues.add("  ⚠️ L$ln: possible malformed YAML key")
        }
        return if (issues.isEmpty())
            ok("✅ lint [$name]: No obvious YAML issues — ${content.lines().size} lines")
        else
            ok("⚠️ lint [$name]: ${issues.size} issue(s)\n${issues.take(20).joinToString("\n")}")
    }

    // ── Shell ─────────────────────────────────────────────────────────────────
    private fun lintShell(name: String, content: String): AgentStep {
        val issues = mutableListOf<String>()
        val lines  = content.lines()

        if (lines.firstOrNull()?.startsWith("#!") == false)
            issues.add("  ⚠️ L1: missing shebang (#!)")

        lines.forEachIndexed { i, line ->
            val ln = i + 1
            if (line.trimStart().startsWith("rm -rf /") && !line.contains("#"))
                issues.add("  ❌ L$ln: dangerous rm -rf /")
            if (line.contains("chmod 777"))
                issues.add("  ⚠️ L$ln: chmod 777 is overly permissive")
        }

        return if (issues.isEmpty())
            ok("✅ lint [$name]: No obvious shell issues — ${lines.size} lines")
        else
            ok("⚠️ lint [$name]: ${issues.size} issue(s)\n${issues.take(20).joinToString("\n")}")
    }
}
