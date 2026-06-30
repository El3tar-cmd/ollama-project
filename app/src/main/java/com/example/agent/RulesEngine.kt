package com.example.agent

import org.json.JSONObject

data class RuleViolation(
    val rule: String,
    val advice: String,
    val blocking: Boolean = false,
    val severity: String = "advisory"
)

object RulesEngine {

    private val CODE_EXTENSIONS = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "cpp", "c", "h",
        "go", "rs", "rb", "php", "swift", "dart", "sh", "kts", "gradle", "sql"
    )

    // Dangerous commands stored as literals (NOT regex) to avoid pattern syntax crashes
    private val DANGEROUS_CMDS_LITERAL = listOf(
        "rm -rf /",
        "rm -rf /*",
        "mkfs",
        "dd if=",
        "chmod 777 /",
        ":(){:|:&};:",
        ">/dev/sda",
        ">/dev/sdb",
        ">/dev/mmcblk",
        "format c:",
        "shred /dev",
        "> /dev/null 2>&1 && curl",
        "poweroff",
        "shutdown now",
        "halt -p"
    )

    // Safe regex patterns for dangerous commands
    private val DANGEROUS_CMDS_REGEX = listOf(
        Regex("""curl\s+.+\|\s*sh"""),
        Regex("""wget\s+.+\|\s*sh"""),
        Regex("""curl\s+.+\|\s*bash"""),
        Regex("""wget\s+.+\|\s*bash"""),
        Regex("""base64\s+-d.+\|\s*bash"""),
        Regex("""eval\s+\$\(curl""")
    )

    // System paths that must never be written to
    private val FORBIDDEN_WRITE_PATHS = listOf(
        "/system", "/proc", "/dev", "/etc", "/bin", "/sbin",
        "/usr/bin", "/usr/lib", "/data/data", "/data/system"
    )

    fun validate(tool: JSONObject, state: AgentState): RuleViolation? {
        val name = tool.optString("name", "")

        // ── CRITICAL BLOCKING: Dangerous shell commands ────────────────────────
        if (name in setOf("terminal_executor", "bash", "run_command", "shell")) {
            val cmd = tool.optString("cmd", tool.optString("command", "")).lowercase()

            val literalMatch = DANGEROUS_CMDS_LITERAL.firstOrNull {
                cmd.contains(it, ignoreCase = true)
            }
            val regexMatch = if (literalMatch == null)
                DANGEROUS_CMDS_REGEX.firstOrNull { it.containsMatchIn(cmd) }?.pattern
            else null

            val dangerous = literalMatch ?: regexMatch
            if (dangerous != null) {
                return RuleViolation(
                    rule      = "dangerous-command",
                    advice    = "🚫 BLOCKED: Dangerous command pattern detected ('${dangerous.take(40)}'). Refusing to execute. Use a safer alternative.",
                    blocking  = true,
                    severity  = "critical"
                )
            }
        }

        // ── CRITICAL BLOCKING: Writing to forbidden system paths ──────────────
        if (name in setOf("write_file", "file_writer", "append_file", "create_file")) {
            val path = tool.optString("path", "")
            if (path.isNotBlank()) {
                val forbidden = FORBIDDEN_WRITE_PATHS.firstOrNull { path.startsWith(it) }
                if (forbidden != null) {
                    return RuleViolation(
                        rule     = "forbidden-path-write",
                        advice   = "🚫 BLOCKED: Cannot write to system path '$path'. All writes must be inside the working directory.",
                        blocking = true,
                        severity = "critical"
                    )
                }
            }
        }

        // ── BLOCKING: Premature complete with no real work ─────────────────────
        if (name == "complete") {
            val productive = state.filesWritten.size + state.toolsUsed.count {
                it in StateManager.RUN_TOOLS || it in StateManager.GIT_TOOLS
            }
            // Only block if the agent used NO tools at all (not even think/read).
            // Conversational or informational tasks legitimately need zero file writes.
            val usedAnyTool = state.toolsUsed.isNotEmpty()
            if (productive == 0 && !usedAnyTool && state.stepCount <= 2) {
                return RuleViolation(
                    rule     = "premature-complete",
                    advice   = "🚫 BLOCKED: You called 'complete' without doing any real work. Use think, read a file, or run a command before completing. Productive actions so far: $productive",
                    blocking = true,
                    severity = "high"
                )
            }
        }

        // ── ADVISORY: Edit without prior read ─────────────────────────────────
        if (name in setOf("line_editor", "multi_line_editor", "edit_file",
                          "append_file", "replace_in_file", "replace_lines")) {
            val path = tool.optString("path", "")
            if (path.isNotBlank() && !state.filesRead.contains(path) && !state.filesWritten.contains(path)) {
                return RuleViolation(
                    rule     = "read-before-edit",
                    advice   = "⚠️ Advisory: '$path' hasn't been read yet. Read it first with file_reader or line_reader to ensure the edit target exists.",
                    blocking = false,
                    severity = "advisory"
                )
            }
        }

        // ── ADVISORY: Too many consecutive errors ─────────────────────────────
        if (state.consecutiveErrors >= 3 && name !in StateManager.THINK_TOOLS &&
            name != "complete") {
            return RuleViolation(
                rule     = "consecutive-errors",
                advice   = "⚠️ ${state.consecutiveErrors} consecutive errors. Use deep_think to diagnose and change strategy before continuing.",
                blocking = false,
                severity = "high"
            )
        }

        // ── ADVISORY: File written but not verified ────────────────────────────
        if (name == "complete" && state.filesWritten.isNotEmpty()) {
            val unverified = state.filesWritten - state.filesVerified
            val hasLinted  = "lint" in state.toolsUsed
            val hasDiff    = "git_diff" in state.toolsUsed
            if (unverified.isNotEmpty() && !hasLinted && !hasDiff) {
                return RuleViolation(
                    rule     = "verify-before-complete",
                    advice   = "⚠️ Files were written but not verified. Run lint or git_diff to review changes before calling complete.",
                    blocking = false,
                    severity = "advisory"
                )
            }
        }

        return null
    }

    // ── Context-aware next-tool suggestions ───────────────────────────────────
    fun suggestNextTool(sm: StateManager): String? {
        val state   = sm.state
        val used    = state.toolsUsed.toSet()
        val written = state.filesWritten
        val read    = state.filesRead
        val phase   = state.phase

        // Phase-aware suggestions
        return when {
            // After writes: always lint code files first
            written.isNotEmpty() && "lint" !in used -> {
                val codeFile = written.firstOrNull { f ->
                    f.substringAfterLast('.', "") in CODE_EXTENSIONS
                }
                if (codeFile != null)
                    "lint (verify ${codeFile.substringAfterLast('/')})"
                else null
            }

            // After lint: run git_diff
            "lint" in used && "git_diff" !in used && written.isNotEmpty() ->
                "git_diff (review all changes)"

            // After many steps without exploring: explore first
            state.stepCount > 3 && read.isEmpty() && written.isEmpty() && !state.hasPlan ->
                "directory_explorer (map the project structure)"

            // After writing 3+ files: save a memory checkpoint
            written.size >= 3 && "memory_save_long" !in used && state.stepCount > 8 ->
                "memory_save_long (checkpoint progress for future sessions)"

            // When plan exists and steps remain
            state.planSteps.isNotEmpty() && !sm.allPlanStepsCompleted() -> {
                val pending = sm.pendingPlanSteps()
                if (pending.isNotEmpty())
                    "Next plan step ${pending.first().first + 1}: ${pending.first().second.take(50)}"
                else null
            }

            // After verify phase: reflect
            phase == AgentPhase.VERIFY && state.hasVerified && !state.hasReflected ->
                "reflect (record lessons learned)"

            else -> null
        }
    }

    fun isCodeFile(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in CODE_EXTENSIONS
}
