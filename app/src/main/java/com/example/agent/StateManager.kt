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
    val isPlanned: Boolean = false,
    val hasThought: Boolean = false
)

class StateManager {
    private var _state = AgentState()
    val state get() = _state

    fun setWorkflow(type: WorkflowType) {
        _state = _state.copy(workflowType = type)
    }

    fun recordStep(toolName: String, result: String, isError: Boolean = false) {
        _state = _state.copy(
            stepCount     = _state.stepCount + 1,
            errorCount    = if (isError) _state.errorCount + 1 else _state.errorCount,
            toolsUsed     = _state.toolsUsed + toolName,
            lastToolName  = toolName,
            lastToolResult = result.take(500)
        )
    }

    fun recordFileRead(path: String) {
        _state = _state.copy(filesRead = _state.filesRead + path)
    }

    fun recordFileWrite(path: String) {
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

    fun reset() { _state = AgentState() }

    fun summary(): String = buildString {
        appendLine("Steps: ${_state.stepCount} | Errors: ${_state.errorCount} | Workflow: ${_state.workflowType.name}")
        if (_state.toolsUsed.isNotEmpty())
            appendLine("Tools: ${_state.toolsUsed.takeLast(5).joinToString(" → ")}")
        if (_state.filesRead.isNotEmpty())
            appendLine("Read: ${_state.filesRead.size} file(s)")
        if (_state.filesWritten.isNotEmpty())
            appendLine("Written: ${_state.filesWritten.joinToString(", ")}")
    }.trim()
}
