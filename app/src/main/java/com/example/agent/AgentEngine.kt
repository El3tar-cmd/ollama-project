package com.example.agent

import android.content.Context
import com.example.agent.tools.ToolExecutor
import com.example.data.api.LlamaCppApi
import com.example.data.api.OllamaApi
import com.example.data.model.AgentStep
import com.example.data.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AgentEngine(private val context: Context) {

    var workingDir: String = context.filesDir.absolutePath

    private val toolExecutor by lazy { ToolExecutor(context) { workingDir } }

    private val thinkRegex = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

    suspend fun runAgentLoop(
        userTask: String,
        model: String,
        baseUrl: String,
        maxSteps: Int = 25,
        cloudApiKey: String = "",
        backend: String = "ollama",
        onAskUser: (suspend (String) -> String)? = null,
        onStep: suspend (AgentStep) -> Unit
    ) {
        toolExecutor.askUserFn = onAskUser

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
                if (errs >= 3) { onStep(AgentStep("info", "⚠️ Too many errors — is the server running?")); break }
                delay(1000); continue
            }
            if (response.isBlank()) {
                errs++
                onStep(AgentStep("error", "❌ Empty response from model.", true))
                if (errs >= 3) break
                delay(800); continue
            }
            errs = 0

            // ── Extract <think> blocks → show as collapsible think steps ──
            thinkRegex.findAll(response).forEach { m ->
                val thinkContent = m.groupValues[1].trim()
                if (thinkContent.isNotBlank()) onStep(AgentStep("think", thinkContent))
            }

            val toolCalls = parseToolCalls(response)

            // ── Strip think blocks + tool markers from display text ──
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
                val nudgeCount = messages.count { it.role == "user" && it.content.startsWith("[NUDGE]") }
                if (nudgeCount < 2) {
                    messages.add(ChatMessage("user",
                        "[NUDGE] You MUST output a tool call to continue. Examples:\n" +
                        "TOOL>>\n{\"name\":\"list_dir\",\"path\":\"$workingDir\"}\n<<TOOL\n\n" +
                        "Or to finish:\nTOOL>>\n{\"name\":\"complete\",\"summary\":\"done\"}\n<<TOOL"
                    ))
                    continue
                }
                // Max nudges reached — finish gracefully
                onStep(AgentStep("info", "ℹ️ Task complete (model gave no tool call after nudges)."))
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

            if (messages.size > 30) {
                val head = messages.take(2)
                val tail = messages.takeLast(12)
                messages.clear()
                messages.addAll(head)
                messages.add(ChatMessage("system", "[Earlier steps trimmed — context limit management]"))
                messages.addAll(tail)
            }
        }

        if (steps >= maxSteps)
            onStep(AgentStep("info", "⚠️ Max steps ($maxSteps) reached. Task may be incomplete."))
    }
}
