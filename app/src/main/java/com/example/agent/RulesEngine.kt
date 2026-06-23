package com.example.agent

import org.json.JSONObject

data class RuleViolation(val rule: String, val advice: String)

object RulesEngine {

    private val CODE_EXTENSIONS = setOf(
        "kt", "java", "py", "js", "ts", "cpp", "c", "h",
        "go", "rs", "rb", "php", "swift", "dart", "sh", "kts"
    )

    fun validate(tool: JSONObject, state: AgentState): RuleViolation? {
        val name = tool.optString("name", "")

        // Rule 1: Read before write
        if (name in setOf("edit_file", "append_file", "multi_line_editor")) {
            val path = tool.optString("path", "")
            if (path.isNotBlank() && !state.filesRead.contains(path)) {
                return RuleViolation(
                    "read-before-write",
                    "⚠️ Rule: Read '$path' first before editing. Use file_reader or line_reader."
                )
            }
        }

        // Rule 2: Sequence thinking before first real action
        if (state.stepCount == 0 && !state.hasThought &&
            name !in setOf("sequence_thinking", "think", "memory_recall", "memory_recall_long")) {
            return RuleViolation(
                "think-first",
                "⚠️ Rule: Use sequence_thinking or think before your first action."
            )
        }

        // Rule 3: Suggest linter after writing code files
        if (name in setOf("write_file", "multi_file_writer") && state.stepCount > 2) {
            val path = tool.optString("path", "")
            val ext  = path.substringAfterLast('.', "")
            if (ext in CODE_EXTENSIONS && !state.toolsUsed.contains("lint")) {
                // Advisory only — don't block, just remind
                return null
            }
        }

        return null
    }

    fun suggestNextTool(state: AgentState): String? {
        val used = state.toolsUsed
        val written = state.filesWritten

        // After writing code, suggest linting
        if (written.isNotEmpty() && "lint" !in used) {
            val codeFile = written.firstOrNull { f ->
                f.substringAfterLast('.', "") in CODE_EXTENSIONS
            }
            if (codeFile != null) return "lint"
        }

        // After linting, suggest git_diff
        if ("lint" in used && "git_diff" !in used && written.isNotEmpty()) {
            return "git_diff"
        }

        return null
    }
}
