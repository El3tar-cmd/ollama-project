package com.example.agent.tools

import com.example.data.model.AgentStep
import org.json.JSONArray

/**
 * Advanced thinking and metacognitive tools for the agent.
 *
 * Tools provided:
 * - sequence_thinking  : Numbered chain-of-thought reasoning
 * - deep_think         : Multi-layer analysis (problem → root cause → solution → risks)
 * - planning           : Create structured execution plan with numbered steps
 * - decompose_task     : Break a complex task into atomic executable subtasks
 * - critique           : Self-critique a proposed solution before executing
 * - verify_plan        : Validate a plan for completeness and feasibility
 * - reflect            : Post-execution reflection — what worked, what didn't
 * - context_manager    : Manage and summarize context state
 */
class ThinkingTools {

    private fun think(msg: String) = AgentStep("think", msg)
    private fun plan(msg: String)  = AgentStep("plan",  msg)

    // ── sequence_thinking: Numbered chain-of-thought ──────────────────────────
    /**
     * Use: {"name":"sequence_thinking","steps":["Step 1...","Step 2...","Step 3..."]}
     * Or:  {"name":"sequence_thinking","content":"free-form reasoning..."}
     */
    fun toolSequenceThinking(steps: JSONArray?, content: String): AgentStep {
        val sb = StringBuilder("🧠 Chain-of-Thought\n")
        sb.appendLine("─────────────────────────────────────")

        if (steps != null && steps.length() > 0) {
            for (i in 0 until steps.length()) {
                val step = steps.optString(i, "").trim()
                if (step.isNotBlank()) {
                    val label = if (step.matches(Regex("""^\d+[\).\s].*"""))) step
                                else "${i + 1}. $step"
                    sb.appendLine(label)
                }
            }
        } else if (content.isNotBlank()) {
            sb.append(content)
        } else {
            return think("💭 (empty thinking — provide 'steps' array or 'content' string)")
        }

        return think(sb.trimEnd().toString())
    }

