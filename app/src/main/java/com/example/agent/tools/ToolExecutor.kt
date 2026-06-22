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

    private val fileTools   = FileTools(getWorkingDir)
    private val bashTool    = BashTool(context, getWorkingDir)
    private val webTool     = WebTool()
    private val searchTools = SearchTools(getWorkingDir)
    private val memoryTool  = MemoryTool(context, getWorkingDir)

    suspend fun executeTool(tool: JSONObject): AgentStep = withContext(Dispatchers.IO) {
        when (val name = tool.optString("name", "")) {

            "think" -> AgentStep("think", "💭 ${tool.optString("content")}")
            "plan"  -> {
                val arr = tool.optJSONArray("steps")
                val sb  = StringBuilder("📋 Plan\n")
                if (arr != null) for (i in 0 until arr.length()) sb.appendLine("  ${i+1}. ${arr.optString(i)}")
                AgentStep("think", sb.trimEnd().toString())
            }

            "list_dir", "list"
                -> fileTools.toolListDir(tool.optString("path", getWorkingDir()))
            "read_file"
                -> fileTools.toolReadFile(tool.optString("path", ""))
            "read_lines"
                -> fileTools.toolReadLines(tool.optString("path",""), tool.optInt("start",1), tool.optInt("end",50))
            "write_file"
                -> fileTools.toolWriteFile(tool.optString("path",""), tool.optString("content",""))
            "append_file"
                -> fileTools.toolAppendFile(tool.optString("path",""), tool.optString("content",""))
            "edit_file"
                -> fileTools.toolEditFile(tool.optString("path",""), tool.optString("old",""), tool.optString("new",""))
            "delete_file"
                -> fileTools.toolDeleteFile(tool.optString("path",""))
            "move_file"
                -> fileTools.toolMoveFile(tool.optString("src",""), tool.optString("dst",""))
            "create_dir"
                -> fileTools.toolCreateDir(tool.optString("path",""))

            "bash"
                -> bashTool.toolBash(tool.optString("cmd",""), tool.optString("cwd", getWorkingDir()))
            "git"
                -> bashTool.toolBash("git ${tool.optString("args","status")}", getWorkingDir())
            "fetch_url"
                -> webTool.toolFetchUrl(tool.optString("url",""), tool.optString("method","GET"), tool.optString("body",""))
            "calculate"
                -> bashTool.toolCalculate(tool.optString("expr",""))

            "search_files", "search"
                -> searchTools.toolSearchFiles(
                    tool.optString("dir", tool.optString("path", getWorkingDir())),
                    tool.optString("query","")
                )
            "grep"
                -> searchTools.toolGrep(
                    tool.optString("dir", getWorkingDir()),
                    tool.optString("pattern",""),
                    tool.optString("glob","")
                )
            "web_search"
                -> webTool.toolWebSearch(tool.optString("query",""))

            "memory_save"
                -> memoryTool.toolMemorySave(
                    tool.optString("key","note").trim().replace(Regex("[^a-zA-Z0-9_\\- ]"), ""),
                    tool.optString("value","")
                )
            "memory_recall" -> memoryTool.toolMemoryRecall()
            "memory_clear"  -> memoryTool.toolMemoryClear(tool.optString("key",""))

            "ask_user" -> {
                val q  = tool.optString("question", "What should I do next?")
                val fn = askUserFn
                if (fn != null) AgentStep("tool_result", "💬 User replied: ${fn(q)}")
                else AgentStep("tool_result", "ℹ️ No user available — continuing.")
            }

            "complete" -> {
                val summary = tool.optString("summary", "Task complete.")
                memoryTool.recordTaskDone(summary)
                AgentStep("complete", "✅ $summary")
            }

            else -> AgentStep("tool_result", "❌ Unknown tool: \"$name\"", isError = true)
        }
    }
}
