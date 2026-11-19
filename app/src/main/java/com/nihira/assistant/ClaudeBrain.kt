package com.nihira.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional alternative brain using Anthropic Claude (tool calling).
 * Used only if no Gemini key is set but an Anthropic key is.
 */
class ClaudeBrain(private val apiKey: String, private val model: String) : Brain {

    override val label = "Claude ($model)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun think(userText: String): Brain.Thought = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", Brain.systemPrompt())
            .put("tools", Tools.schema)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", userText)))

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Claude API error ${response.code}: ${raw.take(300)}")
            }
            val content = JSONObject(raw).optJSONArray("content") ?: JSONArray()
            val reply = StringBuilder()
            val actions = mutableListOf<Action>()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> reply.append(block.optString("text")).append(' ')
                    "tool_use" -> actions += Action(
                        block.getString("name"),
                        block.optJSONObject("input") ?: JSONObject()
                    )
                }
            }
            Brain.Thought(reply.toString().trim(), actions)
        }
    }
}
