package com.example.agent

import android.util.Log
import com.example.agent.tools.MemoryTool
import com.example.agent.tools.ToolExecutor
import com.example.data.api.LlamaCppApi
import com.example.data.api.OllamaApi
import com.example.data.model.AgentStep
import com.example.data.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.resume

/**
 * APEX Agent Engine — Multi-phase autonomous execution loop.
 *
 * Phases: RECALL → PLAN → EXECUTE → VERIFY → REFLECT → COMPLETE
 *
 * Key capabilities:
 * - Adaptive step budget based on task complexity
 * - Priority-based cycle detection with multi-pattern recognition
 * - Quality gate enforcement before completion
 * - Rich state tracking across all execution phases
 * - Smart context compression with fact extraction
 * - Error escalation with root-cause injection
 * - Parallel feedback construction
 */
class AgentEngine(private val context: android.content.Context) {

    var workingDir: String = context.filesDir.absolutePath

    private val toolExecutor   by lazy { ToolExecutor(context) { workingDir } }
    private val memoryTool     by lazy { MemoryTool(context) { workingDir } }
    private val planManager    by lazy { ProjectPlanManager { workingDir } }
    private val stateManager   = StateManager()
    private val contextManager = AgentContextManager()

    // Regex for extracting <think> blocks
    private val thinkRegex     = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
    private val thinkOpenRegex = Regex("<think>[\\s\\S]*$",           RegexOption.IGNORE_CASE)

