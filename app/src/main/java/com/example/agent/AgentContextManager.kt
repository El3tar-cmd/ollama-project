package com.example.agent

import com.example.data.model.ChatMessage

/**
 * Advanced context manager with:
 * - Priority-weighted message preservation during compression
 * - Semantic file cache with LRU eviction
 * - Token budget enforcement with graceful degradation
 * - Rich per-iteration feedback injection
 */
class AgentContextManager {

    // ── File content cache (LRU via LinkedHashMap) ────────────────────────────
    private val fileCache = object : LinkedHashMap<String, CachedFile>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CachedFile>): Boolean {
            return size > MAX_CACHE_ENTRIES || totalCacheChars() > MAX_CACHE_CHARS
        }
    }

    private data class CachedFile(
        val content: String,
        val cachedAt: Long = System.currentTimeMillis(),
        var accessCount: Int = 0
    )

    private val MAX_CACHE_ENTRIES = 25
    private val MAX_CACHE_CHARS   = 150_000

    fun cacheFile(path: String, content: String) {
        if (path.isBlank() || content.isBlank()) return
        fileCache[path] = CachedFile(content)
    }

    fun getCachedFile(path: String): String? {
        val entry = fileCache[path] ?: return null
        entry.accessCount++
        return entry.content
    }

    fun hasFile(path: String) = fileCache.containsKey(path)

    fun reset() { fileCache.clear() }

    private fun totalCacheChars() = fileCache.values.sumOf { it.content.length }

    fun cachedFilesSummary(): String {
        if (fileCache.isEmpty()) return ""
        return fileCache.entries.take(8).joinToString(", ") {
            "${it.key.substringAfterLast('/')}(${it.value.content.length}c,×${it.value.accessCount})"
        } + if (fileCache.size > 8) " +${fileCache.size - 8} more" else ""
    }

    // ── Token budget management ───────────────────────────────────────────────
    // Heuristic: 1 token ≈ 3.5 chars for mixed code/prose
    private val SOFT_LIMIT_CHARS = 90_000    // ~26k tokens — trigger proactive compression
    private val HARD_LIMIT_CHARS = 130_000   // ~37k tokens — force hard compression
    private val MAX_MESSAGES     = 36

    fun estimateChars(messages: List<ChatMessage>): Int =
        messages.sumOf { it.role.length + it.content.length + 10 }

    fun isNearLimit(messages: List<ChatMessage>): Boolean =
        estimateChars(messages) > SOFT_LIMIT_CHARS || messages.size > MAX_MESSAGES - 6

    fun isOverLimit(messages: List<ChatMessage>): Boolean =
        estimateChars(messages) > HARD_LIMIT_CHARS || messages.size > MAX_MESSAGES

    // ── Smart compression: preserve critical context, summarize middle ─────────
    /**
     * Priority-preserving compression:
     * 1. Always keep: system prompt (index 0) + original task (index 1)
     * 2. Always keep: last N messages (recent context)
     * 3. Summarize: middle messages with key facts extracted
     * 4. Inject: compression note with state + file cache summary
     */
    fun compress(
        messages: List<ChatMessage>,
        stateSummary: String,
        keepTailSize: Int = 14
    ): List<ChatMessage> {
        if (messages.size <= 6) return messages

        val head     = messages.take(2)      // system + original task
        val tail     = messages.takeLast(keepTailSize)
        val middle   = messages.drop(2).dropLast(keepTailSize)

        // Extract key facts from middle messages
        val keyFacts = extractKeyFacts(middle)

        val compressionNote = ChatMessage("system", buildString {
            appendLine("━━━━━━ [CONTEXT COMPRESSED: ${middle.size} messages → summary] ━━━━━━")
            appendLine()
            appendLine("PROGRESS SO FAR:")
            appendLine(stateSummary)
            if (keyFacts.isNotEmpty()) {
                appendLine()
                appendLine("KEY FACTS FROM COMPRESSED HISTORY:")
                keyFacts.forEach { appendLine("  • $it") }
            }
            if (fileCache.isNotEmpty()) {
                appendLine()
                appendLine("FILES IN CACHE (re-read if needed for current content):")
                appendLine("  ${cachedFilesSummary()}")
            }
            appendLine()
            appendLine("Continue from where you left off. Use tools to verify before acting.")
            append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        })

        return head + compressionNote + tail
    }

    // Extract brief key facts from a list of messages to preserve during compression
    private fun extractKeyFacts(messages: List<ChatMessage>): List<String> {
        val facts = mutableListOf<String>()
        for (msg in messages) {
            val c = msg.content
            // Tool results with important outcomes
            when {
                c.contains("✅ Wrote") -> {
                    Regex("""✅ Wrote .+ → (.+?) \(""").find(c)?.groupValues?.get(1)
                        ?.let { facts.add("Wrote file: $it") }
                }
                c.contains("✅ Replaced") || c.contains("✅ multi_line_editor") -> {
                    facts.add("Edited: ${c.lines().firstOrNull()?.take(80) ?: ""}")
                }
                c.contains("✅ git commit") -> {
                    facts.add("Committed: ${c.substringAfter("git commit:").take(60).trim()}")
                }
                c.contains("⚠️ exit=") || c.contains("❌") -> {
                    facts.add("Error: ${c.take(100)}")
                }
                c.contains("📋 Execution Plan") || c.contains("Tasks") -> {
                    facts.add("Plan: ${c.lines().drop(2).take(3).joinToString("; ")}")
                }
            }
            if (facts.size >= 12) break
        }
        return facts.distinct().take(12)
    }

    // ── Per-iteration feedback message builder ────────────────────────────────
    /**
     * Builds the user feedback message injected after each tool execution.
     * Rich enough to keep the agent oriented, compact enough to save tokens.
     */
    fun buildFeedbackMessage(
        toolResults: String,
        stateSummary: String,
        nextToolHint: String?,
        step: Int,
        maxSteps: Int,
        phase: AgentPhase = AgentPhase.EXECUTE,
        pendingPlanSteps: List<Pair<Int, String>> = emptyList()
    ): String = buildString {
        appendLine("── Tool Results ──────────────────────")
        append(toolResults.take(3000))
        if (toolResults.length > 3000) appendLine("\n... (truncated)")
        appendLine()
        appendLine("── Agent Status ──────────────────────")
        appendLine(stateSummary)
        appendLine("Step: $step/$maxSteps | Phase: $phase")
        if (fileCache.isNotEmpty()) appendLine("Cache: ${cachedFilesSummary()}")
        if (pendingPlanSteps.isNotEmpty()) {
            appendLine()
            appendLine("── Pending Plan Steps ────────────────")
            pendingPlanSteps.take(5).forEach { (i, desc) ->
                appendLine("  [ ] ${i + 1}. $desc")
            }
        }
        appendLine()
        appendLine("── Next Action ───────────────────────")
        if (nextToolHint != null) appendLine("Suggested: $nextToolHint")
        append("Use TOOL>> or WRITE_FILE>> to continue. Think before acting if uncertain.")
    }
}
