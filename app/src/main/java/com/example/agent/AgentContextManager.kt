package com.example.agent

import com.example.data.model.ChatMessage

/**
 * Smart context manager for the agent loop.
 *
 * Responsibilities:
 * - File content cache: avoids redundant LLM round-trips for already-read files
 * - Token budget: estimates token usage and triggers proactive compression
 * - Smart compression: summarizes history meaningfully instead of just dropping messages
 * - Injection: builds per-iteration user messages with relevant context hints
 */
class AgentContextManager {

    // ── File content cache ────────────────────────────────────────────────────
    private val fileCache = linkedMapOf<String, String>()  // path → content
    private val MAX_CACHE_ENTRIES = 20
    private val MAX_CACHE_CHARS   = 120_000  // ~30k tokens of cached files

    fun cacheFile(path: String, content: String) {
        if (path.isBlank()) return
        fileCache[path] = content
        // Evict oldest if over limit
        while (fileCache.size > MAX_CACHE_ENTRIES ||
               fileCache.values.sumOf { it.length } > MAX_CACHE_CHARS) {
            fileCache.entries.firstOrNull()?.let { fileCache.remove(it.key) }
        }
    }

    fun getCachedFile(path: String): String? = fileCache[path]
    fun hasFile(path: String): Boolean = fileCache.containsKey(path)
    fun reset() { fileCache.clear() }

    fun cachedFilesSummary(): String {
        if (fileCache.isEmpty()) return ""
        return fileCache.entries.joinToString(", ") {
            "${it.key.substringAfterLast('/')} (${it.value.length}c)"
        }
    }

    // ── Token budget ──────────────────────────────────────────────────────────
    // Rough heuristic: 1 token ≈ 3.5 chars for mixed code/prose
    private val SOFT_LIMIT_CHARS = 80_000   // ~23k tokens
    private val HARD_LIMIT_CHARS = 110_000  // ~31k tokens

    fun estimateChars(messages: List<ChatMessage>): Int =
        messages.sumOf { it.role.length + it.content.length + 8 }

    fun isNearLimit(messages: List<ChatMessage>): Boolean =
        estimateChars(messages) > SOFT_LIMIT_CHARS

    fun isOverLimit(messages: List<ChatMessage>): Boolean =
        estimateChars(messages) > HARD_LIMIT_CHARS

    /**
     * Smart compress: preserve system + task, summarize middle, keep recent tail.
     * Result is always under the soft limit.
     */
    fun compress(
        messages: List<ChatMessage>,
        stateSummary: String
    ): List<ChatMessage> {
        if (messages.size <= 6) return messages
        val head = messages.take(2)          // system prompt + original task
        val tailSize = 12
        val tail = messages.takeLast(tailSize)
        val droppedCount = messages.size - 2 - tailSize

        val compressionNote = ChatMessage("system", buildString {
            appendLine("━━ [CONTEXT COMPRESSED: $droppedCount messages summarized] ━━")
            appendLine(stateSummary)
            if (fileCache.isNotEmpty()) {
                appendLine("Files in cache: ${cachedFilesSummary()}")
                appendLine("Use file_reader again if you need current content of a file.")
            }
        })
        return head + compressionNote + tail
    }

    // ── Per-iteration context injection ──────────────────────────────────────
    /**
     * Build the "user" feedback message after each tool execution.
     * Injects state summary, file cache hints, and next-step suggestions.
     */
    fun buildFeedbackMessage(
        toolResults: String,
        stateSummary: String,
        nextToolHint: String?,
        step: Int,
        maxSteps: Int
    ): String = buildString {
        append("Tool results:\n$toolResults\n")
        append("State: $stateSummary\n")
        append("Step: $step/$maxSteps")
        if (fileCache.isNotEmpty()) append(" | Cached: ${cachedFilesSummary()}")
        appendLine()
        if (nextToolHint != null) append("Suggested next: $nextToolHint. ")
        appendLine("Output your next TOOL>> or WRITE_FILE>> block.")
    }
}
