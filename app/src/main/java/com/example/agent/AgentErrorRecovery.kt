package com.example.agent

/**
 * Analyzes tool errors and generates recovery strategies.
 * Injected into the agent context after each failure so the LLM
 * can course-correct instead of repeating the same broken call.
 */
object AgentErrorRecovery {

    data class RecoveryHint(
        val diagnosis: String,
        val action: String,
        val suggestedTool: String? = null
    )

    private val patterns = listOf(
        Triple(listOf("permission denied", "eacces", "eperm"),
            "Permission denied on file/directory.",
            "Use 'ls -la' via terminal_executor to check permissions, or verify the path is inside the working directory." to "terminal_executor"),

        Triple(listOf("no such file", "not found", "enoent"),
            "File or path does not exist.",
            "Use directory_explorer or find_files to locate the correct path before retrying." to "directory_explorer"),

        Triple(listOf("timed out", "timeout", "deadline"),
            "Operation timed out.",
            "Break the task into smaller steps. Avoid processing large files in one call." to null),

        Triple(listOf("syntax error", "parse error", "unexpected token"),
            "Syntax error in code or JSON.",
            "Review the code carefully, fix the syntax, then use lint to verify." to "lint"),

        Triple(listOf("python not found", "node not found", "runtime not found"),
            "Runtime binary not available.",
            "Use terminal_executor with bash as a fallback, or inform the user to set up Termux." to "terminal_executor"),

        Triple(listOf("not a git repository", "git repo"),
            "Not inside a git repository.",
            "Run 'git init' via terminal_executor first, then retry the git operation." to "terminal_executor"),

        Triple(listOf("out of memory", "oom", "cannot allocate"),
            "Out of memory.",
            "Process too large. Split into smaller chunks or use streaming approach." to null),

        Triple(listOf("invalid json", "malformed json", "json parse"),
            "Invalid JSON in tool call.",
            "Double-check all JSON brackets, quotes, and commas. Retry with corrected format." to null),

        Triple(listOf("connection refused", "connect failed", "network"),
            "Network or connection error.",
            "Check if the server/service is running. Use web_fetch or try again after a short wait." to null),

        Triple(listOf("directory not empty", "rmdir"),
            "Directory not empty — cannot delete.",
            "Use 'rm -rf path' via terminal_executor to force-delete a directory with contents." to "terminal_executor")
    )

    fun analyze(toolName: String, errorMsg: String): RecoveryHint? {
        val err = errorMsg.lowercase()
        for ((keywords, diagnosis, actionPair) in patterns) {
            if (keywords.any { err.contains(it) }) {
                return RecoveryHint(diagnosis, actionPair.first, actionPair.second)
            }
        }
        return null
    }

    /**
     * Build the recovery feedback message to inject into context.
     * Returns null if no specific recovery hint is available.
     */
    fun buildRecoveryMessage(toolName: String, errorMsg: String): String {
        val hint = analyze(toolName, errorMsg)
        return buildString {
            appendLine("❌ Tool '$toolName' failed: ${errorMsg.take(200)}")
            if (hint != null) {
                appendLine("📍 Diagnosis: ${hint.diagnosis}")
                appendLine("🔧 Recovery: ${hint.action}")
                if (hint.suggestedTool != null)
                    appendLine("➡️  Try: ${hint.suggestedTool}")
            } else {
                appendLine("➡️  Analyze the error and try a different approach or tool.")
            }
        }
    }

    /**
     * Detect if the agent is stuck in a cycle by looking at the last N tool names.
     */
    fun detectCycle(recentTools: List<String>, windowSize: Int = 6): Boolean {
        if (recentTools.size < windowSize) return false
        val window = recentTools.takeLast(windowSize)
        val unique = window.toSet()
        // Stuck if last N calls are all the same tool, or alternating between just 2
        return unique.size == 1 || (unique.size == 2 && windowSize >= 4)
    }

    fun cycleRecoveryMessage(stuck: String): String =
        "⚠️ Detected repetitive tool usage ('$stuck'). " +
        "Change strategy: use a different tool, re-read the task, or call complete if done."
}
