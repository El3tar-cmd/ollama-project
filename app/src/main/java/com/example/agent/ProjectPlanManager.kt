package com.example.agent

import java.io.File

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

    fun markFirstPendingDone(label: String) {
        if (!tasksFile.exists()) return
        val lines = tasksFile.readLines().toMutableList()
        val idx = lines.indexOfFirst { it.trimStart().startsWith("- [ ]") }
        if (idx < 0) return
        val current = lines[idx]
        val suffix = if (label.isBlank()) "" else " <!-- $label -->"
        lines[idx] = current.replace("- [ ]", "- [x]", ignoreCase = false) + suffix
        tasksFile.writeText(lines.joinToString("\n") + "\n")
    }
}
