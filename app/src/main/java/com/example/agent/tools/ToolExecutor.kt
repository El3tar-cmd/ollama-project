package com.example.agent.tools

import android.content.Context
import com.example.data.model.AgentStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Central tool dispatcher for the APEX Agent.
 * Routes parsed JSON tool calls to their implementations.
 * Supports fuzzy name mapping for common model hallucinations.
 */
class ToolExecutor(
    private val context: Context,
    private val getWorkingDir: () -> String
) {
    var askUserFn: (suspend (String) -> String)? = null

    private val fileTools      = FileTools(getWorkingDir)
    private val multiFileTools = MultiFileTools(getWorkingDir)
    private val bashTool       = BashTool(context, getWorkingDir)
    private val webTool        = WebTool()
    private val searchTools    = SearchTools(getWorkingDir)
    private val memoryTool     = MemoryTool(context, getWorkingDir)
    private val gitTool        = GitTool(context, getWorkingDir)
    private val linterTool     = LinterTool()
    private val thinkingTools  = ThinkingTools()

    suspend fun executeTool(tool: JSONObject): AgentStep = withContext(Dispatchers.IO) {
        when (val name = tool.optString("name", "").lowercase().trim()) {

            // ── Thinking & Metacognition ──────────────────────────────────────
            "think", "sequence_thinking" -> {
                val steps   = tool.optJSONArray("steps")
                val content = tool.optString("content", tool.optString("thought", ""))
                thinkingTools.toolSequenceThinking(steps, content)
            }

            "deep_think", "deep_analysis", "analyze" -> {
                thinkingTools.toolDeepThink(
                    problem      = tool.optString("problem", tool.optString("content", "")),
                    rootCause    = tool.optString("root_cause", ""),
                    solution     = tool.optString("solution", ""),
                    risks        = tool.optString("risks", ""),
                    alternatives = tool.optString("alternatives", "")
                )
            }

            "planning", "plan" -> {
                val task  = tool.optString("task", tool.optString("content", ""))
                val steps = tool.optJSONArray("steps")
                if (steps != null) thinkingTools.toolPlanning(task, steps)
                else AgentStep("plan", "📋 Plan: ${tool.optString("content", "(no steps provided)")}")
            }

            "decompose_task", "task_decomposition", "decompose" -> {
                thinkingTools.toolDecomposeTask(
                    task        = tool.optString("task", ""),
                    subtasks    = tool.optJSONArray("subtasks"),
                    constraints = tool.optString("constraints", "")
                )
            }

            "critique", "self_critique", "review_plan" -> {
                thinkingTools.toolCritique(
                    proposal     = tool.optString("proposal", tool.optString("content", "")),
                    concerns     = tool.optString("concerns", ""),
                    improvements = tool.optString("improvements", ""),
                    verdict      = tool.optString("verdict", "review")
                )
            }

            "verify_plan", "plan_check" -> {
                thinkingTools.toolVerifyPlan(
                    planSummary       = tool.optString("plan_summary", tool.optString("content", "")),
                    completenessCheck = tool.optString("completeness_check", ""),
                    feasibilityCheck  = tool.optString("feasibility_check", ""),
                    missingSteps      = tool.optString("missing_steps", "")
                )
            }

            "reflect", "reflection", "retrospective" -> {
                thinkingTools.toolReflect(
                    whatWorked  = tool.optString("what_worked", ""),
                    whatFailed  = tool.optString("what_failed", ""),
                    lessons     = tool.optString("lessons", ""),
                    nextTime    = tool.optString("next_time", "")
                )
            }

            "context_manager" -> {
                thinkingTools.toolContextManager(
                    action = tool.optString("action", "summarize"),
                    focus  = tool.optString("focus", "")
                )
            }

            // ── Directory & File System Exploration ───────────────────────────
            "directory_explorer", "list_directory_files", "list_dir", "list", "ls" ->
                fileTools.toolListDir(tool.optString("path", getWorkingDir()))

            "tree" ->
                fileTools.toolTree(
                    tool.optString("path", getWorkingDir()),
                    tool.optInt("depth", 3)
                )

            "file_info" ->
                fileTools.toolFileInfo(tool.optString("path", ""))

            "file_exists" ->
                fileTools.toolFileExists(tool.optString("path", ""))

            "file_outline", "outline" ->
                fileTools.toolFileOutline(tool.optString("path", ""), tool.optInt("max_lines", 100))

            "file_diff", "diff_files" ->
                fileTools.toolFileDiff(
                    tool.optString("a", tool.optString("path_a", tool.optString("src", ""))),
                    tool.optString("b", tool.optString("path_b", tool.optString("dst", "")))
                )

            // ── File Reading ──────────────────────────────────────────────────
            "file_reader", "read_file", "read" ->
                fileTools.toolReadFile(tool.optString("path", ""))

            "line_reader", "read_file_lines", "read_lines" -> {
                val path     = tool.optString("path", "")
                val startStr = tool.optString("start", "1")
                val endStr   = tool.optString("end",   "50")
                val intervalMatch = Regex("""^[\{\(]?(\d+)[-,:]?\s*(\d+)[\)\}]?$""").find(startStr)
                val start: Int
                val end: Int
                if (intervalMatch != null) {
                    start = intervalMatch.groupValues[1].toIntOrNull() ?: 1
                    end   = intervalMatch.groupValues[2].toIntOrNull() ?: 50
                } else {
                    start = startStr.toIntOrNull() ?: 1
                    end   = endStr.toIntOrNull()   ?: 50
                }
                fileTools.toolReadLines(path, start, end)
            }

            "head_file", "head" ->
                fileTools.toolHeadFile(tool.optString("path", ""), tool.optInt("lines", 30))

            "tail_file", "tail" ->
                fileTools.toolTailFile(tool.optString("path", ""), tool.optInt("lines", 20))

            // ── File Writing ──────────────────────────────────────────────────
            "file_writer", "write_file", "create_file" ->
                fileTools.toolWriteFile(tool.optString("path", ""), tool.optString("content", ""))

            "append_file" ->
                fileTools.toolAppendFile(tool.optString("path", ""), tool.optString("content", ""))

            // ── File Editing ──────────────────────────────────────────────────
            "line_editor", "replace_in_file", "edit_file" ->
                fileTools.toolEditFile(
                    tool.optString("path", ""),
                    tool.optString("old",  ""),
                    tool.optString("new",  "")
                )

            "replace_all" ->
                fileTools.toolReplaceAll(
                    tool.optString("path", ""),
                    tool.optString("old",  ""),
                    tool.optString("new",  "")
                )

            "replace_lines" ->
                fileTools.toolReplaceLines(
                    tool.optString("path", ""),
                    tool.optInt("start", 1),
                    tool.optInt("end",   1),
                    tool.optString("content", "")
                )

            "insert_before" ->
                fileTools.toolInsertBefore(
                    tool.optString("path",    ""),
                    tool.optString("marker",  ""),
                    tool.optString("content", "")
                )

            "insert_after" ->
                fileTools.toolInsertAfter(
                    tool.optString("path",    ""),
                    tool.optString("marker",  ""),
                    tool.optString("content", "")
                )

            // ── Multi-file operations ─────────────────────────────────────────
            "multi_file_writer" -> {
                val files = tool.optJSONArray("files")
                    ?: return@withContext AgentStep("tool_result",
                        "❌ multi_file_writer: 'files' array required", isError = true)
                multiFileTools.toolMultiFileWriter(files)
            }

            "multi_line_editor" -> {
                val edits = tool.optJSONArray("edits")
                    ?: return@withContext AgentStep("tool_result",
                        "❌ multi_line_editor: 'edits' array required", isError = true)
                multiFileTools.toolMultiLineEditor(tool.optString("path", ""), edits)
            }

            // ── File Management ───────────────────────────────────────────────
            "delete_file", "delete_path", "remove_file" ->
                fileTools.toolDeleteFile(tool.optString("path", ""))

            "copy_file", "copy_path" ->
                fileTools.toolCopyFile(
                    tool.optString("src", tool.optString("source", "")),
                    tool.optString("dst", tool.optString("dest",   tool.optString("destination", "")))
                )

            "move_file", "rename_path", "move_path", "rename_file" ->
                fileTools.toolMoveFile(
                    tool.optString("src", tool.optString("source", tool.optString("old", ""))),
                    tool.optString("dst", tool.optString("dest",   tool.optString("new", "")))
                )

            "create_dir", "create_directory", "mkdir" ->
                fileTools.toolCreateDir(tool.optString("path", ""))

            "find_files" ->
                fileTools.toolFindFiles(
                    tool.optString("dir", tool.optString("path", getWorkingDir())),
                    tool.optString("pattern", "*"),
                    tool.optInt("max", 50)
                )

            // ── Shell Execution ───────────────────────────────────────────────
            "terminal_executor", "run_command", "bash", "shell" ->
                bashTool.toolBash(
                    tool.optString("cmd", tool.optString("command", "")),
                    tool.optString("cwd", getWorkingDir())
                )

            "git_tool", "git" ->
                bashTool.toolBash("git ${tool.optString("args", "status")}", getWorkingDir())

            "run_python", "python_exec", "python" ->
                bashTool.toolRunPython(
                    tool.optString("code", tool.optString("cmd", tool.optString("script", ""))),
                    tool.optString("cwd", getWorkingDir())
                )

            "run_node", "node_exec", "node" ->
                bashTool.toolRunNode(
                    tool.optString("code", tool.optString("cmd", tool.optString("script", ""))),
                    tool.optString("cwd", getWorkingDir())
                )

            "calculate", "calc", "math" ->
                bashTool.toolCalculate(tool.optString("expr", tool.optString("expression", "")))

            // ── Web ───────────────────────────────────────────────────────────
            "fetch_url", "web_fetch", "http_request" ->
                webTool.toolFetchUrl(
                    tool.optString("url",    ""),
                    tool.optString("method", "GET"),
                    tool.optString("body",   "")
                )

            "web_search", "search_web", "google" ->
                webTool.toolWebSearch(tool.optString("query", ""))

            // ── Search ────────────────────────────────────────────────────────
            "project_search", "search_content", "search_files", "search" ->
                searchTools.toolSearchFiles(
                    tool.optString("dir", tool.optString("path", getWorkingDir())),
                    tool.optString("query", "")
                )

            "regex_search", "grep", "grep_search" ->
                searchTools.toolGrep(
                    tool.optString("dir", getWorkingDir()),
                    tool.optString("pattern", ""),
                    tool.optString("glob", tool.optString("file_pattern", ""))
                )

            "semantic_search" ->
                searchTools.toolSemanticSearch(
                    tool.optString("dir", getWorkingDir()),
                    tool.optString("query", "")
                )

            "todo_scan", "scan_todos", "find_todos" ->
                searchTools.toolTodoScan(
                    tool.optString("dir", getWorkingDir()),
                    tool.optInt("max", 50)
                )

            "project_overview" ->
                searchTools.toolProjectOverview(
                    tool.optString("dir", tool.optString("path", getWorkingDir()))
                )

            // ── Linter ────────────────────────────────────────────────────────
            "lint", "linter", "check_syntax" ->
                linterTool.toolLint(tool.optString("path", ""))

            // ── Git ───────────────────────────────────────────────────────────
            "git_status" ->
                gitTool.toolGitStatus()

            "git_diff" ->
                gitTool.toolGitDiff(tool.optString("target", "HEAD"))

            "git_log" ->
                gitTool.toolGitLog(tool.optInt("count", 10))

            "git_add" ->
                gitTool.toolGitAdd(tool.optString("paths", "."))

            "git_commit" ->
                gitTool.toolGitCommit(
                    tool.optString("message", ""),
                    tool.optBoolean("add_all", true)
                )

            "git_push" ->
                gitTool.toolGitPush(
                    tool.optString("remote", "origin"),
                    tool.optString("branch", "")
                )

            "git_branch" ->
                gitTool.toolGitBranch(tool.optString("name", ""))

            // ── Memory — Three-Tier System ────────────────────────────────────

            // Tier 1: Long-term / Semantic
            "memory_save", "memory_save_long" ->
                memoryTool.toolMemorySaveLong(
                    key   = tool.optString("key",   tool.optString("name", "note")).trim()
                                .replace(Regex("[^a-zA-Z0-9_\\- ]"), ""),
                    value = tool.optString("value", tool.optString("content", ""))
                )

            "memory_recall", "memory_recall_long" ->
                memoryTool.toolMemoryRecallLong(tool.optString("key", ""))

            "memory_clear", "memory_clear_long" ->
                memoryTool.toolMemoryClearLong(tool.optString("key", ""))

            // Tier 2: Session / Episodic
            "memory_save_short" ->
                memoryTool.toolMemorySaveShort(
                    key   = tool.optString("key",   "note").trim()
                                .replace(Regex("[^a-zA-Z0-9_\\- ]"), ""),
                    value = tool.optString("value", tool.optString("content", ""))
                )

            "memory_recall_short" ->
                memoryTool.toolMemoryRecallShort()

            "memory_clear_short" ->
                memoryTool.toolMemoryClearShort()

            // Tier 3: Procedural / How-To
            "memory_save_procedure", "save_procedure", "learn_procedure" ->
                memoryTool.toolMemorySaveProcedure(
                    name  = tool.optString("name",  tool.optString("key",   "procedure")).trim(),
                    steps = tool.optString("steps", tool.optString("value", tool.optString("content", "")))
                )

            "memory_recall_procedure", "recall_procedure", "get_procedure" ->
                memoryTool.toolMemoryRecallProcedure(tool.optString("name", tool.optString("key", "")))

            // All tiers at once
            "memory_recall_all" ->
                memoryTool.toolMemoryRecallAll()

            // Cross-tier search
            "memory_search", "search_memory" ->
                memoryTool.toolMemorySearch(tool.optString("query", ""))

            // ── User Interaction ──────────────────────────────────────────────
            "ask_user", "ask_human", "clarify" -> {
                val question = tool.optString("question", "What should I do next?")
                val fn = askUserFn
                if (fn != null) AgentStep("tool_result", "💬 User says: ${fn(question)}")
                else AgentStep("tool_result", "ℹ️ No user input available — continuing autonomously.")
            }

            // ── Complete ──────────────────────────────────────────────────────
            "complete", "done", "finish" -> {
                val summary = tool.optString("summary",
                    tool.optString("result", tool.optString("content", "Task complete.")))
                memoryTool.recordTaskDone(summary)
                AgentStep("complete", "✅ $summary")
            }

            // ── Fuzzy fallback mapping ────────────────────────────────────────
            else -> {
                val mapped = FUZZY_TOOL_MAP[name] ?: FUZZY_TOOL_MAP.entries
                    .firstOrNull { (k, _) -> name.contains(k) || k.contains(name) }?.value

                if (mapped != null) {
                    executeTool(JSONObject(tool.toString()).put("name", mapped))
                } else {
                    AgentStep("tool_result",
                        "❌ Unknown tool: \"$name\"\n" +
                        "Available: file_reader, line_reader, file_writer, line_editor, " +
                        "multi_line_editor, terminal_executor, git_diff, lint, " +
                        "project_search, regex_search, deep_think, planning, complete",
                        isError = true)
                }
            }
        }
    }

    companion object {
        // Common LLM hallucination → correct tool name mappings
        private val FUZZY_TOOL_MAP = mapOf(
            // Reading
            "extract"       to "file_reader",
            "get_file"      to "file_reader",
            "read_css"      to "file_reader",
            "read_js"       to "file_reader",
            "read_html"     to "file_reader",
            "get_css"       to "file_reader",
            "get_js"        to "file_reader",
            "view_file"     to "file_reader",
            "show_file"     to "file_reader",
            "print_file"    to "file_reader",
            "cat"           to "file_reader",
            // Writing
            "save_file"     to "write_file",
            "overwrite"     to "write_file",
            "update_file"   to "line_editor",
            "patch_file"    to "line_editor",
            "modify_file"   to "line_editor",
            // File management
            "delete"        to "delete_file",
            "remove"        to "delete_file",
            "move"          to "move_file",
            "rename"        to "move_file",
            "copy"          to "copy_file",
            // Terminal
            "run"           to "terminal_executor",
            "execute"       to "terminal_executor",
            "command"       to "terminal_executor",
            "bash_cmd"      to "terminal_executor",
            "sh"            to "terminal_executor",
            "cmd"           to "terminal_executor",
            // Directory
            "ls"            to "directory_explorer",
            "dir"           to "directory_explorer",
            // Git
            "gitdiff"       to "git_diff",
            "git_show"      to "git_diff",
            // Search
            "grep"          to "regex_search",
            "find"          to "find_files",
            "locate"        to "project_search",
            // Thinking
            "think"         to "sequence_thinking",
            "reason"        to "deep_think",
            "analysis"      to "deep_think",
            "brainstorm"    to "deep_think",
            "check_plan"    to "verify_plan",
            // Memory
            "save_memory"   to "memory_save_long",
            "remember"      to "memory_save_long",
            "recall"        to "memory_recall_all",
            "forget"        to "memory_clear_long"
        )
    }
}
