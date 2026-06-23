package com.example.agent

import org.json.JSONObject

data class RuleViolation(val rule: String, val advice: String, val blocking: Boolean = false)

object RulesEngine {

    private val CODE_EXTENSIONS = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "cpp", "c", "h",
        "go", "rs", "rb", "php", "swift", "dart", "sh", "kts", "gradle"
    )

    private val DANGEROUS_CMDS = listOf(
        "rm -rf /", "mkfs", "dd if=", "chmod 777 /", "curl.*| sh", "wget.*| sh",
        ":(){:|:&};:", ">/dev/sda", "format c:"
    )

    fun validate(tool: JSONObject, state: AgentState): RuleViolation? {
        val name = tool.optString("name", "")

        // ── BLOCKING: Dangerous shell commands ────────────────────────────────
        if (name in setOf("terminal_executor", "bash")) {
            val cmd = tool.optString("cmd", tool.optString("command", "")).lowercase()
            val dangerous = DANGEROUS_CMDS.firstOrNull { pattern ->
                Regex(pattern).containsMatchIn(cmd)
            }
            if (dangerous != null) {
                return RuleViolation(
                    "dangerous-command",
                    "🚫 BLOCKED: Dangerous command pattern detected ('$dangerous'). Refusing to execute.",
                    blocking = true
                )
            }
        }

        // ── BLOCKING: Writing outside working directory ────────────────────────
        if (name in setOf("write_file", "file_writer", "append_file")) {
            val path = tool.optString("path", "")
            if (path.isNotBlank() && (path.startsWith("/system") || path.startsWith("/proc") ||
                        path.startsWith("/dev") || path.startsWith("/etc"))) {
                return RuleViolation(
                    "system-path-write",
                    "🚫 BLOCKED: Cannot write to system path '$path'. Only write inside the working directory.",
                    blocking = true
                )
            }
        }

        // ── ADVISORY: Read before write ───────────────────────────────────────
        if (name in setOf("line_editor", "multi_line_editor", "edit_file", "append_file")) {
            val path = tool.optString("path", "")
            if (path.isNotBlank() && !state.filesRead.contains(path)) {
                return RuleViolation(
                    "read-before-write",
                    "⚠️ Rule: Read '$path' before editing. Use file_reader first to avoid overwriting with wrong content.",
                    blocking = false
                )
            }
        }

        // ── ADVISORY: Think before first real action ──────────────────────────
        if (state.stepCount == 0 && !state.hasThought &&
            name !in setOf("sequence_thinking", "think", "planning",
                           "memory_recall", "memory_recall_long", "memory_recall_all")) {
            return RuleViolation(
                "think-first",
                "⚠️ Rule: Start with sequence_thinking to plan your approach.",
                blocking = false
            )
        }

        // ── ADVISORY: Max consecutive errors ─────────────────────────────────
        if (state.errorCount >= 3 && name !in setOf("sequence_thinking", "think", "complete")) {
            return RuleViolation(
                "too-many-errors",
                "⚠️ ${state.errorCount} errors so far. Use sequence_thinking to diagnose before continuing.",
                blocking = false
            )
        }

        return null
    }

    /**
     * Suggest the most impactful next tool given current state.
     * Returns null if no clear suggestion.
     */
    fun suggestNextTool(state: AgentState): String? {
        val used    = state.toolsUsed
        val written = state.filesWritten
        val read    = state.filesRead

        // After writing code files, lint if not done
        if (written.isNotEmpty() && "lint" !in used) {
            val codeFile = written.firstOrNull { f ->
                f.substringAfterLast('.', "") in CODE_EXTENSIONS
            }
            if (codeFile != null) return "lint (verify ${ codeFile.substringAfterLast('/') })"
        }

        // After lint, suggest git_diff
        if ("lint" in used && "git_diff" !in used && written.isNotEmpty()) {
            return "git_diff (review all changes)"
        }

        // After many steps without reading, suggest checking progress
        if (state.stepCount > 5 && read.isEmpty() && written.isEmpty()) {
            return "directory_explorer (explore project structure)"
        }

        // After writing multiple files, memory_save_long
        if (written.size >= 3 && "memory_save_long" !in used && state.stepCount > 8) {
            return "memory_save_long (save progress for future sessions)"
        }

        return null
    }

    /**
     * Check if a given file path is a code file that should be linted.
     */
    fun isCodeFile(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in CODE_EXTENSIONS
}
