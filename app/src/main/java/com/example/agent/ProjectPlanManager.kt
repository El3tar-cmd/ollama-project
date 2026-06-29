package com.example.agent

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Task priority levels ──────────────────────────────────────────────────────
enum class TaskPriority { CRITICAL, HIGH, NORMAL, LOW }

// ── Task dependency and metadata ──────────────────────────────────────────────
data class AgentTask(
    val index: Int,
    val description: String,
    val isDone: Boolean = false,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val dependsOn: List<Int> = emptyList(),
    val notes: String = "",
    val completedAt: String = ""
)

/**
 * Advanced project plan manager with:
 * - Task dependency tracking
 * - Priority queue ordering
 * - Progress metrics & milestone detection
 * - Persistent plan + task files in .devhive/ workspace
 */
class ProjectPlanManager(private val getWorkingDir: () -> String) {

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private fun ts() = df.format(Date())

    private val devhiveDir: File
        get() = File(getWorkingDir(), ".devhive")

    private val planFile: File  get() = File(devhiveDir, "plan.md")
    private val tasksFile: File get() = File(devhiveDir, "tasks.md")

    // ── Load context for system prompt ────────────────────────────────────────
    fun loadContext(): String {
        val plan  = planFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val tasks = tasksFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (plan.isBlank() && tasks.isBlank()) return ""

        val progress = calculateProgress()

        return buildString {
            appendLine("[ACTIVE PROJECT PLAN]")
            appendLine("Progress: ${progress.toInt()}% complete")
            appendLine("Files: .devhive/plan.md | .devhive/tasks.md")
            appendLine("Rule: Update tasks.md when each step completes.")
            appendLine()
            if (plan.isNotBlank()) {
                appendLine(".devhive/plan.md:")
                appendLine(plan.take(4000))
                appendLine()
            }
            if (tasks.isNotBlank()) {
                appendLine(".devhive/tasks.md:")
                append(tasks.take(4000))
            }
        }.trim()
    }

