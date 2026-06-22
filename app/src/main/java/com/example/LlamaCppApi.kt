package com.example

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlamaCppApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun checkHealth(baseUrl: String, callback: (Boolean, String) -> Unit) {
        val url = "$baseUrl/health"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) =
                callback(false, e.message ?: "unreachable")
            override fun onResponse(call: Call, response: Response) {
                response.use { callback(it.code == 200, it.message) }
            }
        })
    }

    fun chatStream(
        baseUrl: String,
        messages: List<ChatMessage>,
        temperature: Float = 0.7f,
        topP: Float = 0.95f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        maxTokens: Int = 2048,
        onToken: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ): Call {
        val msgsArray = JSONArray()
        for (m in messages) {
            msgsArray.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val body = JSONObject().apply {
            put("model", "local")
            put("messages", msgsArray)
            put("stream", true)
            put("temperature", temperature)
            put("top_p", topP)
            put("top_k", topK)
            put("repeat_penalty", repeatPenalty)
            put("max_tokens", maxTokens)
        }
        val req = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = client.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) =
                onComplete(false, e.message ?: "network error")
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onComplete(false, "HTTP ${response.code}: ${response.message}")
                    return
                }
                try {
                    val src = response.body?.source() ?: run {
                        onComplete(false, "empty body"); return
                    }
                    while (!src.exhausted()) {
                        val line = src.readUtf8Line() ?: break
                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") break
                            runCatching {
                                val delta = JSONObject(data)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("delta")
                                // Fix: optString returns "null" string when value is JSON null
                                val token = if (delta.isNull("content")) ""
                                            else delta.optString("content", "")
                                if (token.isNotEmpty()) onToken(token)
                            }
                        }
                    }
                    onComplete(true, "ok")
                } catch (e: Exception) {
                    onComplete(false, e.message ?: "stream error")
                }
            }
        })
        return call
    }
}
