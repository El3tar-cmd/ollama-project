package com.example.agent

import android.util.Log
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

    private val toolExecutor by lazy { com.example.agent.tools.ToolExecutor(context) { workingDir } }
    private val thinkRegex   = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

    // ── Estimate task complexity ──────────────────────────────────────────────
    private fun isComplexTask(task: String): Boolean {
        val words = task.trim().split(Regex("\\s+")).size
        val complexKeywords = listOf(
            "project", "مشروع", "create", "build", "implement", "develop",
            "refactor", "migrate", "integrate", "design", "architecture",
            "multiple", "several", "full", "complete", "system", "app",
            "اعمل", "ابني", "طور", "انشئ", "نظام", "تطبيق"
        )
        return words > 20 || complexKeywords.any { task.contains(it, ignoreCase = true) }
    }

    // ── Generate task plan ────────────────────────────────────────────────────
    private suspend fun generatePlan(
        task: String, model: String, baseUrl: String,
        cloudApiKey: String, backend: String
    ): String {
        val api  = OllamaApi()
        var plan = ""
        val planMessages = listOf(
            ChatMessage("system",
                "You are a task planner. Create a concise numbered plan (5-8 steps max) " +
                "for the following task. Output ONLY the numbered list. Each step on its own line. " +
                "Be specific and actionable. Use the same language as the user."),
            ChatMessage("user", task)
        )
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val onTok: (String) -> Unit  = { plan += it }
                val onDone: (Boolean, String) -> Unit = { _, _ ->
                    if (!cont.isCompleted) cont.resume(Unit)
                }
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
        maxSteps: Int    = 25,
        cloudApiKey: String = "",
        backend: String  = "ollama",
        onAskUser: (suspend (String) -> String)? = null,
        onStep: suspend (AgentStep) -> Unit
    ) {
        toolExecutor.askUserFn = onAskUser

        // ── Planning phase for complex tasks ──────────────────────────────────
        if (isComplexTask(userTask)) {
            onStep(AgentStep("info", "🗂️ Generating task plan…"))
            val plan = generatePlan(userTask, model, baseUrl, cloudApiKey, backend)
            if (plan.isNotBlank()) {
                onStep(AgentStep("plan", plan))
            }
        }

        val messages = mutableListOf(
            ChatMessage("system", buildSystemPrompt(workingDir)),
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
                    val onTok: (String) -> Unit = { response += it }
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
                if (errs >= 3) { onStep(AgentStep("info", "⚠️ Too many errors.")); break }
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
                val thinkContent = m.groupValues[1].trim()
                if (thinkContent.isNotBlank()) onStep(AgentStep("think", thinkContent))
            }

            val toolCalls = parseToolCalls(response)

            // Strip think + tool markers from display
            val display = response
                .replace(thinkRegex, "")
                .replace(Regex("""WRITE_FILE>>[^\n]+\n[\s\S]*?<<WRITE_FILE"""), "")
                .replace(Regex("""TOOL>>\s*\n[\s\S]*?\n?<<TOOL"""), "")
                .replace(Regex("""```tool\s*\n[\s\S]*?\n?```"""), "")
                .replace(Regex("""```json\s*\n[\s\S]*?\n?```"""), "")
                .trim()
            if (display.isNotBlank()) onStep(AgentStep("assistant", display))

            if (toolCalls.isEmpty()) {
                messages.add(ChatMessage("assistant", response))
                val nudgeCount = messages.count {
                    it.role == "user" && it.content.startsWith("[NUDGE]")
                }
                if (nudgeCount < 2) {
                    messages.add(ChatMessage("user",
                        "[NUDGE] You MUST output a tool call to continue. Examples:\n" +
                        "TOOL>>\n{\"name\":\"list_dir\",\"path\":\"$workingDir\"}\n<<TOOL\n\n" +
                        "Or to finish:\nTOOL>>\n{\"name\":\"complete\",\"summary\":\"done\"}\n<<TOOL"
                    ))
                    continue
                }
                onStep(AgentStep("info", "ℹ️ Task complete."))
                break
            }

            val resultBuf = StringBuilder()
            var done = false
            for (call in toolCalls) {
                val toolName = call.optString("name", "?")
                onStep(AgentStep("tool_call", "🔧 $toolName"))
                val result = toolExecutor.executeTool(call)
                onStep(result)
                resultBuf.appendLine(result.content).appendLine("---")
                if (result.type == "complete") { done = true; break }
            }

            messages.add(ChatMessage("assistant", response))
            if (done) break

            messages.add(ChatMessage("user",
                "Tool results:\n$resultBuf\nProceed. Output the next TOOL>> or WRITE_FILE>> block."))

            // Trim context if too long
            if (messages.size > 30) {
                val head = messages.take(2)
                val tail = messages.takeLast(12)
                messages.clear(); messages.addAll(head)
                messages.add(ChatMessage("system", "[Earlier steps trimmed — context limit]"))
                messages.addAll(tail)
            }
        }

        if (steps >= maxSteps)
            onStep(AgentStep("info", "⚠️ Max steps ($maxSteps) reached."))
    }
}
