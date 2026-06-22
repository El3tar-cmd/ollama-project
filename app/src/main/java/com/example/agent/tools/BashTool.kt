package com.example.agent.tools

import android.content.Context
import com.example.data.model.AgentStep
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

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

    fun toolBash(cmd: String, cwd: String): AgentStep {
        if (cmd.isBlank()) return err("bash: cmd required")
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.directory(File(if (File(cwd).exists()) cwd else getWorkingDir()))
            pb.environment().apply {
                put("HOME",            context.filesDir.absolutePath)
                put("TMPDIR",          context.cacheDir.absolutePath)
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                put("OLLAMA_MODELS",   File(context.filesDir, "ollama_models").absolutePath)
            }
            pb.redirectErrorStream(true)
            val proc    = pb.start()
            val output  = proc.inputStream.bufferedReader().readText()
            val timeout = !proc.waitFor(30, TimeUnit.SECONDS)
            if (timeout) { proc.destroy(); return err("bash: timed out\n${output.take(300)}") }
            val icon = if (proc.exitValue() == 0) "✅" else "⚠️"
            ok("$icon exit=${proc.exitValue()}\n\$ $cmd\n${output.take(3000).trimEnd()}")
        } catch (e: Exception) { err("bash: ${e.message}") }
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

    private fun evalExpr(e: String): String {
        val r = evalMath(e.replace("\\s".toRegex(), "").replace("**", "^"))
        return if (r == kotlin.math.floor(r) && !r.isInfinite()) r.toLong().toString()
        else "%.10g".format(r).trimEnd('0').trimEnd('.')
    }

    private fun evalMath(raw: String): Double {
        var e = raw

        data class Fn(val name: String, val fn: (Double) -> Double)
        val fns = listOf(
            Fn("sqrt")  { v -> kotlin.math.sqrt(v) },
            Fn("abs")   { v -> kotlin.math.abs(v)  },
            Fn("floor") { v -> kotlin.math.floor(v) },
            Fn("ceil")  { v -> kotlin.math.ceil(v)  },
            Fn("round") { v -> kotlin.math.round(v).toDouble() }
        )
        for (fn in fns) {
            val tag = "${fn.name}("
            while (e.contains(tag)) {
                val idx = e.indexOf(tag)
                var d = 1; var j = idx + tag.length
                while (j < e.length && d > 0) { if (e[j]=='(') d++ else if (e[j]==')') d--; j++ }
                val inner = e.substring(idx + tag.length, j - 1)
                e = e.substring(0, idx) + fn.fn(evalMath(inner)) + e.substring(j)
            }
        }

        while (e.contains('(')) {
            val last  = e.lastIndexOf('(')
            val close = e.indexOf(')', last)
            if (close < 0) break
            e = e.substring(0, last) + evalMath(e.substring(last+1, close)) + e.substring(close+1)
        }

        val nums = mutableListOf<Double>()
        val ops  = mutableListOf<Char>()
        var i = 0
        while (i < e.length) {
            val c = e[i]
            if (c.isDigit() || c == '.' ||
                (c == '-' && (i == 0 || e[i-1] in "+-*/^%"))) {
                var j = i + 1
                while (j < e.length && (e[j].isDigit() || e[j] == '.')) j++
                nums.add(e.substring(i, j).toDouble()); i = j
            } else if (c in "+-*/^%") { ops.add(c); i++ }
            else i++
        }
        if (nums.isEmpty()) return e.toDoubleOrNull() ?: throw IllegalArgumentException("Cannot parse: $e")

        var oi = ops.indexOf('^')
        while (oi >= 0) {
            nums[oi] = Math.pow(nums[oi], nums[oi+1])
            nums.removeAt(oi+1); ops.removeAt(oi); oi = ops.indexOf('^')
        }
        oi = ops.indexOfFirst { it in "*/%" }
        while (oi >= 0) {
            nums[oi] = when (ops[oi]) { '*' -> nums[oi]*nums[oi+1]; '/' -> nums[oi]/nums[oi+1]; else -> nums[oi]%nums[oi+1] }
            nums.removeAt(oi+1); ops.removeAt(oi)
            oi = ops.indexOfFirst { it in "*/%" }
        }
        var r = nums[0]
        ops.forEachIndexed { k, op -> r = if (op == '+') r + nums[k+1] else r - nums[k+1] }
        return r
    }
}
