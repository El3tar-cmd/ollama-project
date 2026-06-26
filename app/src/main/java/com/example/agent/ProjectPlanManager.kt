package com.example.agent

import java.io.File

data class AgentTask(val description: String, val isDone: Boolean = false)

class ProjectPlanManager(private val getWorkingDir: () -> String) {

    private val devyDir: File
        get() = File(getWorkingDir(), ".github-devy")

    private val planFile: File
        get() = File(devyDir, "plan.md")

    private val tasksFile: File
        get() = File(devyDir, "tasks.md")

    fun loadContext(): String {
        val plan = planFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val tasks = tasksFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (plan.isBlank() && tasks.isBlank()) return ""
        return buildString {
            appendLine("[ACTIVE PROJECT PLAN]")
            appendLine("Use these workspace files as the live roadmap. Update tasks.md when tasks are completed.")
            appendLine()
            appendLine(".github-devy/plan.md:")
            appendLine(plan.ifBlank { "(empty)" }.take(6000))
            appendLine()
            appendLine(".github-devy/tasks.md:")
            appendLine(tasks.ifBlank { "(empty)" }.take(6000))
        }.trim()
    }

    fun ensurePlan(userTask: String, generatedPlan: String): Boolean {
        if (planFile.exists() && tasksFile.exists()) return false

        devyDir.mkdirs()
        if (!planFile.exists()) {
            planFile.writeText(buildString {
                appendLine("# Plan")
                appendLine()
                appendLine("Scope: ${userTask.trim()}")
                appendLine()
                if (generatedPlan.isNotBlank()) {
                    appendLine(generatedPlan.trim())
                } else {
                    appendLine("1. Inspect the relevant project files.")
                    appendLine("2. Implement focused changes.")
                    appendLine("3. Verify the result.")
                    appendLine("4. Review the diff and summarize.")
                }
            }.trimEnd() + "\n")
        }

        if (!tasksFile.exists()) {
            val taskLines = generatedPlan
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.replace(Regex("""^\d+[\).\s-]+"""), "").trim() }
                .filter { it.isNotBlank() }
                .take(8)
            val fallback = listOf(
                "Inspect relevant files",
                "Apply the required changes",
                "Run verification",
                "Review git diff",
                "Summarize completion"
            )
            tasksFile.writeText(buildString {
                appendLine("# Tasks")
                appendLine()
                (taskLines.ifEmpty { fallback }).forEach { appendLine("- [ ] $it") }
            }.trimEnd() + "\n")
        }
        return true
    }

    fun updatePlan(newPlan: String) {
        planFile.writeText(newPlan.trimEnd() + "\n")
    }

    fun getTasks(): List<AgentTask> {
        if (!tasksFile.exists()) return emptyList()
        return tasksFile.readLines()
            .filter { it.trimStart().startsWith("- [") }
            .map { line ->
                val isDone = line.contains("- [x]")
                val desc = line.substringAfter("- [ ] ").substringAfter("- [x] ").trim()
                AgentTask(desc, isDone)
            }
    }

    fun updateTaskStatus(index: Int, isDone: Boolean) {
        val tasks = getTasks().toMutableList()
        if (index !in tasks.indices) return
        
        tasks[index] = tasks[index].copy(isDone = isDone)
        saveTasks(tasks)
    }

    fun addTask(description: String) {
        val tasks = getTasks().toMutableList()
        tasks.add(AgentTask(description))
        saveTasks(tasks)
    }

    fun removeTask(index: Int) {
        val tasks = getTasks().toMutableList()
        if (index !in tasks.indices) return
        tasks.removeAt(index)
        saveTasks(tasks)
    }

    fun calculateProgress(): Double {
        val tasks = getTasks()
        if (tasks.isEmpty()) return 0.0
        val completed = tasks.count { it.isDone }
        return (completed.toDouble() / tasks.size) * 100.0
    }

    private fun saveTasks(tasks: List<AgentTask>) {
        tasksFile.writeText(buildString {
            appendLine("# Tasks")
            appendLine()
            tasks.forEach { task ->
                val checkbox = if (task.isDone) "- [x]" else "- [ ]"
                appendLine("$checkbox ${task.description}")
            }
        }.trimEnd() + "\n")
    }

    // Keep legacy method for compatibility
    fun markFirstPendingDone(label: String) {
        val tasks = getTasks()
        val firstPendingIdx = tasks.indexOfFirst { !it.isDone }
        if (firstPendingIdx == -1) return
        updateTaskStatus(firstPendingIdx, true)
    }
}