    // ── Initialize plan + tasks from user request and generated plan ──────────
    fun ensurePlan(userTask: String, generatedPlan: String): Boolean {
        if (planFile.exists() && tasksFile.exists()) return false

        devhiveDir.mkdirs()

        if (!planFile.exists()) {
            planFile.writeText(buildString {
                appendLine("# DevHive Agent Plan")
                appendLine("_Created: ${ts()}_")
                appendLine()
                appendLine("## Objective")
                appendLine(userTask.trim())
                appendLine()
                appendLine("## Approach")
                if (generatedPlan.isNotBlank()) {
                    appendLine(generatedPlan.trim())
                } else {
                    appendLine("1. Understand the task and map the codebase structure")
                    appendLine("2. Identify and read all relevant files")
                    appendLine("3. Implement focused, targeted changes")
                    appendLine("4. Verify changes with lint and/or tests")
                    appendLine("5. Review the diff and commit if appropriate")
                    appendLine("6. Summarize all changes made")
                }
                appendLine()
                appendLine("## Notes")
                appendLine("_Updated by agent during execution_")
            })
        }

        if (!tasksFile.exists()) {
            val taskLines = generatedPlan
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.replace(Regex("""^\d+[\).\s-]+"""), "").trim() }
                .filter { it.isNotBlank() }
                .take(10)

            val fallback = listOf(
                "Map project structure and identify relevant files",
                "Read and understand the target files",
                "Implement required changes",
                "Verify changes with lint or build",
                "Review git diff",
                "Commit changes and summarize"
            )

            tasksFile.writeText(buildString {
                appendLine("# Tasks")
                appendLine("_Created: ${ts()}_")
                appendLine()
                (taskLines.ifEmpty { fallback }).forEachIndexed { i, step ->
                    appendLine("- [ ] $step")
                }
            })
        }
        return true
    }

    // ── CRUD operations on tasks ──────────────────────────────────────────────
    fun getTasks(): List<AgentTask> {
        if (!tasksFile.exists()) return emptyList()
        return tasksFile.readLines()
            .filter { it.trimStart().startsWith("- [") }
            .mapIndexed { i, line ->
                val done  = line.contains("- [x]", ignoreCase = true)
                val desc  = line
                    .replace("- [x] ", "").replace("- [ ] ", "")
                    .replace("- [X] ", "")
                    .trim()

                // Parse priority tag if present: [CRITICAL], [HIGH], [LOW]
                val priority = when {
                    desc.startsWith("[CRITICAL]") -> TaskPriority.CRITICAL
                    desc.startsWith("[HIGH]")     -> TaskPriority.HIGH
                    desc.startsWith("[LOW]")      -> TaskPriority.LOW
                    else                          -> TaskPriority.NORMAL
                }
                val cleanDesc = desc
                    .removePrefix("[CRITICAL]").removePrefix("[HIGH]")
                    .removePrefix("[LOW]").trim()

                AgentTask(i, cleanDesc, done, priority)
            }
    }

    fun getNextPendingTask(): AgentTask? =
        getTasks()
            .filter { !it.isDone }
            .sortedWith(compareBy { it.priority.ordinal })
            .firstOrNull()

    fun updateTaskStatus(index: Int, isDone: Boolean) {
        val tasks = getTasks().toMutableList()
        if (index !in tasks.indices) return
        tasks[index] = tasks[index].copy(isDone = isDone,
            completedAt = if (isDone) ts() else "")
        saveTasks(tasks)
    }

    fun markFirstPendingDone(label: String) {
        val tasks = getTasks()
        val idx   = tasks.indexOfFirst { !it.isDone }
        if (idx == -1) return
        updateTaskStatus(idx, true)
        appendPlanNote("Step ${idx + 1} completed: $label")
    }

    fun addTask(description: String, priority: TaskPriority = TaskPriority.NORMAL) {
        val tasks = getTasks().toMutableList()
        val tag   = when (priority) {
            TaskPriority.CRITICAL -> "[CRITICAL] "
            TaskPriority.HIGH     -> "[HIGH] "
            TaskPriority.LOW      -> "[LOW] "
            else                  -> ""
        }
        tasks.add(AgentTask(tasks.size, "$tag$description", priority = priority))
        saveTasks(tasks)
    }

    fun removeTask(index: Int) {
        val tasks = getTasks().toMutableList()
        if (index !in tasks.indices) return
        tasks.removeAt(index)
        saveTasks(tasks)
    }

    // ── Progress metrics ──────────────────────────────────────────────────────
    fun calculateProgress(): Double {
        val tasks = getTasks()
        if (tasks.isEmpty()) return 0.0
        return (tasks.count { it.isDone }.toDouble() / tasks.size) * 100.0
    }

    fun pendingTaskCount(): Int = getTasks().count { !it.isDone }

    fun completedTaskCount(): Int = getTasks().count { it.isDone }

    fun allTasksDone(): Boolean = getTasks().all { it.isDone }

    // ── Plan note appending ───────────────────────────────────────────────────
    fun appendPlanNote(note: String) {
        if (!planFile.exists()) return
        try {
            planFile.appendText("\n_[${ts()}] $note_\n")
        } catch (_: Exception) {}
    }

    fun updatePlan(newPlan: String) {
        planFile.writeText(newPlan.trimEnd() + "\n")
    }

    // ── Progress summary ──────────────────────────────────────────────────────
    fun progressSummary(): String {
        val tasks = getTasks()
        if (tasks.isEmpty()) return "No tasks defined."
        val done    = tasks.count { it.isDone }
        val pending = tasks.count { !it.isDone }
        val pct     = if (tasks.isEmpty()) 0 else (done * 100 / tasks.size)
        return buildString {
            appendLine("Progress: $done/${tasks.size} ($pct%)")
            tasks.forEach { t ->
                val cb = if (t.isDone) "✓" else "○"
                appendLine("  $cb ${t.index + 1}. ${t.description.take(60)}")
            }
        }.trim()
    }

    // ── Internal serializer ───────────────────────────────────────────────────
    private fun saveTasks(tasks: List<AgentTask>) {
        tasksFile.writeText(buildString {
            appendLine("# Tasks")
            appendLine("_Updated: ${ts()}_")
            appendLine()
            tasks.forEach { t ->
                val cb  = if (t.isDone) "- [x]" else "- [ ]"
                val tag = when (t.priority) {
                    TaskPriority.CRITICAL -> "[CRITICAL] "
                    TaskPriority.HIGH     -> "[HIGH] "
                    TaskPriority.LOW      -> "[LOW] "
                    else                  -> ""
                }
                val done = if (t.isDone && t.completedAt.isNotBlank()) " _(done ${t.completedAt})_" else ""
                appendLine("$cb $tag${t.description}$done")
            }
        })
    }
}