    // ── LLM streaming call ────────────────────────────────────────────────────
    private suspend fun callLlm(
        messages: List<ChatMessage>,
        model: String,
        baseUrl: String,
        cloudApiKey: String,
        backend: String
    ): Pair<Boolean, String> {
        val api      = OllamaApi()
        var response = ""
        var ok       = true
        var errMsg   = ""

        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val onTok: (String) -> Unit         = { response += it }
                val onDone: (Boolean, String) -> Unit = { s, m ->
                    if (!s) { ok = false; errMsg = m }
                    if (!cont.isCompleted) cont.resume(Unit)
                }
                val call = when {
                    cloudApiKey.isNotBlank() &&
                        (backend == "ollama-cloud" || model.endsWith(":cloud")) ->
                        api.cloudChatStream(cloudApiKey, model, messages,
                            onTokenGenerated = onTok, onComplete = onDone)
                    backend == "llamacpp" ->
                        LlamaCppApi().chatStream(baseUrl, messages,
                            onToken = onTok, onComplete = onDone)
                    else ->
                        api.chatStream(baseUrl, model, messages,
                            onTokenGenerated = onTok, onComplete = onDone)
                }
                cont.invokeOnCancellation { call.cancel() }
            }
        } catch (ex: CancellationException) { throw ex }
        catch (ex: Exception) { ok = false; errMsg = ex.message ?: "unknown error" }

        return ok to (if (ok) response else errMsg)
    }

    // ── Estimate task complexity → adaptive step budget ───────────────────────
    private fun estimateComplexity(task: String): Int {
        val lower = task.lowercase()
        val complexSignals = listOf(
            "refactor", "migrate", "rewrite", "redesign", "implement", "build",
            "add feature", "create", "debug", "fix all", "update all", "entire",
            "complete", "full", "every", "all files", "architecture", "module",
            "multiple", "several", "system"
        )
        val simpleSignals = listOf(
            "what is", "explain", "how does", "show me", "list", "describe",
            "find", "where is", "which", "tell me"
        )
        val complexScore = complexSignals.count { lower.contains(it) }
        val simpleScore  = simpleSignals.count  { lower.contains(it) }

        return when {
            simpleScore > complexScore -> 15    // Simple query
            complexScore >= 4          -> 60    // Large project task
            complexScore >= 2          -> 45    // Medium task
            task.length > 300          -> 45    // Long description → complex
            else                       -> 30    // Default
        }
    }

    // ── Main entry point ──────────────────────────────────────────────────────
    suspend fun runAgentLoop(
        userTask: String,
        model: String,
        baseUrl: String,
        maxSteps: Int       = 30,
        cloudApiKey: String = "",
        backend: String     = "ollama",
        onAskUser: (suspend (String) -> String)? = null,
        onStep: suspend (AgentStep) -> Unit
    ) {
        toolExecutor.askUserFn = onAskUser
        stateManager.reset()
        contextManager.reset()

        // Adaptive step budget
        val adaptiveMax = maxOf(maxSteps, estimateComplexity(userTask))
        onStep(AgentStep("info", "🚀 APEX Agent started · budget: $adaptiveMax steps"))

        try {
            runLoop(userTask, model, baseUrl, cloudApiKey, backend, adaptiveMax, onStep)
        } catch (ce: CancellationException) { throw ce }
        catch (e: Exception) {
            Log.e(TAG_AGENT, "Agent loop crashed", e)
            onStep(AgentStep("error",
                "❌ Agent crashed (${e::class.java.simpleName}): ${e.message?.take(200) ?: "unknown"}",
                true))
        }
    }

    // ── Internal execution loop ───────────────────────────────────────────────
    @Suppress("NAME_SHADOWING")
    private suspend fun runLoop(
        userTask: String,
        model: String,
        baseUrl: String,
        cloudApiKey: String,
        backend: String,
        maxSteps: Int,
        onStep: suspend (AgentStep) -> Unit
    ) {
        // ── Handle trivial shell-only tasks directly ──────────────────────────
        handleSimpleShellTask(userTask, onStep)?.let {
            onStep(AgentStep("info", "✅ Done."))
            return
        }

        // ── Load memory and project context ───────────────────────────────────
        val memoryContent      = try { memoryTool.loadMemoryForPrompt() } catch (_: Exception) { "" }
        val projectPlanContext = try { planManager.loadContext()         } catch (_: Exception) { "" }

        // ── Build initial message list ────────────────────────────────────────
        val messages = mutableListOf(
            ChatMessage("system", buildSystemPrompt(workingDir, memoryContent, projectPlanContext)),
            ChatMessage("user",   userTask)
        )

        // ── Loop state ────────────────────────────────────────────────────────
        var steps                   = 0
        var llmErrors               = 0
        var consecutiveThinkingOnly = 0
        var productiveActions       = 0   // writes + runs + git — not reads/thinks
        val toolSignatureCounts     = mutableMapOf<String, Int>()
        var done                    = false

        // ── Main loop ─────────────────────────────────────────────────────────
        while (steps < maxSteps && !done) {
            steps++

            // ── Context management: compress if near token limit ──────────────
            if (contextManager.isNearLimit(messages)) {
                val compressed = contextManager.compress(
                    messages, stateManager.fullSummary()
                )
                messages.clear()
                messages.addAll(compressed)
                onStep(AgentStep("info", "📦 Context compressed — continuing."))
            }

            // ── LLM call ─────────────────────────────────────────────────────
            val (streamOk, rawResponse) = callLlm(messages, model, baseUrl, cloudApiKey, backend)

            if (!streamOk) {
                llmErrors++
                onStep(AgentStep("error", "❌ LLM error: $rawResponse", true))
                if (llmErrors >= 3) {
                    onStep(AgentStep("info", "⚠️ Too many LLM errors. Stopping."))
                    break
                }
                delay(1500L)
                continue
            }
            if (rawResponse.isBlank()) {
                llmErrors++
                onStep(AgentStep("error", "❌ Empty LLM response.", true))
                if (llmErrors >= 3) break
                delay(1000L)
                continue
            }
            llmErrors = 0

            // ── Extract <think> blocks ────────────────────────────────────────
            try {
                thinkRegex.findAll(rawResponse).forEach { m ->
                    val t = m.groupValues[1].trim()
                    if (t.isNotBlank()) {
                        onStep(AgentStep("think", t))
                        stateManager.markThought()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG_AGENT, "think regex: ${e.message}")
            }

            // ── Parse tool calls ──────────────────────────────────────────────
            val toolCalls = try { parseToolCalls(rawResponse) }
            catch (e: Exception) {
                Log.w(TAG_AGENT, "parseToolCalls: ${e.message}")
                emptyList()
            }

            // ── Build displayable text (strip tool markers + think blocks) ────
            val displayText = try {
                rawResponse
                    .replace(thinkRegex, "")
                    .replace(thinkOpenRegex, "")
                    .replace(Regex("""WRITE_FILE>>[^\n]+\n[\s\S]*?<<WRITE_FILE"""), "")
                    .replace(Regex("""TOOL>>\s*\n[\s\S]*?\n?<<TOOL"""), "")
                    .replace(Regex("""```tool\s*\n[\s\S]*?\n?```"""), "")
                    .replace(Regex("""```json\s*\n[\s\S]*?\n?```"""), "")
                    .trim()
            } catch (e: PatternSyntaxException) {
                Log.w(TAG_AGENT, "display regex: ${e.message}")
                rawResponse.take(2000)
            }
            if (displayText.isNotBlank()) onStep(AgentStep("assistant", displayText))

            // ── No tool calls: decide between finish vs redirect ──────────────
            if (toolCalls.isEmpty()) {
                messages.add(ChatMessage("assistant", rawResponse))

                val isShortAnswer  = rawResponse.length < 500
                val isConversational = isShortAnswer && !containsPlanningLanguage(rawResponse)
                val isWrapUp        = productiveActions > 0 && isShortAnswer

                if (isConversational || isWrapUp) {
                    onStep(AgentStep("info", "✅ Done."))
                    break
                }

                // Redirect — agent described work but used no tools
                val redirect = buildRedirectMessage(productiveActions, steps)
                onStep(AgentStep("info", "⚡ Redirecting: no tool call detected."))
                messages.add(ChatMessage("user", redirect))
                continue
            }

            // ── Cycle detection ───────────────────────────────────────────────
            val onlyThinking = toolCalls.all { tc ->
                val n = tc.optString("name", "")
                stateManager.isThinkTool(n)
            }
            if (onlyThinking) {
                consecutiveThinkingOnly++
            } else {
                consecutiveThinkingOnly = 0
            }

            val recentTools = stateManager.recentTools(10)
            val stuckPattern = AgentErrorRecovery.detectStuckPattern(recentTools)
            if (stuckPattern != null && (stuckPattern.isNotBlank())) {
                val recovery = AgentErrorRecovery.cycleRecoveryMessage(stuckPattern)
                onStep(AgentStep("info", "🔄 Stuck pattern detected: $stuckPattern"))
                messages.add(ChatMessage("user", recovery))
            } else if (consecutiveThinkingOnly >= 4) {
                val demand = buildString {
                    appendLine("[AGENT STUCK] ${consecutiveThinkingOnly} consecutive thinking-only steps.")
                    appendLine("STOP thinking. Execute the next concrete step NOW.")
                    appendLine("Use WRITE_FILE>>, line_editor, or terminal_executor.")
                    append("Thinking alone accomplishes nothing. ACT.")
                }
                onStep(AgentStep("info", "⚠️ Thinking loop — forcing concrete action."))
                messages.add(ChatMessage("user", demand))
                consecutiveThinkingOnly = 0
            }

            // ── Execute each tool call ────────────────────────────────────────
            val resultBuf = StringBuilder()

            for (toolCall in toolCalls) {
                if (done) break

                val toolName  = toolCall.optString("name", "?")
                val signature = "$toolName|${toolCall.toString().take(300)}"
                val repeatCnt = (toolSignatureCounts[signature] ?: 0) + 1
                toolSignatureCounts[signature] = repeatCnt

                // Block pathologically repeated identical calls
                if (repeatCnt >= 3 && toolName != "complete") {
                    val blockMsg = "Identical '$toolName' call blocked (×$repeatCnt). " +
                        "The previous results did not change. " +
                        "You MUST change strategy: use a different tool, different arguments, or different approach."
                    onStep(AgentStep("info", "🚫 Repeated call blocked."))
                    resultBuf.appendLine("BLOCKED: $blockMsg")
                    messages.add(ChatMessage("user", "[RECOVERY] $blockMsg"))
                    continue
                }

                // ── Quality gate for complete ─────────────────────────────────
                if (toolName == "complete") {
                    val gate = enforceCompleteGate(productiveActions, steps)
                    if (gate != null) {
                        onStep(AgentStep("info", "🚫 Complete blocked: $gate"))
                        resultBuf.appendLine(gate)
                        messages.add(ChatMessage("user", gate))
                        continue
                    }
                }

                // ── Rules engine validation ───────────────────────────────────
                val violation = RulesEngine.validate(toolCall, stateManager.state)
                if (violation != null) {
                    onStep(AgentStep("info", violation.advice))
                    if (violation.blocking) {
                        resultBuf.appendLine("BLOCKED: ${violation.advice}")
                        stateManager.recordStep(toolName, "BLOCKED: ${violation.advice}", isError = true)
                        continue
                    }
                }

                // ── State classification ──────────────────────────────────────
                when {
                    stateManager.isThinkTool(toolName) -> stateManager.markThought()
                    stateManager.isReadTool(toolName)  ->
                        stateManager.recordFileRead(toolCall.optString("path", ""))
                    stateManager.isWriteTool(toolName) -> {
                        val path = toolCall.optString("path",
                            toolCall.optJSONArray("files")
                                ?.optJSONObject(0)?.optString("path") ?: "")
                        stateManager.recordFileWrite(path)
                        productiveActions++
                    }
                    stateManager.isRunTool(toolName) -> productiveActions++
                    stateManager.isGitTool(toolName) -> productiveActions++
                    stateManager.isVerifyTool(toolName) -> {
                        val path = toolCall.optString("path", "")
                        if (path.isNotBlank()) stateManager.recordFileVerified(path)
                        stateManager.markVerified()
                        productiveActions++
                    }
                }

                // ── Execute tool ──────────────────────────────────────────────
                onStep(AgentStep("tool_call", "🔧 $toolName"))
                val result = try {
                    toolExecutor.executeTool(toolCall)
                } catch (ex: Exception) {
                    Log.e(TAG_AGENT, "Tool '$toolName' threw", ex)
                    AgentStep("tool_result",
                        "❌ Tool '$toolName' internal error: ${ex.message ?: ex::class.java.simpleName}",
                        isError = true)
                }

                stateManager.recordStep(toolName, result.content, result.isError)

                // Cache file reads for context reuse
                if (toolName in setOf("file_reader", "read_file", "head_file", "line_reader")) {
                    val path = toolCall.optString("path", "")
                    if (path.isNotBlank() && !result.isError)
                        contextManager.cacheFile(path, result.content)
                }

                onStep(result)

                // ── Error recovery injection ──────────────────────────────────
                if (result.isError && toolName !in StateManager.THINK_TOOLS) {
                    val recovery = AgentErrorRecovery.buildRecoveryMessage(toolName, result.content)
                    resultBuf.appendLine(recovery)
                } else {
                    resultBuf.appendLine(result.content.take(1500))
                    resultBuf.appendLine("---")
                }

                // ── Milestone tracking ────────────────────────────────────────
                if (!result.isError && stateManager.isWriteTool(toolName)) {
                    val path = toolCall.optString("path", "").substringAfterLast('/')
                    if (path.isNotBlank())
                        stateManager.addMilestone("Wrote $path")
                }

                // ── Complete signal ───────────────────────────────────────────
                if (result.type == "complete") {
                    try { planManager.markFirstPendingDone("agent completed") } catch (_: Exception) {}
                    stateManager.markReflected()
                    done = true
                    break
                }
            }

            messages.add(ChatMessage("assistant", rawResponse))
            if (done) break

            // ── Build next-iteration feedback message ─────────────────────────
            val suggestion = RulesEngine.suggestNextTool(stateManager)
            val feedback   = contextManager.buildFeedbackMessage(
                toolResults      = resultBuf.toString(),
                stateSummary     = stateManager.summary(),
                nextToolHint     = suggestion,
                step             = steps,
                maxSteps         = maxSteps,
                phase            = stateManager.state.phase,
                pendingPlanSteps = stateManager.pendingPlanSteps()
            )
            messages.add(ChatMessage("user", feedback))

            // ── Hard context limit ────────────────────────────────────────────
            if (contextManager.isOverLimit(messages)) {
                val compressed = contextManager.compress(
                    messages, stateManager.fullSummary(), keepTailSize = 10
                )
                messages.clear()
                messages.addAll(compressed)
            }
        }

        if (steps >= maxSteps && !done) {
            onStep(AgentStep("info",
                "⚠️ Max steps ($maxSteps) reached.\n${stateManager.fullSummary()}"))
        }
    }

    // ── Quality gate: reject premature complete ───────────────────────────────
    private fun enforceCompleteGate(productiveActions: Int, steps: Int): String? {
        val state    = stateManager.state
        val written  = state.filesWritten
        val hasLint  = "lint"     in state.toolsUsed
        val hasDiff  = "git_diff" in state.toolsUsed

        return when {
            productiveActions == 0 && steps <= 4 ->
                "[BLOCKED] complete called with zero productive actions. " +
                "You must write/edit files or run commands first. " +
                "filesWritten=${written.size}, productiveActions=$productiveActions. " +
                "Do the actual work first, then call complete."

            written.isNotEmpty() && !hasLint && !hasDiff ->
                "[BLOCKED] Files were written but not verified. " +
                "Run lint on modified code files, then git_diff to review changes, " +
                "then call complete with a verified summary."

            !stateManager.allPlanStepsCompleted() && state.planSteps.size > 1 ->
                "[BLOCKED] Plan has ${stateManager.pendingPlanSteps().size} unfinished step(s). " +
                "Complete all plan steps before calling complete.\n" +
                "Pending: ${stateManager.pendingPlanSteps().take(3).joinToString { it.second.take(40) }}"

            else -> null
        }
    }

    // ── Redirect message builder ──────────────────────────────────────────────
    private fun buildRedirectMessage(productiveActions: Int, steps: Int): String = buildString {
        if (productiveActions == 0 && steps <= 2) {
            appendLine("[AGENT ERROR] You described work but used NO tools.")
            appendLine("In agent mode, you MUST use tool calls to act.")
            appendLine("Do NOT write plans in prose. Execute them with TOOL>> or WRITE_FILE>>.")
            append("Start with the first action NOW.")
        } else {
            appendLine("[AGENT REMINDER] Response contained no tool call.")
            appendLine("If the task is fully done, call:")
            appendLine("TOOL>>")
            appendLine("""{"name":"complete","summary":"<precise summary of all changes made and verified>"}""")
            appendLine("<<TOOL")
            append("Otherwise, continue with the next required tool.")
        }
    }

    // ── Planning language detector ────────────────────────────────────────────
    private fun containsPlanningLanguage(text: String): Boolean {
        val planning = listOf(
            "i will ", "i'll ", "let me ", "going to ", "i'm going",
            "step 1", "first,", "firstly,", "next step", "i need to",
            "i should ", "now i", "then i", "my plan", "my approach"
        )
        return planning.any { text.contains(it, ignoreCase = true) }
    }

    // ── Trivial shell task fast-path ──────────────────────────────────────────
    private suspend fun handleSimpleShellTask(
        task: String,
        onStep: suspend (AgentStep) -> Unit
    ): Unit? {
        val cmd = task.trim()
        if (!cmd.matches(Regex("""^(ls|pwd|whoami|date|echo\s+\S+)(\s+[-./\w]+)?$""")))
            return null

        val tool = when {
            cmd == "pwd" ->
                JSONObject().put("name", "bash").put("cmd", "pwd").put("cwd", workingDir)
            cmd.startsWith("ls") -> {
                val arg = cmd.removePrefix("ls").trim()
                JSONObject().put("name", "directory_explorer")
                           .put("path", if (arg.isBlank()) workingDir else arg)
            }
            else ->
                JSONObject().put("name", "bash").put("cmd", cmd).put("cwd", workingDir)
        }

        val name = tool.optString("name", "?")
        onStep(AgentStep("tool_call", "🔧 $name"))
        val result = try {
            toolExecutor.executeTool(tool)
        } catch (ex: Exception) {
            Log.e(TAG_AGENT, "Simple task: $name", ex)
            AgentStep("tool_result",
                "❌ '$name' error: ${ex.message ?: ex::class.java.simpleName}",
                isError = true)
        }
        onStep(result)
        return Unit
    }
}
