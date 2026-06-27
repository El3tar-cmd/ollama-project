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

class AgentEngine(private val context: android.content.Context) {

    var workingDir: String = context.filesDir.absolutePath

    private val toolExecutor   by lazy { ToolExecutor(context) { workingDir } }
    private val memoryTool     by lazy { MemoryTool(context) { workingDir } }
    private val planManager    by lazy { ProjectPlanManager { workingDir } }
    private val stateManager   = StateManager()
    private val contextManager = AgentContextManager()

    private val thinkRegex     = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)
    private val thinkOpenRegex = Regex("<think>[\\s\\S]*$",           RegexOption.IGNORE_CASE)

    // ── LLM call helper ───────────────────────────────────────────────────────
    private suspend fun callLlm(
        messages: List<ChatMessage>,
        model: String, baseUrl: String,
        cloudApiKey: String, backend: String
    ): Pair<Boolean, String> {
        val api = OllamaApi()
        var response = ""
        var ok = true
        var errMsg = ""
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val onTok:  (String) -> Unit         = { response += it }
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
        catch (ex: Exception) { ok = false; errMsg = ex.message ?: "unknown" }
        return ok to (if (ok) response else errMsg)
    }

    // ── Planning phase ────────────────────────────────────────────────────────
    private suspend fun generatePlan(
        task: String, model: String, baseUrl: String,
        cloudApiKey: String, backend: String
    ): String {
        val planMessages = listOf(
            ChatMessage("system",
                "You are a task planner. Create a concise numbered plan (max 7 steps) for the given task. " +
                "Output ONLY the numbered list, one step per line. Be specific and actionable. " +
                "Use the same language as the user."),
            ChatMessage("user", task)
        )
        val (ok, result) = callLlm(planMessages, model, baseUrl, cloudApiKey, backend)
        return if (ok) result.trim() else ""
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
        contextManager.reset()

        val effectiveMaxSteps = maxSteps
        onStep(AgentStep("info", "Agent loop started · max steps $effectiveMaxSteps"))

        executeSimpleShellTask(userTask, onStep)?.let {
            onStep(AgentStep("info", "ℹ️ Task complete."))
            return
        }

        // ── Load memory ───────────────────────────────────────────────────────
        val memoryContent = try { memoryTool.loadMemoryForPrompt() } catch (_: Exception) { "" }
        val projectPlanContext = try { planManager.loadContext() } catch (_: Exception) { "" }

        // ── Build initial messages ────────────────────────────────────────────
        val messages = mutableListOf(
            ChatMessage("system", buildSystemPrompt(workingDir, memoryContent, projectPlanContext)),
            ChatMessage("user",   userTask)
        )

        var steps                   = 0
        var errs                    = 0
        var consecutiveThinkingOnly = 0
        var productiveToolCount     = 0   // writes, edits, terminal, git — not reads/thinks
        val toolSignatureCounts     = mutableMapOf<String, Int>()

        // ── Main loop ─────────────────────────────────────────────────────────
        while (steps < effectiveMaxSteps) {
            steps++

            // Token budget check — compress before calling LLM if near limit
            if (contextManager.isNearLimit(messages)) {
                val compressed = contextManager.compress(messages, stateManager.summary())
                messages.clear(); messages.addAll(compressed)
                onStep(AgentStep("info", "📦 Context compressed (token budget)."))
            }

            val (streamOk, rawResponse) = callLlm(messages, model, baseUrl, cloudApiKey, backend)

            if (!streamOk) {
                errs++
                onStep(AgentStep("error", "❌ $rawResponse", true))
                if (errs >= 3) { onStep(AgentStep("info", "⚠️ Too many LLM errors. Stopping.")); break }
                delay(1000); continue
            }
            if (rawResponse.isBlank()) {
                errs++
                onStep(AgentStep("error", "❌ Empty response from model.", true))
                if (errs >= 3) break
                delay(800); continue
            }
            errs = 0

            val response = rawResponse

            // ── Extract and display <think> blocks ────────────────────────────
            try {
                thinkRegex.findAll(response).forEach { m ->
                    val t = m.groupValues[1].trim()
                    if (t.isNotBlank()) { onStep(AgentStep("think", t)); stateManager.markThought() }
                }
            } catch (e: Exception) {
                Log.w(TAG_AGENT, "think regex failed: ${e.message}")
            }

            val toolCalls = try { parseToolCalls(response) }
            catch (e: Exception) {
                Log.w(TAG_AGENT, "parseToolCalls threw: ${e.message}")
                emptyList()
            }

            // ── Build display text (strip markers + think blocks) ─────────────
            val display = try {
                response
                    .replace(thinkRegex, "")
                    .replace(thinkOpenRegex, "")
                    .replace(Regex("""WRITE_FILE>>[^\n]+\n[\s\S]*?<<WRITE_FILE"""), "")
                    .replace(Regex("""TOOL>>\s*\n[\s\S]*?\n?<<TOOL"""), "")
                    .replace(Regex("""```tool\s*\n[\s\S]*?\n?```"""), "")
                    .replace(Regex("""```json\s*\n[\s\S]*?\n?```"""), "")
                    .trim()
            } catch (e: PatternSyntaxException) {
                Log.w(TAG_AGENT, "Display regex failed: ${e.message}")
                response.take(2000)
            }
            if (display.isNotBlank()) onStep(AgentStep("assistant", display))

            // ── No tool calls — NEVER silently complete, always redirect ────────
            if (toolCalls.isEmpty()) {
                messages.add(ChatMessage("assistant", response))
                // If the agent produced real work already and the response looks conclusive → done
                val looksConclusive = response.length < 300 &&
                    !response.contains("will ", ignoreCase = true) &&
                    !response.contains("I'll ", ignoreCase = true) &&
                    !response.contains("let me ", ignoreCase = true) &&
                    !response.contains("going to ", ignoreCase = true) &&
                    productiveToolCount > 0
                if (looksConclusive) {
                    onStep(AgentStep("info", "✅ Task complete."))
                    break
                }
                // Otherwise: the agent described work but used no tool — redirect
                val redirect = if (productiveToolCount == 0) {
                    "[AGENT ERROR] You described what you plan to do but used NO tools. " +
                    "You MUST immediately perform the first action using a tool call. " +
                    "Do NOT explain — execute. Use TOOL>>...<<TOOL format right now."
                } else {
                    "[AGENT REMINDER] You responded with text but no tool call. " +
                    "If the task is done, call: TOOL>>\n{\"name\":\"complete\",\"summary\":\"...\"}\n<<TOOL\n" +
                    "Otherwise, continue working with the next required tool."
                }
                onStep(AgentStep("info", "⚡ Redirecting agent to use tools..."))
                messages.add(ChatMessage("user", redirect))
                continue
            }

            // ── Cycle detection (consecutive sequence_thinking only) ───────────
            val onlyThinking = toolCalls.all { tc ->
                val n = tc.optString("name", "")
                n == "sequence_thinking" || n.contains("think") || n == "planning"
            }
            if (onlyThinking) {
                consecutiveThinkingOnly++
                val recentTools = stateManager.recentTools(8)
                if (AgentErrorRecovery.detectCycle(recentTools)) {
                    val stuck = recentTools.lastOrNull() ?: "unknown"
                    onStep(AgentStep("info", AgentErrorRecovery.cycleRecoveryMessage(stuck)))
                    messages.add(ChatMessage("user", AgentErrorRecovery.cycleRecoveryMessage(stuck)))
                    consecutiveThinkingOnly = 0
                }
                if (consecutiveThinkingOnly >= 4) {
                    // Too many thinking-only cycles — demand concrete action
                    val demand = "[AGENT STUCK] You have been thinking for $consecutiveThinkingOnly " +
                        "consecutive steps without taking real action. STOP thinking. " +
                        "Pick up the first task step and execute it NOW with a file write, edit, or command. " +
                        "Thinking alone accomplishes nothing."
                    onStep(AgentStep("info", "⚠️ Agent stuck in thinking loop — forcing action"))
                    messages.add(ChatMessage("user", demand))
                    consecutiveThinkingOnly = 0
                }
            } else {
                consecutiveThinkingOnly = 0
            }

            // ── Execute tool calls ────────────────────────────────────────────
            val resultBuf = StringBuilder()
            var done = false

            for (call in toolCalls) {
                val toolName = call.optString("name", "?")
                val signature = "$toolName|${call.toString()}"
                val repeatedCount = (toolSignatureCounts[signature] ?: 0) + 1
                toolSignatureCounts[signature] = repeatedCount
                if (repeatedCount >= 3 && toolName != "complete") {
                    val advice = "Repeated identical tool call blocked: $toolName. Inspect the last result and choose a different tool or arguments."
                    onStep(AgentStep("info", advice))
                    resultBuf.appendLine("BLOCKED: $advice")
                    messages.add(ChatMessage("user", "[RECOVERY] $advice"))
                    continue
                }

                // ── Gate: prevent premature `complete` ───────────────────────
                if (toolName == "complete") {
                    val hasWritten = stateManager.state.filesWritten.isNotEmpty()
                    val needsDiff  = hasWritten && "git_diff" !in stateManager.state.toolsUsed

                    when {
                        productiveToolCount == 0 && steps <= 4 -> {
                            val msg = "[BLOCKED] You called complete but have not done any real work yet. " +
                                "filesWritten=${stateManager.state.filesWritten.size}, " +
                                "productiveActions=$productiveToolCount. " +
                                "You must perform the actual task first: read the task, write/edit files, " +
                                "run commands. Then call complete with a verified summary."
                            onStep(AgentStep("info", "🚫 Premature complete blocked — forcing execution"))
                            resultBuf.appendLine(msg)
                            messages.add(ChatMessage("user", msg))
                            continue
                        }
                        needsDiff -> {
                            val msg = "[BLOCKED] Files were modified. Run git_diff to review changes, " +
                                "then call complete with a verified summary of what changed."
                            onStep(AgentStep("info", "⚠️ git_diff required before complete"))
                            resultBuf.appendLine(msg)
                            messages.add(ChatMessage("user", msg))
                            continue
                        }
                    }
                }

                // Rules Engine check
                val violation = RulesEngine.validate(call, stateManager.state)
                if (violation != null) {
                    onStep(AgentStep("info", violation.advice))
                    if (violation.blocking) {
                        resultBuf.appendLine("BLOCKED: ${violation.advice}")
                        stateManager.recordStep(toolName, "BLOCKED: ${violation.advice}", isError = true)
                        continue
                    }
                }

                // ── Track state + productive count ────────────────────────────
                val isThinkTool  = toolName.contains("think") || toolName == "sequence_thinking" || toolName == "planning"
                val isReadTool   = toolName in setOf("file_reader", "read_file", "line_reader",
                                                     "read_lines", "head_file", "tail_file",
                                                     "directory_explorer", "tree", "find_files",
                                                     "project_search", "regex_search", "semantic_search",
                                                     "git_status", "memory_recall_all", "context_manager")
                val isWriteTool  = toolName in setOf("file_writer", "write_file", "multi_file_writer",
                                                     "line_editor", "edit_file", "multi_line_editor",
                                                     "append_file", "replace_all", "create_file",
                                                     "delete_file", "move_file", "copy_file")
                val isRunTool    = toolName in setOf("terminal_executor", "run_command", "bash", "shell",
                                                     "run_python", "run_node", "calculate",
                                                     "git_add", "git_commit", "git_push", "git_branch",
                                                     "git_diff", "lint")

                when {
                    isThinkTool -> stateManager.markThought()
                    isReadTool  -> stateManager.recordFileRead(call.optString("path", ""))
                    isWriteTool -> {
                        stateManager.recordFileWrite(call.optString("path",
                            call.optJSONArray("files")?.optJSONObject(0)?.optString("path") ?: ""))
                        productiveToolCount++
                    }
                    isRunTool   -> productiveToolCount++
                }

                onStep(AgentStep("tool_call", "🔧 $toolName"))
                val result = try {
                    toolExecutor.executeTool(call)
                } catch (ex: Exception) {
                    Log.e(TAG_AGENT, "Tool execution crashed: $toolName", ex)
                    AgentStep(
                        "tool_result",
                        "❌ Tool '$toolName' failed internally: ${ex.message ?: ex::class.java.simpleName}",
                        isError = true
                    )
                }
                stateManager.recordStep(toolName, result.content, result.isError)

                // Cache file reads into context manager
                if (toolName in setOf("file_reader", "read_file", "head_file", "line_reader")) {
                    val path = call.optString("path", "")
                    if (path.isNotBlank() && !result.isError)
                        contextManager.cacheFile(path, result.content)
                }

                onStep(result)

                // Error recovery injection
                if (result.isError && toolName !in setOf("sequence_thinking", "think", "lint")) {
                    val recovery = AgentErrorRecovery.buildRecoveryMessage(toolName, result.content)
                    resultBuf.appendLine(recovery)
                } else {
                    resultBuf.appendLine(result.content).appendLine("---")
                }

                if (result.type == "complete") {
                    try { planManager.markFirstPendingDone("completed by agent") } catch (_: Exception) {}
                    done = true
                    break
                }
            }

            messages.add(ChatMessage("assistant", response))
            if (done) break

            // ── Build next-iteration feedback ─────────────────────────────────
            val suggestion = RulesEngine.suggestNextTool(stateManager.state)
            val feedback   = contextManager.buildFeedbackMessage(
                toolResults  = resultBuf.toString(),
                stateSummary = stateManager.summary(),
                nextToolHint = suggestion,
                step         = steps,
                maxSteps     = effectiveMaxSteps
            )
            messages.add(ChatMessage("user", feedback))

            // ── Smart context trimming (hard limit) ───────────────────────────
            if (contextManager.isOverLimit(messages) || messages.size > 32) {
                val compressed = contextManager.compress(messages, stateManager.fullSummary())
                messages.clear(); messages.addAll(compressed)
            }
        }

        if (steps >= effectiveMaxSteps)
            onStep(AgentStep("info", "⚠️ Max steps ($effectiveMaxSteps) reached. ${stateManager.summary()}"))
    }

    private suspend fun executeSimpleShellTask(
        task: String,
        onStep: suspend (AgentStep) -> Unit
    ): Unit? {
        val cmd = task.trim()
        if (!cmd.matches(Regex("""^(ls|pwd|whoami|date)(\s+[-./\w]+)?$"""))) return null
        val tool = when {
            cmd == "pwd" -> JSONObject().put("name", "bash").put("cmd", "pwd").put("cwd", workingDir)
            cmd.startsWith("ls") -> {
                val arg = cmd.removePrefix("ls").trim()
                JSONObject()
                    .put("name", "list_dir")
                    .put("path", if (arg.isBlank()) workingDir else arg)
            }
            else -> JSONObject().put("name", "bash").put("cmd", cmd).put("cwd", workingDir)
        }
        val name = tool.optString("name", "?")
        onStep(AgentStep("tool_call", "🔧 $name"))
        val result = try {
            toolExecutor.executeTool(tool)
        } catch (ex: Exception) {
            Log.e(TAG_AGENT, "Simple tool execution crashed: $name", ex)
            AgentStep(
                "tool_result",
                "❌ Tool '$name' failed internally: ${ex.message ?: ex::class.java.simpleName}",
                isError = true
            )
        }
        onStep(result)
        return Unit
    }
}
