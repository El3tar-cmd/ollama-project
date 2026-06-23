package com.example.agent.tools

import com.example.data.model.AgentStep
import org.json.JSONArray

class ThinkingTools {

    private fun ok(msg: String) = AgentStep("think", msg)

    /**
     * sequence_thinking — structured chain-of-thought with numbered steps.
     * {"name":"sequence_thinking","steps":["1. Understand...","2. Plan...","3. Execute..."]}
     * Or: {"name":"sequence_thinking","content":"free-form reasoning"}
     */
    fun toolSequenceThinking(steps: JSONArray?, content: String): AgentStep {
        val sb = StringBuilder("🧠 Sequence Thinking\n")

        if (steps != null && steps.length() > 0) {
            sb.appendLine("─────────────────────")
            for (i in 0 until steps.length()) {
                val step = steps.optString(i, "").trim()
                if (step.isNotBlank()) {
                    val label = if (step.matches(Regex("^\\d+\\..*"))) step
                                else "${i + 1}. $step"
                    sb.appendLine(label)
                }
            }
        } else if (content.isNotBlank()) {
            sb.appendLine("─────────────────────")
            sb.append(content)
        } else {
            return AgentStep("think", "💭 (empty thinking step)")
        }

        return ok(sb.trimEnd().toString())
    }

    /**
     * planning — create a structured execution plan.
     * {"name":"planning","task":"...","steps":["Step 1","Step 2",...]}
     */
    fun toolPlanning(task: String, steps: JSONArray?): AgentStep {
        val sb = StringBuilder("📋 Execution Plan\n")
        if (task.isNotBlank()) sb.appendLine("Task: $task")
        sb.appendLine("─────────────────────")

        if (steps != null && steps.length() > 0) {
            for (i in 0 until steps.length()) {
                val step = steps.optString(i, "").trim()
                if (step.isNotBlank()) {
                    val label = if (step.matches(Regex("^\\d+\\..*"))) step
                                else "${i + 1}. $step"
                    sb.appendLine(label)
                }
            }
        } else {
            sb.appendLine("(no steps provided)")
        }

        return AgentStep("plan", sb.trimEnd().toString())
    }

    /**
     * context_manager — summarize or trim the current context.
     * {"name":"context_manager","action":"summarize","focus":"what to keep"}
     */
    fun toolContextManager(action: String, focus: String): AgentStep {
        val act = action.ifBlank { "summarize" }
        val msg = when (act) {
            "summarize" -> "📦 Context Manager: Summarizing context. Focus: ${focus.ifBlank { "all" }}"
            "trim"      -> "✂️ Context Manager: Trimming old context to reduce token usage."
            "reset"     -> "🔄 Context Manager: Resetting context. Starting fresh from working memory."
            else        -> "📦 Context Manager: $act"
        }
        return AgentStep("think", msg)
    }
}
