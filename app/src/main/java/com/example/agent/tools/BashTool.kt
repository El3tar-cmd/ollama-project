package com.example.agent.tools

import android.content.Context
import com.example.data.model.AgentStep
import com.example.linux.EmbeddedLinux
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BashTool(private val context: Context, private val getWorkingDir: () -> String) {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)

    private val fetchClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ── Strategy: Embedded Linux → limited Android shell → Error ──────────────

    private fun runInEmbeddedLinux(cmd: String, cwd: String, timeoutSec: Long = 60L): AgentStep? {
        if (!EmbeddedLinux.isReady(context)) return null
        val result = EmbeddedLinux.exec(context, cmd, cwd.takeIf { File(it).exists() }, timeoutSec)
        if (!result.success && result.output.isProotInfrastructureError()) return null
        val icon = if (result.success) "✅" else "⚠️"
        val content = "$icon exit=${result.exitCode} [embedded-linux]\n$ $cmd\n${result.output.take(3000).trimEnd()}"
        return if (result.success) ok(content) else AgentStep("tool_result", content, isError = true)
    }

    private fun runInAndroidShell(cmd: String, cwd: String, timeoutSec: Long = 60L): AgentStep =
        execProcess(
            bin = "/system/bin/sh",
            args = listOf("-c", cmd),
            cwd = cwd,
            extraEnv = mapOf(
                "HOME" to getWorkingDir(),
                "PATH" to "/system/bin:/system/xbin:/vendor/bin",
                "TMPDIR" to context.cacheDir.absolutePath
            ),
            timeoutSec = timeoutSec,
            label = "android-shell"
        )

    // ── Public tool methods ───────────────────────────────────────────────────

    fun toolBash(cmd: String, cwd: String): AgentStep {
        if (cmd.isBlank()) return err("bash: cmd required")

        if (cmd.trim() in setOf("pwd", "echo \$PWD")) {
            return ok("✅ exit=0 [workspace]\n$ pwd\n${File(cwd).takeIf { it.exists() }?.absolutePath ?: getWorkingDir()}")
        }

        runInEmbeddedLinux(cmd, cwd)?.let { return it }
        return runInAndroidShell(cmd, cwd)
    }

    fun toolRunPython(code: String, cwd: String): AgentStep {
        if (code.isBlank()) return err("run_python: code required")

        if (EmbeddedLinux.isReady(context)) {
            val cmd = when {
                code.trimEnd().endsWith(".py") && !code.contains("\n") ->
                    "python3 ${shellEscape(code.trim())} 2>&1"
                else ->
                    "python3 -c ${shellEscape(code)} 2>&1"
            }
            runInEmbeddedLinux(cmd, cwd, timeoutSec = 120)?.let { return it }
        }
        return err("Python is available after Embedded Linux is installed from the Server tab.")
    }

    fun toolRunNode(code: String, cwd: String): AgentStep {
        if (code.isBlank()) return err("run_node: code required")

        if (EmbeddedLinux.isReady(context)) {
            val cmd = when {
                code.trimEnd().endsWith(".js") && !code.contains("\n") ->
                    "node ${shellEscape(code.trim())} 2>&1"
                else ->
                    "node -e ${shellEscape(code)} 2>&1"
            }
            runInEmbeddedLinux(cmd, cwd, timeoutSec = 120)?.let { return it }
        }
        return err("Node.js is available after Embedded Linux is installed from the Server tab.")
    }

    fun toolFetchUrl(url: String, method: String, body: String): AgentStep {
        if (url.isBlank()) return err("fetch_url: url required")
        return try {
            val mth = method.uppercase().ifBlank { if (body.isNotBlank()) "POST" else "GET" }
            val reqBody = when {
                mth == "GET" || mth == "HEAD" -> null
                body.isNotBlank() -> body.toByteArray().toRequestBody(
                    if (body.trimStart().startsWith("{") || body.trimStart().startsWith("["))
                        "application/json".toMediaType()
                    else "text/plain".toMediaType()
                )
                else -> ByteArray(0).toRequestBody("text/plain".toMediaType())
            }
            fetchClient.newCall(
                Request.Builder().url(url).method(mth, reqBody)
                    .header("User-Agent", "Mozilla/5.0 DevHiveIDE/1.0")
                    .header("Accept", "*/*")
                    .build()
            ).execute().use { resp ->
                ok("🌐 HTTP ${resp.code} $mth $url\n${resp.body?.string()?.take(3000) ?: "(empty)"}")
            }
        } catch (e: Exception) { err("fetch_url: ${e.message}") }
    }

    fun toolCalculate(expr: String): AgentStep {
        if (expr.isBlank()) return err("calculate: expr required")
        return try { ok("🔢 $expr = ${evalExpr(expr.trim())}") }
        catch (e: Exception) { err("calculate: ${e.message}") }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun execProcess(
        bin: String,
        args: List<String>,
        cwd: String,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSec: Long = 60L,
        label: String = "process"
    ): AgentStep {
        return try {
            val pb = ProcessBuilder(listOf(bin) + args)
            pb.directory(File(if (File(cwd).exists()) cwd else getWorkingDir()))
            pb.environment().apply {
                putAll(extraEnv)
            }
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val (outputThread, outputRef) = proc.readOutputAsync()
            val timeout = !proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (timeout) {
                proc.destroy()
                outputThread.join(1000)
                return err("timed out after ${timeoutSec}s\n${outputRef.get().take(300)}")
            }
            outputThread.join(3000)
            val output = outputRef.get()
            val icon = if (proc.exitValue() == 0) "✅" else "⚠️"
            val content = "$icon exit=${proc.exitValue()} [$label]\n$ ${(listOf(bin) + args).joinToString(" ")}\n${output.take(3000).trimEnd()}"
            if (proc.exitValue() == 0) ok(content) else AgentStep("tool_result", content, isError = true)
        } catch (e: Exception) { err("exec: ${e.message}") }
    }

    private fun Process.readOutputAsync(): Pair<Thread, AtomicReference<String>> {
        val output = AtomicReference("")
        val thread = Thread {
            output.set(
                try {
                    inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    "Failed to read process output: ${e.message}"
                }
            )
        }
        thread.isDaemon = true
        thread.start()
        return thread to output
    }

    private fun String.isProotInfrastructureError(): Boolean =
        contains("proot error:", ignoreCase = true) ||
        contains("can't create glue rootfs", ignoreCase = true) ||
        contains("PROOT_TMP_DIR", ignoreCase = true) ||
        contains("can't sanitize binding", ignoreCase = true) ||
        contains("PRoot exec error", ignoreCase = true)

    private fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun evalExpr(e: String): String {
        val r = evalMath(e.replace("\\s".toRegex(), "").replace("**", "^"))
        return if (r == kotlin.math.floor(r) && !r.isInfinite()) r.toLong().toString()
        else "%.10g".format(r).trimEnd('0').trimEnd('.')
    }

    private fun evalMath(raw: String): Double {
        var e = raw
        data class Fn(val name: String, val fn: (Double) -> Double)
        val fns = listOf(
            Fn("sqrt")  { v -> kotlin.math.sqrt(v)  },
            Fn("abs")   { v -> kotlin.math.abs(v)   },
            Fn("floor") { v -> kotlin.math.floor(v) },
            Fn("ceil")  { v -> kotlin.math.ceil(v)  },
            Fn("round") { v -> kotlin.math.round(v).toDouble() }
        )
        for (fn in fns) {
            val tag = "${fn.name}("
            while (e.contains(tag)) {
                val idx = e.indexOf(tag)
                var d = 1; var j = idx + tag.length
                while (j < e.length && d > 0) { if (e[j] == '(') d++ else if (e[j] == ')') d--; j++ }
                val inner = e.substring(idx + tag.length, j - 1)
                e = e.substring(0, idx) + fn.fn(evalMath(inner)) + e.substring(j)
            }
        }
        while (e.contains('(')) {
            val last  = e.lastIndexOf('(')
            val close = e.indexOf(')', last)
            if (close < 0) break
            e = e.substring(0, last) + evalMath(e.substring(last + 1, close)) + e.substring(close + 1)
        }
        val nums = mutableListOf<Double>()
        val ops  = mutableListOf<Char>()
        var i = 0
        while (i < e.length) {
            val c = e[i]
            if (c.isDigit() || c == '.' || (c == '-' && (i == 0 || e[i - 1] in "+-*/^%"))) {
                var j = i + 1
                while (j < e.length && (e[j].isDigit() || e[j] == '.')) j++
                nums.add(e.substring(i, j).toDouble()); i = j
            } else if (c in "+-*/^%") { ops.add(c); i++ }
            else i++
        }
        if (nums.isEmpty()) return e.toDoubleOrNull() ?: throw IllegalArgumentException("Cannot parse: $e")
        var oi = ops.indexOf('^')
        while (oi >= 0) {
            nums[oi] = Math.pow(nums[oi], nums[oi + 1])
            nums.removeAt(oi + 1); ops.removeAt(oi); oi = ops.indexOf('^')
        }
        oi = ops.indexOfFirst { it in "*/%" }
        while (oi >= 0) {
            nums[oi] = when (ops[oi]) { '*' -> nums[oi] * nums[oi + 1]; '/' -> nums[oi] / nums[oi + 1]; else -> nums[oi] % nums[oi + 1] }
            nums.removeAt(oi + 1); ops.removeAt(oi)
            oi = ops.indexOfFirst { it in "*/%" }
        }
        var r = nums[0]
        ops.forEachIndexed { k, op -> r = if (op == '+') r + nums[k + 1] else r - nums[k + 1] }
        return r
    }
}