    // ── deep_think: Multi-layer analysis ─────────────────────────────────────
    /**
     * Use: {"name":"deep_think","problem":"...","root_cause":"...","solution":"...","risks":"..."}
     * Or provide only "problem" and the agent fills in the rest in the response.
     */
    fun toolDeepThink(
        problem: String,
        rootCause: String,
        solution: String,
        risks: String,
        alternatives: String
    ): AgentStep {
        if (problem.isBlank()) return think("deep_think: 'problem' field required")

        val sb = StringBuilder("🔍 Deep Analysis\n")
        sb.appendLine("═══════════════════════════════════════")

        sb.appendLine("❓ PROBLEM")
        sb.appendLine(problem.trim())
        sb.appendLine()

        if (rootCause.isNotBlank()) {
            sb.appendLine("🎯 ROOT CAUSE")
            sb.appendLine(rootCause.trim())
            sb.appendLine()
        }

        if (solution.isNotBlank()) {
            sb.appendLine("✅ PROPOSED SOLUTION")
            sb.appendLine(solution.trim())
            sb.appendLine()
        }

        if (alternatives.isNotBlank()) {
            sb.appendLine("🔄 ALTERNATIVES CONSIDERED")
            sb.appendLine(alternatives.trim())
            sb.appendLine()
        }

        if (risks.isNotBlank()) {
            sb.appendLine("⚠️  RISKS & MITIGATIONS")
            sb.appendLine(risks.trim())
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.append("→ Next: Execute the proposed solution with a tool call.")

        return think(sb.trimEnd().toString())
    }

    // ── planning: Structured execution plan ──────────────────────────────────
    /**
     * Use: {"name":"planning","task":"...","steps":["Step 1","Step 2",...]}
     */
    fun toolPlanning(task: String, steps: JSONArray?): AgentStep {
        val sb = StringBuilder("📋 Execution Plan\n")
        sb.appendLine("═══════════════════════════════════════")
        if (task.isNotBlank()) {
            sb.appendLine("Task: $task")
            sb.appendLine("───────────────────────────────────────")
        }

        if (steps != null && steps.length() > 0) {
            for (i in 0 until steps.length()) {
                val step = steps.optString(i, "").trim()
                if (step.isNotBlank()) {
                    val label = if (step.matches(Regex("""^\d+[\).\s].*"""))) step
                                else "${i + 1}. $step"
                    sb.appendLine("[ ] $label")
                }
            }
        } else {
            sb.appendLine("(no steps provided — add a 'steps' array)")
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.append("→ Begin with step 1. Mark each step done before moving to the next.")
        return plan(sb.trimEnd().toString())
    }

    // ── decompose_task: Break complex task into atomic subtasks ───────────────
    /**
     * Use: {"name":"decompose_task","task":"...","subtasks":["Subtask 1","Subtask 2",...]}
     */
    fun toolDecomposeTask(task: String, subtasks: JSONArray?, constraints: String): AgentStep {
        if (task.isBlank()) return think("decompose_task: 'task' field required")

        val sb = StringBuilder("🔀 Task Decomposition\n")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("MAIN TASK: $task")
        sb.appendLine()

        if (constraints.isNotBlank()) {
            sb.appendLine("CONSTRAINTS: $constraints")
            sb.appendLine()
        }

        sb.appendLine("ATOMIC SUBTASKS:")
        if (subtasks != null && subtasks.length() > 0) {
            for (i in 0 until subtasks.length()) {
                val st = subtasks.optString(i, "").trim()
                if (st.isNotBlank()) sb.appendLine("  ${i + 1}. [ ] $st")
            }
        } else {
            sb.appendLine("  (provide 'subtasks' array to populate)")
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.append("→ Execute each subtask in order. Complete all before calling 'complete'.")
        return plan(sb.trimEnd().toString())
    }

    // ── critique: Self-critique a proposed solution ───────────────────────────
    /**
     * Use: {"name":"critique","proposal":"...","concerns":"...","verdict":"proceed|revise|abort"}
     */
    fun toolCritique(
        proposal: String,
        concerns: String,
        improvements: String,
        verdict: String
    ): AgentStep {
        if (proposal.isBlank()) return think("critique: 'proposal' field required")

        val verdictEmoji = when (verdict.lowercase()) {
            "proceed"  -> "✅ PROCEED"
            "revise"   -> "🔄 REVISE FIRST"
            "abort"    -> "🚫 ABORT — Choose different approach"
            else       -> "⚠️  REVIEW NEEDED"
        }

        val sb = StringBuilder("🔎 Self-Critique\n")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("PROPOSAL:")
        sb.appendLine(proposal.trim())
        sb.appendLine()

        if (concerns.isNotBlank()) {
            sb.appendLine("⚠️  CONCERNS:")
            sb.appendLine(concerns.trim())
            sb.appendLine()
        }

        if (improvements.isNotBlank()) {
            sb.appendLine("💡 IMPROVEMENTS:")
            sb.appendLine(improvements.trim())
            sb.appendLine()
        }

        sb.appendLine("VERDICT: $verdictEmoji")
        sb.appendLine("═══════════════════════════════════════")

        val nextAction = when (verdict.lowercase()) {
            "proceed"  -> "→ Execute the proposal now."
            "revise"   -> "→ Apply improvements, then execute."
            "abort"    -> "→ Use deep_think to find an alternative approach."
            else       -> "→ Consider the concerns before proceeding."
        }
        sb.append(nextAction)

        return think(sb.trimEnd().toString())
    }

    // ── verify_plan: Validate a plan before execution ─────────────────────────
    /**
     * Use: {"name":"verify_plan","plan_summary":"...","completeness_check":"...","feasibility_check":"..."}
     */
    fun toolVerifyPlan(
        planSummary: String,
        completenessCheck: String,
        feasibilityCheck: String,
        missingSteps: String
    ): AgentStep {
        if (planSummary.isBlank()) return think("verify_plan: 'plan_summary' required")

        val sb = StringBuilder("✅ Plan Verification\n")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("PLAN: $planSummary")
        sb.appendLine()

        if (completenessCheck.isNotBlank()) {
            sb.appendLine("📋 COMPLETENESS:")
            sb.appendLine(completenessCheck.trim())
            sb.appendLine()
        }

        if (feasibilityCheck.isNotBlank()) {
            sb.appendLine("⚙️  FEASIBILITY:")
            sb.appendLine(feasibilityCheck.trim())
            sb.appendLine()
        }

        if (missingSteps.isNotBlank()) {
            sb.appendLine("❌ MISSING / GAPS:")
            sb.appendLine(missingSteps.trim())
            sb.appendLine()
        } else {
            sb.appendLine("✓ No obvious gaps detected.")
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.append("→ If plan is verified, begin execution immediately.")

        return think(sb.trimEnd().toString())
    }

    // ── reflect: Post-execution reflection ───────────────────────────────────
    /**
     * Use: {"name":"reflect","what_worked":"...","what_failed":"...","lessons":"...","next_time":"..."}
     */
    fun toolReflect(
        whatWorked: String,
        whatFailed: String,
        lessons: String,
        nextTime: String
    ): AgentStep {
        val sb = StringBuilder("🪞 Reflection\n")
        sb.appendLine("═══════════════════════════════════════")

        if (whatWorked.isNotBlank()) {
            sb.appendLine("✅ WHAT WORKED:")
            sb.appendLine(whatWorked.trim())
            sb.appendLine()
        }

        if (whatFailed.isNotBlank()) {
            sb.appendLine("❌ WHAT FAILED:")
            sb.appendLine(whatFailed.trim())
            sb.appendLine()
        }

        if (lessons.isNotBlank()) {
            sb.appendLine("📚 LESSONS LEARNED:")
            sb.appendLine(lessons.trim())
            sb.appendLine()
        }

        if (nextTime.isNotBlank()) {
            sb.appendLine("🔄 NEXT TIME:")
            sb.appendLine(nextTime.trim())
        }

        sb.appendLine("═══════════════════════════════════════")
        sb.append("→ Consider saving key lessons to memory_save_long for future sessions.")

        return think(sb.trimEnd().toString())
    }

    // ── context_manager: Context state management hint ────────────────────────
    fun toolContextManager(action: String, focus: String): AgentStep {
        val msg = when (action.lowercase()) {
            "summarize" -> "📦 Context Summary requested. Focus: ${focus.ifBlank { "all" }}"
            "trim"      -> "✂️  Context trimming requested — older messages will be compressed."
            "reset"     -> "🔄 Context reset — starting from working memory only."
            "status"    -> "📊 Context status requested."
            else        -> "📦 Context action: $action"
        }
        return think(msg)
    }
}
