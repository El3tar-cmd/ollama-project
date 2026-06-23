package com.example.agent

enum class WorkflowType { SIMPLE, MEDIUM, LARGE }

object TaskClassifier {

    // Purely conversational queries — should complete in one shot, no file tools needed
    private val conversationalPatterns = listOf(
        Regex("^(hi|hello|hey|howdy|greetings)[!?.,]?$", RegexOption.IGNORE_CASE),
        Regex("what.*(your|ur).*(name|called)", RegexOption.IGNORE_CASE),
        Regex("who are you", RegexOption.IGNORE_CASE),
        Regex("what can you do", RegexOption.IGNORE_CASE),
        Regex("how are you", RegexOption.IGNORE_CASE),
        Regex("(مرحبا|أهلا|مرحباً|هاي|هاى)[!?.,]?$", RegexOption.IGNORE_CASE),
        Regex("(ما اسمك|اسمك إيه|اسمك ايه|مين انت|من أنت)", RegexOption.IGNORE_CASE),
        Regex("(كيف حالك|عامل ايه|عامل إيه)", RegexOption.IGNORE_CASE)
    )

    private val simpleKeywords = listOf(
        "what", "show", "list", "print", "read", "check", "get", "find",
        "ما", "اعرض", "اظهر", "اقرأ", "تحقق", "ابحث"
    )

    private val mediumKeywords = listOf(
        "add", "fix", "update", "change", "edit", "modify", "create", "write",
        "feature", "bug", "function", "class", "method",
        "أضف", "صلح", "عدل", "غير", "انشئ", "اكتب", "ميزة", "خطأ", "دالة"
    )

    private val largeKeywords = listOf(
        "project", "system", "app", "application", "architecture", "refactor",
        "migrate", "integrate", "implement", "develop", "build", "design",
        "multiple", "several", "full", "complete", "entire", "whole", "all files",
        "مشروع", "نظام", "تطبيق", "معمارية", "اعد", "طور", "ابني", "انشئ مشروع",
        "كل الملفات", "من البداية", "من الصفر", "كامل"
    )

    fun classify(task: String): WorkflowType {
        val lower = task.lowercase().trim()
        val words = task.trim().split(Regex("\\s+")).size

        // Conversational: greeting or identity question — always SIMPLE
        if (conversationalPatterns.any { it.containsMatchIn(lower) }) {
            return WorkflowType.SIMPLE
        }

        // Large: keyword match OR very long task
        if (words > 40 || largeKeywords.any { lower.contains(it) }) {
            return WorkflowType.LARGE
        }

        // Simple: short task + simple keywords only
        if (words <= 15 && simpleKeywords.any { lower.contains(it) } &&
            mediumKeywords.none { lower.contains(it) }) {
            return WorkflowType.SIMPLE
        }

        // Medium: default for anything in between
        return WorkflowType.MEDIUM
    }

    fun workflowLabel(type: WorkflowType): String = when (type) {
        WorkflowType.SIMPLE -> "⚡ Simple"
        WorkflowType.MEDIUM -> "🔧 Medium"
        WorkflowType.LARGE  -> "🏗️ Large"
    }
}
