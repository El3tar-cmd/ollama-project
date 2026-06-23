package com.example.agent

data class AgentState(
    val workflowType: WorkflowType = WorkflowType.MEDIUM,
    val stepCount: Int = 0,
    val errorCount: Int = 0,
    val toolsUsed: List<String> = emptyList(),
    val filesRead: Set<String> = emptySet(),
    val filesWritten: Set<String> = emptySet(),
    val planSteps: List<String> = emptyList(),
    val completedSteps: List<String> = emptyList(),
    val lastToolName: String = "",
    val lastToolResult: String = "",
    val lastToolWasError: Boolean = false,
    val isPlanned: Boolean = false,
    val hasThought: Boolean = false,
    val accomplishments: List<String> = emptyList()  // human-readable log of what was done
)

class StateManager {
    private var _state = AgentState()
    val state get() = _state

    fun setWorkflow(type: WorkflowType) {
        _state = _state.copy(workflowType = type)
    }

    fun recordStep(toolName: String, result: String, isError: Boolean = false) {
        val accomplishment = if (!isError && toolName !in setOf("sequence_thinking", "think")) {
            when {
                toolName.startsWith("file_reader") || toolName.startsWith("read") ->
                    "Read: ${result.take(60).lines().first()}"
                toolName.startsWith("write_file") || toolName.startsWith("file_writer") ->
                    "Wrote file: ${result.take(60)}"
                toolName.startsWith("line_editor") || toolName.startsWith("multi_line") ->
                    "Edited: ${result.take(60)}"
                toolName == "lint"     -> "Linted: ${result.take(60)}"
                toolName == "git_diff" -> "Reviewed diff"
                toolName.startsWith("terminal") || toolName == "bash" ->
                    "Shell: ${result.take(60).lines().firstOrNull() ?: ""}"
                else -> null
            }
        } else null

        _state = _state.copy(
            stepCount        = _state.stepCount + 1,
            errorCount       = if (isError) _state.errorCount + 1 else _state.errorCount,
            toolsUsed        = _state.toolsUsed + toolName,
            lastToolName     = toolName,
            lastToolResult   = result.take(600),
            lastToolWasError = isError,
            accomplishments  = if (accomplishment != null) _state.accomplishments + accomplishment
                               else _state.accomplishments
        )
    }

    fun recordFileRead(path: String) {
        if (path.isBlank()) return
        _state = _state.copy(filesRead = _state.filesRead + path)
    }

    fun recordFileWrite(path: String) {
        if (path.isBlank()) return
        _state = _state.copy(filesWritten = _state.filesWritten + path)
    }

    fun setPlan(steps: List<String>) {
        _state = _state.copy(planSteps = steps, isPlanned = true)
    }

    fun completeStep(step: String) {
        _state = _state.copy(completedSteps = _state.completedSteps + step)
    }

    fun markThought() {
        _state = _state.copy(hasThought = true)
    }

    fun hasReadFile(path: String) = path in _state.filesRead

    fun recentTools(n: Int = 8): List<String> = _state.toolsUsed.takeLast(n)

    fun consecutiveErrors(): Int {
        var count = 0
        for (tool in _state.toolsUsed.reversed()) {
            if (_state.lastToolWasError) count++ else break
        }
        return count
    }

    fun reset() { _state = AgentState() }

    fun summary(): String = buildString {
        append("Steps:${_state.stepCount} Errors:${_state.errorCount} Workflow:${_state.workflowType.name}")
        if (_state.toolsUsed.isNotEmpty())
            append(" | Last:${_state.toolsUsed.takeLast(4).joinToString("→")}")
        if (_state.filesRead.isNotEmpty())
            append(" | Read:${_state.filesRead.size}f")
        if (_state.filesWritten.isNotEmpty())
            append(" | Wrote:${_state.filesWritten.size}f")
        if (_state.accomplishments.isNotEmpty()) {
            appendLine()
            append("Done: ${_state.accomplishments.takeLast(3).joinToString("; ")}")
        }
    }.trim()

    fun fullSummary(): String = buildString {
        appendLine("=== Agent Progress ===")
        appendLine("Workflow: ${_state.workflowType.name} | Steps: ${_state.stepCount} | Errors: ${_state.errorCount}")
        if (_state.planSteps.isNotEmpty()) {
            appendLine("Plan (${_state.planSteps.size} steps):")
            _state.planSteps.forEachIndexed { i, s -> appendLine("  ${i+1}. $s") }
        }
        if (_state.filesRead.isNotEmpty())
            appendLine("Files read: ${_state.filesRead.joinToString(", ") { it.substringAfterLast('/') }}")
        if (_state.filesWritten.isNotEmpty())
            appendLine("Files written: ${_state.filesWritten.joinToString(", ") { it.substringAfterLast('/') }}")
        if (_state.accomplishments.isNotEmpty()) {
            appendLine("Accomplished:")
            _state.accomplishments.forEach { appendLine("  ✓ $it") }
        }
    }.trim()
}
