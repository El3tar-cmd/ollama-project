package com.example.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Execution phase of the agent lifecycle ────────────────────────────────────
enum class AgentPhase { RECALL, PLAN, EXECUTE, VERIFY, REFLECT, COMPLETE }

// ── Individual tool execution record ─────────────────────────────────────────
data class ToolRecord(
    val toolName: String,
    val args: String,
    val resultSummary: String,
    val isError: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

// ── Full agent state snapshot ─────────────────────────────────────────────────
data class AgentState(
    val phase: AgentPhase = AgentPhase.RECALL,
    val stepCount: Int = 0,
    val totalErrors: Int = 0,
    val consecutiveErrors: Int = 0,
    val toolsUsed: List<String> = emptyList(),
    val toolHistory: List<ToolRecord> = emptyList(),
    val filesRead: Set<String> = emptySet(),
    val filesWritten: Set<String> = emptySet(),
    val filesVerified: Set<String> = emptySet(),
    val hasThought: Boolean = false,
    val hasPlan: Boolean = false,
    val hasVerified: Boolean = false,
    val hasReflected: Boolean = false,
    val taskMilestones: List<String> = emptyList(),
    val accomplishments: List<String> = emptyList(),
    val qualityScore: Int = 100,
    val lastToolName: String = "",
    val lastToolWasError: Boolean = false,
    val lastToolResult: String = "",
    val errorToolCounts: Map<String, Int> = emptyMap(),
    val planSteps: List<String> = emptyList(),
    val completedPlanSteps: Set<Int> = emptySet()
)

class StateManager {
    private var _state = AgentState()
    val state get() = _state

    private val df = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun ts() = df.format(Date())

    fun reset() { _state = AgentState() }

    fun setPhase(phase: AgentPhase) {
        _state = _state.copy(phase = phase)
    }

    fun setPlan(steps: List<String>) {
        _state = _state.copy(
            planSteps = steps,
            hasPlan   = true,
            phase     = AgentPhase.EXECUTE
        )
    }

    fun completePlanStep(index: Int) {
        val done = _state.completedPlanSteps + index
        val allDone = _state.planSteps.indices.all { it in done }
        _state = _state.copy(
            completedPlanSteps = done,
            phase = if (allDone) AgentPhase.VERIFY else _state.phase
        )
    }

    fun markThought() {
        _state = _state.copy(hasThought = true)
    }

    fun markVerified() {
        _state = _state.copy(hasVerified = true, phase = AgentPhase.REFLECT)
    }

    fun markReflected() {
        _state = _state.copy(hasReflected = true)
    }

    fun addMilestone(description: String) {
        _state = _state.copy(
            taskMilestones = _state.taskMilestones + "[${ ts() }] $description"
        )
    }

    fun recordFileRead(path: String) {
        if (path.isBlank()) return
        _state = _state.copy(filesRead = _state.filesRead + path)
    }

    fun recordFileWrite(path: String) {
        if (path.isBlank()) return
        _state = _state.copy(
            filesWritten = _state.filesWritten + path,
            qualityScore = (_state.qualityScore - 0).coerceAtLeast(0)
        )
    }

    fun recordFileVerified(path: String) {
        if (path.isBlank()) return
        _state = _state.copy(filesVerified = _state.filesVerified + path)
    }

    fun recordStep(toolName: String, result: String, isError: Boolean = false) {
        val record = ToolRecord(
            toolName      = toolName,
            args          = "",
            resultSummary = result.take(300),
            isError       = isError
        )

        val newErrorCounts = if (isError) {
            val prev = _state.errorToolCounts[toolName] ?: 0
            _state.errorToolCounts + (toolName to prev + 1)
        } else {
            _state.errorToolCounts
        }

        val newQuality = if (isError) (_state.qualityScore - 5).coerceAtLeast(0)
                        else (_state.qualityScore + 1).coerceAtMost(100)

        val accomplishment = when {
            !isError && toolName in setOf("write_file", "file_writer", "create_file", "multi_file_writer") ->
                "✓ Wrote ${result.take(60)}"
            !isError && toolName in setOf("line_editor", "multi_line_editor", "replace_in_file") ->
                "✓ Edited ${result.take(60)}"
            !isError && toolName in setOf("terminal_executor", "bash") ->
                "✓ Ran: ${result.lines().firstOrNull()?.take(60) ?: ""}"
            !isError && toolName in setOf("lint") ->
                "✓ Linted: ${result.take(60)}"
            !isError && toolName in setOf("git_commit") ->
                "✓ Committed: ${result.take(60)}"
            else -> null
        }

        _state = _state.copy(
            stepCount        = _state.stepCount + 1,
            totalErrors      = if (isError) _state.totalErrors + 1 else _state.totalErrors,
            consecutiveErrors = if (isError) _state.consecutiveErrors + 1 else 0,
            toolsUsed        = _state.toolsUsed + toolName,
            toolHistory      = (_state.toolHistory + record).takeLast(40),
            lastToolName     = toolName,
            lastToolWasError = isError,
            lastToolResult   = result.take(500),
            errorToolCounts  = newErrorCounts,
            qualityScore     = newQuality,
            accomplishments  = if (accomplishment != null)
                                _state.accomplishments + accomplishment
                               else _state.accomplishments
        )
    }

    fun hasReadFile(path: String) = path in _state.filesRead
    fun hasWrittenFile(path: String) = path in _state.filesWritten
    fun hasVerifiedFile(path: String) = path in _state.filesVerified

    fun recentTools(n: Int = 10): List<String> = _state.toolsUsed.takeLast(n)

    fun errorCountForTool(toolName: String): Int = _state.errorToolCounts[toolName] ?: 0

    fun isToolRepeatedExcessively(toolName: String, threshold: Int = 3): Boolean =
        errorCountForTool(toolName) >= threshold

    fun pendingPlanSteps(): List<Pair<Int, String>> =
        _state.planSteps.mapIndexedNotNull { i, step ->
            if (i !in _state.completedPlanSteps) i to step else null
        }

    fun allPlanStepsCompleted(): Boolean =
        _state.planSteps.isNotEmpty() &&
        _state.planSteps.indices.all { it in _state.completedPlanSteps }

    fun summary(): String = buildString {
        append("[${_state.phase}] Step:${_state.stepCount}")
        append(" Err:${_state.totalErrors}")
        if (_state.consecutiveErrors > 0) append("(${_state.consecutiveErrors} consec)")
        if (_state.toolsUsed.isNotEmpty())
            append(" | Last:${_state.toolsUsed.takeLast(3).joinToString("→")}")
        if (_state.filesRead.isNotEmpty())   append(" | Read:${_state.filesRead.size}f")
        if (_state.filesWritten.isNotEmpty()) append(" | Wrote:${_state.filesWritten.size}f")
        if (_state.filesVerified.isNotEmpty()) append(" | Verified:${_state.filesVerified.size}f")
        if (_state.qualityScore < 80) append(" | Quality:${_state.qualityScore}")
        if (_state.accomplishments.isNotEmpty()) {
            appendLine()
            append("Done: ${_state.accomplishments.takeLast(4).joinToString(" | ")}")
        }
    }.trim()

    fun fullSummary(): String = buildString {
        appendLine("══════════ Agent Progress ══════════")
        appendLine("Phase  : ${_state.phase}")
        appendLine("Steps  : ${_state.stepCount} | Errors: ${_state.totalErrors} | Quality: ${_state.qualityScore}/100")
        if (_state.planSteps.isNotEmpty()) {
            appendLine("Plan   : ${_state.completedPlanSteps.size}/${_state.planSteps.size} steps done")
            _state.planSteps.forEachIndexed { i, s ->
                val done = if (i in _state.completedPlanSteps) "✓" else "○"
                appendLine("  $done ${i + 1}. $s")
            }
        }
        if (_state.filesRead.isNotEmpty())
            appendLine("Read   : ${_state.filesRead.joinToString { it.substringAfterLast('/') }}")
        if (_state.filesWritten.isNotEmpty())
            appendLine("Wrote  : ${_state.filesWritten.joinToString { it.substringAfterLast('/') }}")
        if (_state.filesVerified.isNotEmpty())
            appendLine("Verifed: ${_state.filesVerified.joinToString { it.substringAfterLast('/') }}")
        if (_state.taskMilestones.isNotEmpty()) {
            appendLine("Milestones:")
            _state.taskMilestones.forEach { appendLine("  $it") }
        }
        if (_state.accomplishments.isNotEmpty()) {
            appendLine("Accomplished:")
            _state.accomplishments.forEach { appendLine("  $it") }
        }
    }.trim()

    // ── Tool type classifiers ──────────────────────────────────────────────────
    fun isThinkTool(name: String) = name in THINK_TOOLS
    fun isReadTool(name: String)  = name in READ_TOOLS
    fun isWriteTool(name: String) = name in WRITE_TOOLS
    fun isRunTool(name: String)   = name in RUN_TOOLS
    fun isGitTool(name: String)   = name in GIT_TOOLS
    fun isVerifyTool(name: String) = name in VERIFY_TOOLS

    companion object {
        val THINK_TOOLS = setOf(
            "think", "deep_think", "sequence_thinking", "planning",
            "decompose_task", "critique", "verify_plan", "reflect",
            "context_manager"
        )
        val READ_TOOLS = setOf(
            "file_reader", "read_file", "line_reader", "read_lines",
            "head_file", "tail_file", "directory_explorer", "tree",
            "find_files", "project_search", "regex_search", "semantic_search",
            "git_status", "git_log", "git_diff", "memory_recall_all",
            "memory_recall_long", "memory_recall_short", "file_info",
            "file_exists", "file_diff", "file_outline", "code_structure",
            "todo_scan"
        )
        val WRITE_TOOLS = setOf(
            "file_writer", "write_file", "create_file", "multi_file_writer",
            "line_editor", "edit_file", "multi_line_editor", "append_file",
            "replace_all", "replace_lines", "insert_before", "insert_after",
            "delete_file", "move_file", "copy_file", "create_dir"
        )
        val RUN_TOOLS = setOf(
            "terminal_executor", "run_command", "bash", "shell",
            "run_python", "run_node", "calculate"
        )
        val GIT_TOOLS = setOf(
            "git_add", "git_commit", "git_push", "git_branch"
        )
        val VERIFY_TOOLS = setOf("lint", "git_diff")
    }
}
