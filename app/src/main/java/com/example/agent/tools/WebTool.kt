package com.example.agent.tools

import com.example.data.model.AgentStep
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebTool {

    private fun ok(msg: String)  = AgentStep("tool_result", msg)
    private fun err(msg: String) = AgentStep("tool_result", "❌ $msg", isError = true)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
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
            client.newCall(
                Request.Builder().url(url).method(mth, reqBody)
                    .header("User-Agent", "Mozilla/5.0 DevHiveIDE/1.0")
                    .header("Accept", "*/*")
                    .build()
            ).execute().use { resp ->
                ok("🌐 HTTP ${resp.code} $mth $url\n${resp.body?.string()?.take(3000) ?: "(empty)"}")
            }
        } catch (e: Exception) { err("fetch_url: ${e.message}") }
    }

    fun toolWebSearch(query: String): AgentStep {
        if (query.isBlank()) return err("web_search: query required")
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val step = toolFetchUrl(url, "GET", "")
            val body = step.content.substringAfter("\n").trim()
            try {
                val json = JSONObject(body)
                val sb = StringBuilder("🔍 Web search: \"$query\"\n\n")
                val abstract = json.optString("AbstractText","")
                val absUrl   = json.optString("AbstractURL","")
                if (abstract.isNotBlank()) {
                    sb.appendLine(abstract)
                    if (absUrl.isNotBlank()) sb.appendLine("Source: $absUrl")
                    sb.appendLine()
                }
                val results = json.optJSONArray("Results")
                if (results != null && results.length() > 0) {
                    sb.appendLine("Results:")
                    for (i in 0 until minOf(results.length(), 5)) {
                        val r = results.optJSONObject(i) ?: continue
                        sb.appendLine("• ${r.optString("Text","")}")
                        sb.appendLine("  ${r.optString("FirstURL","")}")
                    }
                    sb.appendLine()
                }
                val related = json.optJSONArray("RelatedTopics")
                if (sb.length < 120 && related != null) {
                    for (i in 0 until minOf(related.length(), 4)) {
                        val r = related.optJSONObject(i) ?: continue
                        val txt = r.optString("Text","")
                        if (txt.isNotBlank()) sb.appendLine("• $txt")
                    }
                }
                ok(sb.trimEnd().toString().ifBlank { "No results found for: $query" })
            } catch (_: Exception) { step }
        } catch (e: Exception) { err("web_search: ${e.message}") }
    }
}
