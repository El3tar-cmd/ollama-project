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
import kotlin.coroutines.resume

class AgentEngine(private val context: android.content.Context) {

    var workingDir: String = context.filesDir.absolutePath

    private val toolExecutor by lazy { ToolExecutor(context) { workingDir } }
    private val memoryTool   by lazy { MemoryTool(context) { workingDir } }
    private val stateManager = StateManager()
    private val thinkRegex   = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

    // ── Planning phase ────────────────────────────────────────────────────────
    private suspend fun generatePlan(
        task: String, model: String, baseUrl: String,
        cloudApiKey: String, backend: String
    ): String {
        val api  = OllamaApi()
        var plan = ""
        val planMessages = listOf(
            ChatMessage("system",
                "You are a task planner. Create a concise numbered plan (max 7 steps) for the task. " +
                "Output ONLY the numbered list, one step per line. Be specific. Use same language as user."),
            ChatMessage("user", task)
        )
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val onTok:  (String) -> Unit         = { plan += it }
                val onDone: (Boolean, String) -> Unit = { _, _ -> if (!cont.isCompleted) cont.resume(Unit) }
                when {
                    cloudApiKey.isNotBlank() && model.endsWith(":cloud") ->
                        api.cloudChatStream(cloudApiKey, model, planMessages,
                            onTokenGenerated = onTok, onComplete = onDone)
                    backend == "llamacpp" ->
                        LlamaCppApi().chatStream(baseUrl, planMessages,
                            onToken = onTok, onComplete = onDone)
                    else ->
                        api.chatStream(baseUrl, model, planMessages,
                            onTokenGenerated = onTok, onComplete = onDone)
                }
            }
        } catch (_: Exception) {}
        return plan.trim()
    }

    // ── Main agent loop ───────────────────────────────────────────────────────
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

        // ── Task Classification ───────────────────────────────────────────────
        val workflowType = TaskClassifier.classify(userTask)
        stateManager.setWorkflow(workflowType)
        onStep(AgentStep("info",
            "${TaskClassifier.workflowLabel(workflowType)} workflow · ${workflowType.name}"))

        // ── Planning phase for MEDIUM and LARGE tasks ─────────────────────────
        if (workflowType != WorkflowType.SIMPLE) {
            onStep(AgentStep("info", "🗂️ Generating execution plan…"))
            val plan = generatePlan(userTask, model, baseUrl, cloudApiKey, backend)
            if (plan.isNotBlank()) {
                onStep(AgentStep("plan", plan))
                stateManager.setPlan(plan.lines().filter { it.isNotBlank() })
            }
        }

        // ── Load memory into system prompt ────────────────────────────────────
        val memoryContent = try { memoryTool.loadMemoryForPrompt() } catch (_: Exception) { "" }

        val messages = mutableListOf(
            ChatMessage("system", buildSystemPrompt(workingDir, memoryContent)),
            ChatMessage("user",   userTask)
        )

        val api   = OllamaApi()
        var steps = 0
        var errs  = 0

        while (steps < maxSteps) {
            steps++
            var response  = ""
            var streamOk  = true
            var streamErr = ""

            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val onTok:  (String) -> Unit         = { response += it }
                    val onDone: (Boolean, String) -> Unit = { s, m ->
                        if (!s) { streamOk = false; streamErr = m }
                        if (!cont.isCompleted) cont.resume(Unit)
                    }
                    val call = when {
                        cloudApiKey.isNotBlank() && model.endsWith(":cloud") ->
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
            catch (ex: Exception) {
                onStep(AgentStep("error", "❌ LLM error: ${ex.message}", true)); break
            }

            if (!streamOk) {
                errs++
                onStep(AgentStep("error", "❌ $streamErr", true))
                if (errs >= 3) { onStep(AgentStep("info", "⚠️ Too many errors. Stopping.")); break }
                delay(1000); continue
            }
            if (response.isBlank()) {
                errs++
                onStep(AgentStep("error", "❌ Empty response from model.", true))
                if (errs >= 3) break
                delay(800); continue
            }
            errs = 0

            // Extract <think> blocks
            thinkRegex.findAll(response).forEach { m ->
                val t = m.groupValues[1].trim()
                if (t.isNotBlank()) { onStep(AgentStep("think", t)); stateManager.markThought() }
            }

            val toolCalls = parseToolCalls(response)

            // Build display text — strip internal markers
            val display = response
                .replace(thinkRegex, "")
                .replace(Regex("""WRITE_FILE>>[^\n]+\n[\s\S]*?<<WRITE_FILE"""), "")
                .replace(Regex("""TOOL>>\s*\n[\s\S]*?\n?<<TOOL"""), "")
                .replace(Regex("""```tool\s*\n[\s\S]*?\n?```"""), "")
                .replace(Regex("""```json\s*\n[\s\S]*?\n?```"""), "")
                .trim()
            if (display.isNotBlank()) onStep(AgentStep("assistant", display))

            // No tool calls — nudge or end
            if (toolCalls.isEmpty()) {
                messages.add(ChatMessage("assistant", response))
                val nudgeCount = messages.count {
                    it.role == "user" && it.content.startsWith("[NUDGE]")
                }
                if (nudgeCount < 2) {
                    messages.add(ChatMessage("user",
                        "[NUDGE] You MUST output a tool call to continue. Use one of these formats:\n\n" +
                        "TOOL>>\n{\"name\":\"sequence_thinking\",\"content\":\"my reasoning\"}\n<<TOOL\n\n" +
                        "TOOL>>\n{\"name\":\"complete\",\"summary\":\"done\"}\n<<TOOL\n\n" +
                        "WRITE_FILE>>$workingDir/example.txt\nfile content\n<<WRITE_FILE"
                    ))
                    continue
                }
                onStep(AgentStep("info", "ℹ️ Task complete."))
                break
            }

            // ── Execute tool calls ────────────────────────────────────────────
            val resultBuf = StringBuilder()
            var done      = false

            for (call in toolCalls) {
                val toolName = call.optString("name", "?")

                // Rules Engine check
                val violation = RulesEngine.validate(call, stateManager.state)
                if (violation != null) {
                    onStep(AgentStep("info", violation.advice))
                    // Advisory only for most rules — don't block, just warn
                }

                // Track state
                when {
                    toolName.contains("think") || toolName == "sequence_thinking"
                        -> stateManager.markThought()
                    toolName in setOf("file_reader", "read_file", "line_reader", "read_lines",
                                      "head_file", "tail_file")
                        -> stateManager.recordFileRead(call.optString("path", ""))
                    toolName in setOf("file_writer", "write_file", "multi_file_writer",
                                      "line_editor", "edit_file", "multi_line_editor",
                                      "append_file", "replace_all")
                        -> stateManager.recordFileWrite(call.optString("path",
                            call.optJSONArray("files")?.optJSONObject(0)?.optString("path") ?: ""))
                }

                onStep(AgentStep("tool_call", "🔧 $toolName"))
                val result = toolExecutor.executeTool(call)
                stateManager.recordStep(toolName, result.content, result.isError)
                onStep(result)

                resultBuf.appendLine(result.content).appendLine("---")
                if (result.type == "complete") { done = true; break }
            }

            messages.add(ChatMessage("assistant", response))
            if (done) break

            // Suggest next tool from Rules Engine
            val suggestion = RulesEngine.suggestNextTool(stateManager.state)
            val hint = if (suggestion != null) " (consider: $suggestion next)" else ""

            messages.add(ChatMessage("user",
                "Tool results:\n$resultBuf\n" +
                "State: ${stateManager.summary()}\n" +
                "Proceed with next step.$hint Output TOOL>> or WRITE_FILE>> block."))

            // Smart context trimming — keep system + user task + recent history
            if (messages.size > 28) {
                val head = messages.take(2)
                val tail = messages.takeLast(10)
                messages.clear(); messages.addAll(head)
                messages.add(ChatMessage("system",
                    "[Context trimmed — ${stateManager.summary()}]"))
                messages.addAll(tail)
            }
        }

        if (steps >= maxSteps)
            onStep(AgentStep("info", "⚠️ Max steps ($maxSteps) reached."))
    }
}
