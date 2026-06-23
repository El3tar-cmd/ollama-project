package com.example.agent.tools

import android.content.Context
import com.example.data.model.AgentStep
import java.io.File
import java.util.concurrent.TimeUnit

class GitTool(private val context: Context, private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)

    private fun runGit(vararg args: String, cwd: String = getWorkingDir()): Pair<Int, String> {
        return try {
            val gitBinary = findGit()
            val cmd = if (gitBinary != null) listOf(gitBinary) + args.toList()
                      else listOf("/system/bin/sh", "-c", "git ${args.joinToString(" ")}")

            val pb = ProcessBuilder(cmd)
            pb.directory(File(cwd))
            pb.environment().apply {
                put("HOME",   context.filesDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                put("GIT_TERMINAL_PROMPT", "0")
                val pat = System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN") ?: ""
                if (pat.isNotBlank()) put("GIT_ASKPASS", "echo")
            }
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out  = proc.inputStream.bufferedReader().readText()
            proc.waitFor(30, TimeUnit.SECONDS)
            Pair(proc.exitValue(), out.trim())
        } catch (e: Exception) {
            Pair(-1, "git error: ${e.message}")
        }
    }

    private fun findGit(): String? {
        val candidates = listOf("/usr/bin/git", "/usr/local/bin/git", "/bin/git")
        return candidates.firstOrNull { File(it).exists() }
    }

    // ── git_status ────────────────────────────────────────────────────────────
    fun toolGitStatus(): AgentStep {
        val (code, out) = runGit("status", "--short", "--branch")
        return if (code == 0 || out.isNotBlank())
            ok("📊 git status\n$out")
        else err("git status failed (is this a git repo?)")
    }

    // ── git_diff ──────────────────────────────────────────────────────────────
    fun toolGitDiff(target: String): AgentStep {
        val t    = target.ifBlank { "HEAD" }
        val args = if (t == "staged") arrayOf("diff", "--cached") else arrayOf("diff", t)
        val (code, out) = runGit(*args)
        if (out.isBlank()) return ok("✅ git diff [$t]: No changes detected.")

        val summary = analyzeDiff(out)
        return ok("🔍 git diff [$t]\n$summary\n\n${out.take(3000)}")
    }

    // ── git_log ───────────────────────────────────────────────────────────────
    fun toolGitLog(count: Int): AgentStep {
        val n = count.coerceIn(1, 20)
        val (code, out) = runGit("log", "--oneline", "--graph", "-$n")
        return if (out.isNotBlank()) ok("📜 git log (last $n)\n$out")
        else err("git log failed")
    }

    // ── git_add ───────────────────────────────────────────────────────────────
    fun toolGitAdd(paths: String): AgentStep {
        val p    = paths.ifBlank { "." }
        val args = if (p == ".") arrayOf("add", "-A") else arrayOf("add", p)
        val (code, out) = runGit(*args)
        return if (code == 0) ok("✅ git add: staged ${if (p == ".") "all changes" else p}")
        else err("git add failed: $out")
    }

    // ── git_commit ────────────────────────────────────────────────────────────
    fun toolGitCommit(message: String, addAll: Boolean): AgentStep {
        if (message.isBlank()) return err("git_commit: message required")
        if (addAll) {
            val (addCode, addOut) = runGit("add", "-A")
            if (addCode != 0 && addOut.contains("error", ignoreCase = true))
                return err("git add -A failed: $addOut")
        }
        val (code, out) = runGit("commit", "-m", message)
        return if (code == 0) ok("✅ git commit: \"$message\"\n$out")
        else if (out.contains("nothing to commit")) ok("ℹ️ Nothing to commit — working tree clean.")
        else err("git commit failed: $out")
    }

    // ── git_push ──────────────────────────────────────────────────────────────
    fun toolGitPush(remote: String, branch: String): AgentStep {
        val r = remote.ifBlank { "origin" }
        val b = branch.ifBlank { "" }

        // Inject PAT into remote URL if available
        val pat = System.getenv("GITHUB_PERSONAL_ACCESS_TOKEN") ?: ""
        if (pat.isNotBlank()) {
            val (urlCode, urlOut) = runGit("remote", "get-url", r)
            if (urlCode == 0 && urlOut.contains("github.com")) {
                val authedUrl = urlOut.trim()
                    .replace("https://github.com/", "https://$pat@github.com/")
                    .replace("https://.*@github.com/".toRegex(), "https://$pat@github.com/")
                runGit("remote", "set-url", r, authedUrl)
            }
        }

        val args = if (b.isBlank()) arrayOf("push", r) else arrayOf("push", r, b)
        val (code, out) = runGit(*args)
        return if (code == 0 || out.contains("up-to-date", ignoreCase = true))
            ok("🚀 git push → $r${if (b.isNotBlank()) "/$b" else ""}\n$out")
        else err("git push failed: $out")
    }

    // ── git_branch ────────────────────────────────────────────────────────────
    fun toolGitBranch(newBranch: String): AgentStep {
        return if (newBranch.isBlank()) {
            val (_, out) = runGit("branch", "-a")
            ok("🌿 Branches:\n$out")
        } else {
            val (code, out) = runGit("checkout", "-b", newBranch)
            if (code == 0) ok("✅ Created & switched to branch: $newBranch")
            else err("git branch failed: $out")
        }
    }

    // ── Analyze diff for a human-readable summary ─────────────────────────────
    private fun analyzeDiff(diff: String): String {
        val lines    = diff.lines()
        val added    = lines.count { it.startsWith("+") && !it.startsWith("+++") }
        val removed  = lines.count { it.startsWith("-") && !it.startsWith("---") }
        val files    = lines.filter { it.startsWith("diff --git") }
            .map { it.substringAfterLast("/") }

        return buildString {
            appendLine("📊 Summary: +$added / -$removed lines across ${files.size} file(s)")
            if (files.isNotEmpty()) appendLine("Files: ${files.take(10).joinToString(", ")}")
        }.trimEnd()
    }
}
