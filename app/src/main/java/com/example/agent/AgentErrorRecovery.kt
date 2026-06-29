package com.example.agent

/**
 * Advanced error recovery engine with hierarchical error taxonomy,
 * multi-step recovery strategies, and adaptive tool suggestions.
 */
object AgentErrorRecovery {

    // ── Error severity levels ─────────────────────────────────────────────────
    enum class Severity { CRITICAL, HIGH, MEDIUM, LOW }

    data class RecoveryStrategy(
        val diagnosis: String,
        val immediateAction: String,
        val alternativeActions: List<String> = emptyList(),
        val suggestedTool: String? = null,
        val severity: Severity = Severity.MEDIUM,
        val requiresUserInput: Boolean = false
    )

    // ── Error pattern catalog ─────────────────────────────────────────────────
    private data class ErrorPattern(
        val keywords: List<String>,
        val strategy: RecoveryStrategy
    )

    private val patterns = listOf(

        // ── Filesystem errors ─────────────────────────────────────────────────
        ErrorPattern(
            listOf("permission denied", "eacces", "eperm", "access denied"),
            RecoveryStrategy(
                diagnosis       = "Filesystem permission denied",
                immediateAction = "Check file permissions with 'ls -la <path>' via terminal_executor",
                alternativeActions = listOf(
                    "Verify the path is inside the working directory",
                    "Use 'chmod +x <file>' if the file needs to be executable",
                    "Check if the parent directory is writable"
                ),
                suggestedTool = "terminal_executor",
                severity      = Severity.HIGH
            )
        ),

        ErrorPattern(
            listOf("no such file", "not found", "enoent", "does not exist", "file not found"),
            RecoveryStrategy(
                diagnosis       = "File or path does not exist",
                immediateAction = "Use directory_explorer or find_files to locate the correct path",
                alternativeActions = listOf(
                    "Run 'find . -name <filename>' via terminal_executor",
                    "Use project_search to locate by content keyword",
                    "Check if the file needs to be created first"
                ),
                suggestedTool = "find_files",
                severity      = Severity.MEDIUM
            )
        ),

        ErrorPattern(
            listOf("file too large", "too large", "out of memory", "oom", "cannot allocate"),
            RecoveryStrategy(
                diagnosis       = "File is too large to process in one call",
                immediateAction = "Use line_reader with start/end range to read sections",
                alternativeActions = listOf(
                    "Use head_file to read only the beginning",
                    "Use tail_file to read the end",
                    "Use file_outline to get structural overview first",
                    "Use regex_search or project_search to find specific content"
                ),
                suggestedTool = "line_reader",
                severity      = Severity.MEDIUM
            )
        ),

        ErrorPattern(
            listOf("directory not empty", "rmdir", "not empty"),
            RecoveryStrategy(
                diagnosis       = "Directory not empty — cannot delete with simple rmdir",
                immediateAction = "Use delete_file which handles recursive deletion",
                alternativeActions = listOf(
                    "Run 'rm -rf <path>' via terminal_executor for force-delete",
                    "First list contents with directory_explorer to confirm deletion is safe"
                ),
                suggestedTool = "terminal_executor",
                severity      = Severity.LOW
            )
        ),

        // ── Text/edit errors ──────────────────────────────────────────────────
        ErrorPattern(
            listOf("text not found", "old text not found", "pattern not found",
                   "not found in file", "occurrence"),
            RecoveryStrategy(
                diagnosis       = "The exact text to replace was not found in the file",
                immediateAction = "Read the file with file_reader or line_reader to get exact current content",
                alternativeActions = listOf(
                    "Use regex_search to find where the target text is located",
                    "The file may have been modified — re-read it before editing again",
                    "Check for extra whitespace, indentation, or line ending differences"
                ),
                suggestedTool = "file_reader",
                severity      = Severity.MEDIUM
            )
        ),

        // ── Syntax/parse errors ───────────────────────────────────────────────
        ErrorPattern(
            listOf("syntax error", "parse error", "unexpected token", "compilation error",
                   "compile error", "cannot find symbol", "unresolved reference"),
            RecoveryStrategy(
                diagnosis       = "Syntax or compilation error in code",
                immediateAction = "Read the file around the error line with line_reader and fix the syntax",
                alternativeActions = listOf(
                    "Use lint tool to get detailed error list",
                    "Run './gradlew :app:compileDebugKotlin 2>&1 | tail -30' for Kotlin errors",
                    "Check imports and package declarations"
                ),
                suggestedTool = "lint",
                severity      = Severity.HIGH
            )
        ),

        ErrorPattern(
            listOf("invalid json", "malformed json", "json parse", "jsonexception"),
            RecoveryStrategy(
                diagnosis       = "Invalid JSON in tool call or file",
                immediateAction = "Carefully check all JSON brackets, quotes, and commas",
                alternativeActions = listOf(
                    "Use single-line JSON for simple tool calls",
                    "Escape special characters in string values",
                    "Break complex JSON into multiple simpler calls"
                ),
                severity = Severity.MEDIUM
            )
        ),

        // ── Runtime errors ────────────────────────────────────────────────────
        ErrorPattern(
            listOf("timed out", "timeout", "deadline exceeded", "took too long"),
            RecoveryStrategy(
                diagnosis       = "Operation timed out",
                immediateAction = "Break the operation into smaller chunks",
                alternativeActions = listOf(
                    "Use line_reader instead of reading entire large files",
                    "Add '2>&1 | head -50' to terminal commands to limit output",
                    "Run background tasks with '&' if appropriate"
                ),
                severity = Severity.HIGH
            )
        ),

        ErrorPattern(
            listOf("connection refused", "connect failed", "network error",
                   "unable to connect", "host unreachable", "no route to host"),
            RecoveryStrategy(
                diagnosis       = "Network or connection error",
                immediateAction = "Verify the server/service is running and the URL is correct",
                alternativeActions = listOf(
                    "Check if Ollama/server is running via git_status or terminal_executor",
                    "Try with fetch_url using a simple health check endpoint",
                    "The local LLM server may need to be started first"
                ),
                suggestedTool = "terminal_executor",
                severity      = Severity.HIGH
            )
        ),

        // ── Runtime not found ─────────────────────────────────────────────────
        ErrorPattern(
            listOf("python not found", "python3 not found", "command not found: python",
                   "no such program", "runtime not found"),
            RecoveryStrategy(
                diagnosis       = "Python runtime not available in current environment",
                immediateAction = "Python requires Embedded Linux. Check via terminal_executor: 'which python3'",
                alternativeActions = listOf(
                    "Install Embedded Linux from the Server tab first",
                    "Use terminal_executor with shell alternatives",
                    "For simple calculations, use the calculate tool instead"
                ),
                suggestedTool = "terminal_executor",
                severity      = Severity.MEDIUM
            )
        ),

        ErrorPattern(
            listOf("node not found", "node: not found", "npm not found"),
            RecoveryStrategy(
                diagnosis       = "Node.js runtime not available",
                immediateAction = "Node.js requires Embedded Linux. Run 'which node' via terminal_executor",
                alternativeActions = listOf(
                    "Install Embedded Linux from the Server tab first",
                    "Check if nvm or n is available for Node.js management"
                ),
                suggestedTool = "terminal_executor",
                severity      = Severity.MEDIUM
            )
        ),

        // ── Git errors ────────────────────────────────────────────────────────
        ErrorPattern(
            listOf("not a git repository", "fatal: not a git"),
            RecoveryStrategy(
                diagnosis       = "Not inside a git repository",
                immediateAction = "Initialize git: run 'git init' via terminal_executor",
                alternativeActions = listOf(
                    "Check the working directory path is correct",
                    "The project may need 'git init && git remote add origin <url>'"
                ),
                suggestedTool = "terminal_executor",
                severity      = Severity.HIGH
            )
        ),

        ErrorPattern(
            listOf("merge conflict", "conflict", "both modified"),
            RecoveryStrategy(
                diagnosis       = "Git merge conflict detected",
                immediateAction = "Read the conflicting file with file_reader and resolve conflict markers",
                alternativeActions = listOf(
                    "Use git_diff to see what conflicts exist",
                    "Look for '<<<<<<', '======', and '>>>>>>' markers in the file",
                    "After resolving, stage with git_add and then commit"
                ),
                suggestedTool = "file_reader",
                severity      = Severity.HIGH
            )
        ),

        // ── Path traversal / security ─────────────────────────────────────────
        ErrorPattern(
            listOf("path traversal", "outside the workspace", "security", "forbidden path"),
            RecoveryStrategy(
                diagnosis       = "Attempted to access path outside the workspace boundary",
                immediateAction = "All file operations must use paths inside the working directory",
                alternativeActions = listOf(
                    "Use relative paths or paths starting with the workingDir variable",
                    "Use directory_explorer to navigate within the workspace"
                ),
                severity        = Severity.CRITICAL,
                requiresUserInput = true
            )
        ),

        // ── Build errors ──────────────────────────────────────────────────────
        ErrorPattern(
            listOf("build failed", "gradle failed", "task failed", "error: "),
            RecoveryStrategy(
                diagnosis       = "Build or task execution failed",
                immediateAction = "Read the full error output — it contains the exact failure reason",
                alternativeActions = listOf(
                    "Run './gradlew :app:compileDebugKotlin 2>&1 | tail -40' for detailed Kotlin errors",
                    "Use lint on the modified files",
                    "Check if all required imports are present in modified files"
                ),
                suggestedTool = "lint",
                severity      = Severity.HIGH
            )
        ),

        // ── Unknown tool ──────────────────────────────────────────────────────
        ErrorPattern(
            listOf("unknown tool", "tool not found", "no tool"),
            RecoveryStrategy(
                diagnosis       = "Tool name not recognized",
                immediateAction = "Check the list of available tools in the system prompt and use exact tool names",
                alternativeActions = listOf(
                    "Common alternatives: file_reader, line_reader, terminal_executor, write_file",
                    "Use sequence_thinking to plan before choosing tools"
                ),
                severity = Severity.LOW
            )
        )
    )

