package com.example

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OllamaModel(
    val name: String,
    val size: Long
)

data class ChatMessage(
    val role: String,
    val content: String
)

class OllamaApi {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Standard OkHttp client for local daemon calls. */
    private val localClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()

    /**
     * Build an OkHttp client that injects `Authorization: Bearer <apiKey>` on
     * every request — used for Ollama Cloud calls to https://ollama.com.
     */
    private fun cloudClient(apiKey: String): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.MINUTES)
            .writeTimeout(15, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", "Bearer $apiKey")
                    .build()
                chain.proceed(req)
            }
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // Local daemon helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun checkRunning(baseUrl: String, callback: (Boolean, String) -> Unit) {
        val request = Request.Builder().url(baseUrl).build()
        localClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, e.message ?: "Failed connection check")
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) callback(true, response.body?.string() ?: "Online")
                else callback(false, "Offline (HTTP ${response.code})")
            }
        })
    }

    fun listModels(baseUrl: String, callback: (List<OllamaModel>?, String) -> Unit) {
        val request = Request.Builder().url("$baseUrl/api/tags").build()
        localClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, e.message ?: "Failed to list local models")
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrEmpty()) {
                        val json  = JSONObject(body)
                        val array = json.optJSONArray("models")
                        val list  = mutableListOf<OllamaModel>()
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                list.add(OllamaModel(item.getString("name"), item.optLong("size", 0)))
                            }
                        }
                        callback(list, "Success")
                    } else {
                        callback(null, "Server code ${response.code}")
                    }
                } catch (e: Exception) {
                    callback(null, "Decoding failure: ${e.message}")
                }
            }
        })
    }

    fun deleteModel(baseUrl: String, modelName: String, callback: (Boolean, String) -> Unit) {
        val body = JSONObject().put("name", modelName).toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder().url("$baseUrl/api/delete").delete(body).build()
        localClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, e.message ?: "Network error")
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.code == 200) callback(true, "Model deleted successfully")
                else callback(false, "Deletion failed: HTTP ${response.code}")
            }
        })
    }

    fun pullModelStream(
        baseUrl: String,
        modelName: String,
        onProgress: (String, Int) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        val body = JSONObject().put("name", modelName).toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder().url("$baseUrl/api/pull").post(body).build()
        localClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(false, e.message ?: "Network error")
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onComplete(false, "Server rejected pull (HTTP ${response.code})")
                    return
                }
                val source = response.body?.source() ?: run { onComplete(false, "Null response stream"); return }
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isNotEmpty()) {
                            try {
                                val json      = JSONObject(line)
                                if (json.has("error")) { onComplete(false, json.getString("error")); return }
                                val status    = json.optString("status", "")
                                val total     = json.optLong("total", 0L)
                                val completed = json.optLong("completed", 0L)
                                if (total > 0) onProgress(status, (completed * 100 / total).toInt())
                                else onProgress(status, -1)
                            } catch (_: Exception) {}
                        }
                    }
                    onComplete(true, "Model pulled successfully")
                } catch (e: Exception) {
                    onComplete(false, "Pull stream interrupted: ${e.message}")
                }
            }
        })
    }

    /**
     * Streaming chat against the LOCAL Ollama daemon.
     * No auth header needed — daemon is on localhost.
     */
    fun chatStream(
        baseUrl: String,
        modelName: String,
        messages: List<ChatMessage>,
        onTokenGenerated: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ): Call {
        val msgsArray = org.json.JSONArray().also { arr ->
            messages.forEach { msg ->
                arr.put(JSONObject().put("role", msg.role).put("content", msg.content))
            }
        }
        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", msgsArray)
            .put("stream", true)
            .toString()
        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val call = localClient.newCall(request)
        call.enqueue(streamChatCallback(call, onTokenGenerated, onComplete))
        return call
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ollama Cloud helpers  (host: https://ollama.com, auth: Bearer token)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validate that [apiKey] can authenticate with Ollama Cloud.
     * Hits GET /api/tags — a 200 response means the key is valid.
     */
    fun validateCloudApiKey(apiKey: String, callback: (Boolean, String) -> Unit) {
        if (apiKey.isBlank()) { callback(false, "API key is empty"); return }
        val request = Request.Builder()
            .url("https://ollama.com/api/tags")
            .build()
        cloudClient(apiKey).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false, "Network error: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                when (response.code) {
                    200  -> callback(true,  "Authenticated successfully")
                    401  -> callback(false, "Invalid API key (401 Unauthorized)")
                    403  -> callback(false, "Access denied (403 Forbidden)")
                    else -> callback(false, "Unexpected response: HTTP ${response.code}")
                }
            }
        })
    }

    /**
     * List models available in Ollama Cloud for this account.
     */
    fun listCloudModels(apiKey: String, callback: (List<OllamaModel>?, String) -> Unit) {
        if (apiKey.isBlank()) { callback(null, "API key is empty"); return }
        val request = Request.Builder().url("https://ollama.com/api/tags").build()
        cloudClient(apiKey).newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, e.message ?: "Network error")
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrEmpty()) {
                        val json  = JSONObject(body)
                        val array = json.optJSONArray("models")
                        val list  = mutableListOf<OllamaModel>()
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                list.add(OllamaModel(item.getString("name"), item.optLong("size", 0)))
                            }
                        }
                        callback(list, "Success")
                    } else {
                        callback(null, "HTTP ${response.code}")
                    }
                } catch (e: Exception) {
                    callback(null, "Decoding failure: ${e.message}")
                }
            }
        })
    }

    /**
     * Streaming chat against Ollama Cloud.
     * Sends `Authorization: Bearer <apiKey>` to https://ollama.com/api/chat.
     */
    fun cloudChatStream(
        apiKey: String,
        modelName: String,
        messages: List<ChatMessage>,
        onTokenGenerated: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ): Call {
        val msgsArray = org.json.JSONArray().also { arr ->
            messages.forEach { msg ->
                arr.put(JSONObject().put("role", msg.role).put("content", msg.content))
            }
        }
        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", msgsArray)
            .put("stream", true)
            .toString()
        val request = Request.Builder()
            .url("https://ollama.com/api/chat")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val call = cloudClient(apiKey).newCall(request)
        call.enqueue(streamChatCallback(call, onTokenGenerated, onComplete))
        return call
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared streaming callback
    // ─────────────────────────────────────────────────────────────────────────

    private fun streamChatCallback(
        call: Call,
        onTokenGenerated: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) = object : Callback {
        override fun onFailure(c: Call, e: IOException) {
            if (!call.isCanceled()) onComplete(false, e.message ?: "Connection error")
        }
        override fun onResponse(c: Call, response: Response) {
            if (!response.isSuccessful) {
                val body = try { response.body?.string() } catch (_: Exception) { null }
                val detail = if (!body.isNullOrBlank()) {
                    try { JSONObject(body).optString("error", "HTTP ${response.code}") }
                    catch (_: Exception) { "HTTP ${response.code}" }
                } else "HTTP ${response.code}"
                onComplete(false, detail)
                return
            }
            val source = response.body?.source()
                ?: run { onComplete(false, "Empty response stream"); return }
            try {
                while (!source.exhausted()) {
                    if (call.isCanceled()) break
                    val line = source.readUtf8Line() ?: break
                    if (line.isNotEmpty()) {
                        try {
                            val json = JSONObject(line)
                            if (json.has("error")) { onComplete(false, json.getString("error")); return }
                            val done   = json.optBoolean("done", false)
                            val token  = json.optJSONObject("message")?.optString("content", "") ?: ""
                            if (token.isNotEmpty()) onTokenGenerated(token)
                            if (done) break
                        } catch (_: Exception) {}
                    }
                }
                if (!call.isCanceled()) onComplete(true, "Done")
            } catch (e: Exception) {
                if (!call.isCanceled()) onComplete(false, "Stream interrupted: ${e.message}")
            }
        }
    }
}
