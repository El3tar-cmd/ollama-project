package com.example.agent.tools

import android.content.Context
import com.example.data.model.AgentStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
        when (val name = tool.optString("name", "")) {

            // ── Thinking & Planning ───────────────────────────────────────────
            "think", "sequence_thinking" -> {
                val steps   = tool.optJSONArray("steps")
                val content = tool.optString("content", tool.optString("thought", ""))
                thinkingTools.toolSequenceThinking(steps, content)
            }
            "planning", "plan" -> {
                val task  = tool.optString("task", "")
                val steps = tool.optJSONArray("steps")
                if (steps != null) thinkingTools.toolPlanning(task, steps)
                else AgentStep("plan", "📋 Plan: ${tool.optString("content", "(no steps provided)")}")
            }
            "context_manager" -> {
                thinkingTools.toolContextManager(
                    tool.optString("action", "summarize"),
                    tool.optString("focus", "")
                )
            }

            // ── Directory Explorer & File System ──────────────────────────────
            "directory_explorer", "list_directory_files", "list_dir", "list", "ls"
                -> fileTools.toolListDir(tool.optString("path", getWorkingDir()))
            "tree"
                -> fileTools.toolTree(
                    tool.optString("path", getWorkingDir()),
                    tool.optInt("depth", 3)
                )
            "file_info"
                -> fileTools.toolFileInfo(tool.optString("path", ""))
            "file_exists"
                -> fileTools.toolFileExists(tool.optString("path", ""))

            // ── File Reader ───────────────────────────────────────────────────
            "file_reader", "read_file", "read"
                -> fileTools.toolReadFile(tool.optString("path", ""))
            "line_reader", "read_file_lines", "read_lines" -> {
                // Handle both separate start/end and interval formats like "(5,10)" or "5-10"
                val path = tool.optString("path", "")
                val startStr = tool.optString("start", tool.optString("lines", "1"))
                val endStr = tool.optString("end", tool.optString("lines", "50"))
                
                // Parse interval format if present (e.g., "(5,10)" or "5-10")
                val intervalMatch = Regex("""^\(?(\d+)[-,:]?\s*(\d+)\)?$""").find(startStr)
                val start: Int
                val end: Int
                if (intervalMatch != null) {
                    start = intervalMatch.groupValues[1].toIntOrNull() ?: 1
                    end = intervalMatch.groupValues[2].toIntOrNull() ?: 50
                } else {
                    start = startStr.toIntOrNull() ?: 1
                    end = endStr.toIntOrNull() ?: 50
                }
                
                fileTools.toolReadLines(path, start, end)
            }
            "head_file"
                -> fileTools.toolHeadFile(tool.optString("path", ""), tool.optInt("lines", 20))
            "tail_file"
                -> fileTools.toolTailFile(tool.optString("path", ""), tool.optInt("lines", 20))
            "file_diff"
                -> fileTools.toolFileDiff(tool.optString("a", tool.optString("path_a", "")),
                                          tool.optString("b", tool.optString("path_b", "")))

            // ── File Writer ───────────────────────────────────────────────────
            "file_writer", "write_file", "create_file"
                -> fileTools.toolWriteFile(tool.optString("path", ""), tool.optString("content", ""))
            "append_file"
                -> fileTools.toolAppendFile(tool.optString("path", ""), tool.optString("content", ""))

            // ── Line Editor ───────────────────────────────────────────────────
            "line_editor", "replace_in_file", "edit_file"
                -> fileTools.toolEditFile(
                    tool.optString("path", ""),
                    tool.optString("old", ""),
                    tool.optString("new", "")
                )
            "replace_all"
                -> fileTools.toolReplaceAll(
                    tool.optString("path", ""),
                    tool.optString("old", ""),
                    tool.optString("new", "")
                )

            // ── Multi File Writer ─────────────────────────────────────────────
            "multi_file_writer" -> {
                val files = tool.optJSONArray("files")
                    ?: return@withContext AgentStep("tool_result", "❌ multi_file_writer: 'files' array required", isError = true)
                multiFileTools.toolMultiFileWriter(files)
            }

            // ── Multi Line Editor ─────────────────────────────────────────────
            "multi_line_editor" -> {
                val edits = tool.optJSONArray("edits")
                    ?: return@withContext AgentStep("tool_result", "❌ multi_line_editor: 'edits' array required", isError = true)
                multiFileTools.toolMultiLineEditor(tool.optString("path", ""), edits)
            }

            // ── File management ───────────────────────────────────────────────
            "delete_file", "delete_path", "remove_file"
                -> fileTools.toolDeleteFile(tool.optString("path", ""))
            "copy_file", "copy_path"
                -> fileTools.toolCopyFile(tool.optString("src", ""), tool.optString("dst", ""))
            "move_file", "rename_path", "move_path"
                -> fileTools.toolMoveFile(tool.optString("src", ""), tool.optString("dst", ""))
            "create_dir", "create_directory", "mkdir"
                -> fileTools.toolCreateDir(tool.optString("path", ""))
            "find_files"
                -> fileTools.toolFindFiles(
                    tool.optString("dir", tool.optString("path", getWorkingDir())),
                    tool.optString("pattern", "*"),
                    tool.optInt("max", 50)
                )

            // ── Terminal Executor ─────────────────────────────────────────────
            "terminal_executor", "run_command", "bash", "shell"
                -> bashTool.toolBash(tool.optString("cmd", ""), tool.optString("cwd", getWorkingDir()))
            "git_tool", "git"
                -> bashTool.toolBash("git ${tool.optString("args", "status")}", getWorkingDir())

            // ── Python Executor ───────────────────────────────────────────────
            "run_python", "python_exec", "python"
                -> bashTool.toolRunPython(
                    tool.optString("code", tool.optString("cmd", "")),
                    tool.optString("cwd", getWorkingDir())
                )

            // ── Node.js Executor ──────────────────────────────────────────────
            "run_node", "node_exec", "node"
                -> bashTool.toolRunNode(
                    tool.optString("code", tool.optString("cmd", "")),
                    tool.optString("cwd", getWorkingDir())
                )

            "fetch_url", "web_fetch"
                -> webTool.toolFetchUrl(
                    tool.optString("url", ""),
                    tool.optString("method", "GET"),
                    tool.optString("body", "")
                )
            "calculate"
                -> bashTool.toolCalculate(tool.optString("expr", ""))

            // ── Search Tools ──────────────────────────────────────────────────
            "project_search", "search_content", "search_files", "search"
                -> searchTools.toolSearchFiles(
                    tool.optString("dir", tool.optString("path", getWorkingDir())),
                    tool.optString("query", "")
                )
            "regex_search", "grep"
                -> searchTools.toolGrep(
                    tool.optString("dir", getWorkingDir()),
                    tool.optString("pattern", ""),
                    tool.optString("glob", "")
                )
            "semantic_search"
                -> searchTools.toolSemanticSearch(
                    tool.optString("dir", getWorkingDir()),
                    tool.optString("query", "")
                )
            "web_search"
                -> webTool.toolWebSearch(tool.optString("query", ""))

            // ── Linter ────────────────────────────────────────────────────────
            "lint", "linter"
                -> linterTool.toolLint(tool.optString("path", ""))

            // ── Git Tool ──────────────────────────────────────────────────────
            "git_status"
                -> gitTool.toolGitStatus()
            "git_diff"
                -> gitTool.toolGitDiff(tool.optString("target", "HEAD"))
            "git_log"
                -> gitTool.toolGitLog(tool.optInt("count", 10))
            "git_add"
                -> gitTool.toolGitAdd(tool.optString("paths", "."))
            "git_commit"
                -> gitTool.toolGitCommit(
                    tool.optString("message", ""),
                    tool.optBoolean("add_all", true)
                )
            "git_push"
                -> gitTool.toolGitPush(
                    tool.optString("remote", "origin"),
                    tool.optString("branch", "")
                )
            "git_branch"
                -> gitTool.toolGitBranch(tool.optString("name", ""))

            // ── Memory System ─────────────────────────────────────────────────
            "memory_save", "memory_save_long"
                -> memoryTool.toolMemorySaveLong(
                    tool.optString("key", "note").trim().replace(Regex("[^a-zA-Z0-9_\\- ]"), ""),
                    tool.optString("value", tool.optString("content", ""))
                )
            "memory_save_short"
                -> memoryTool.toolMemorySaveShort(
                    tool.optString("key", "note").trim().replace(Regex("[^a-zA-Z0-9_\\- ]"), ""),
                    tool.optString("value", tool.optString("content", ""))
                )
            "memory_recall", "memory_recall_long"
                -> memoryTool.toolMemoryRecallLong(tool.optString("key", ""))
            "memory_recall_short"
                -> memoryTool.toolMemoryRecallShort()
            "memory_recall_all"
                -> memoryTool.toolMemoryRecallAll()
            "memory_clear", "memory_clear_long"
                -> memoryTool.toolMemoryClearLong(tool.optString("key", ""))
            "memory_clear_short"
                -> memoryTool.toolMemoryClearShort()

            // ── User interaction ──────────────────────────────────────────────
            "ask_user", "ask_human" -> {
                val q  = tool.optString("question", "What should I do next?")
                val fn = askUserFn
                if (fn != null) AgentStep("tool_result", "💬 User: ${fn(q)}")
                else AgentStep("tool_result", "ℹ️ No user input available — continuing.")
            }

            // ── Complete ──────────────────────────────────────────────────────
            "complete" -> {
                val summary = tool.optString("summary", "Task complete.")
                memoryTool.recordTaskDone(summary)
                AgentStep("complete", "✅ $summary")
            }

            else -> AgentStep("tool_result", "❌ Unknown tool: \"$name\"", isError = true)
        }
    }
}