    // ── Cycle detection ───────────────────────────────────────────────────────
    fun detectCycle(recentTools: List<String>, windowSize: Int = 8): Boolean {
        if (recentTools.size < windowSize) return false
        val window = recentTools.takeLast(windowSize)
        val unique = window.toSet()
        // Stuck if same tool repeats or only 2 tools alternate
        return unique.size == 1 || (unique.size <= 2 && windowSize >= 6)
    }

    fun detectStuckPattern(recentTools: List<String>): String? {
        if (recentTools.size < 4) return null
        val window = recentTools.takeLast(8)
        val unique = window.toSet()
        return when {
            unique.size == 1 -> "Repeating '${unique.first()}' only"
            unique.size == 2 -> "Alternating between '${unique.elementAt(0)}' and '${unique.elementAt(1)}'"
            window.count { it == "sequence_thinking" } >= 4 -> "Stuck in thinking loop"
            else -> null
        }
    }

    // ── Main analysis and recovery builder ───────────────────────────────────
    fun analyze(toolName: String, errorMsg: String): RecoveryStrategy? {
        val err = errorMsg.lowercase()
        for (pattern in patterns) {
            if (pattern.keywords.any { err.contains(it) }) {
                return pattern.strategy
            }
        }
        return null
    }

    fun buildRecoveryMessage(toolName: String, errorMsg: String): String {
        val strategy = analyze(toolName, errorMsg)
        return buildString {
            appendLine("❌ Tool '$toolName' failed:")
            appendLine("   ${errorMsg.take(250)}")
            appendLine()
            if (strategy != null) {
                appendLine("📍 Diagnosis: ${strategy.diagnosis}")
                appendLine("🔧 Immediate action: ${strategy.immediateAction}")
                if (strategy.alternativeActions.isNotEmpty()) {
                    appendLine("📋 Alternatives:")
                    strategy.alternativeActions.forEach { appendLine("   • $it") }
                }
                if (strategy.suggestedTool != null)
                    appendLine("➡️  Try tool: ${strategy.suggestedTool}")
                if (strategy.severity == Severity.CRITICAL)
                    appendLine("🚨 CRITICAL: This may require user intervention.")
            } else {
                appendLine("📍 No specific recovery pattern matched.")
                appendLine("🔧 Steps:")
                appendLine("   1. Read the error output carefully for clues")
                appendLine("   2. Use deep_think to diagnose the root cause")
                appendLine("   3. Try a different approach or tool")
                appendLine("   4. Do NOT repeat the exact same failing call")
            }
        }
    }

    fun cycleRecoveryMessage(pattern: String): String = buildString {
        appendLine("🔄 STUCK PATTERN DETECTED: $pattern")
        appendLine("You must change strategy NOW. Options:")
        appendLine("  1. Use deep_think to re-analyze the situation from scratch")
        appendLine("  2. Read a different file or try a different tool entirely")
        appendLine("  3. If the task is blocked, explain the blocker clearly")
        appendLine("  4. If done, call 'complete' with a verified summary")
        appendLine("DO NOT continue the same pattern.")
    }
}
