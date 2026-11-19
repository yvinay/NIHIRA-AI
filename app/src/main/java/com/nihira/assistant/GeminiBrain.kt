package com.nihira.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Phase 2 brain using Google Gemini (function calling).
 * Get a free key at https://aistudio.google.com/apikey
 */
class GeminiBrain(private val apiKey: String, private val model: String) : Brain {

    override val label = "Gemini ($model)"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** Busy / overloaded / slow: worth trying again or with another model. */
    private class TryAgain(msg: String) : IOException(msg)

    /**
     * Your model first (tried twice), then lighter models that are usually less busy.
     * Google sometimes returns "503 high demand"; this keeps NIHIRA working through it.
     */
    private val attempts: List<String> = listOf(model, model) +
        FALLBACK_MODELS.filter { it != model }

    /** Converts our shared tool list into Gemini's functionDeclarations format. */
    private val functionDeclarations: JSONArray by lazy {
        val out = JSONArray()
        for (i in 0 until Tools.schema.length()) {
            val t = Tools.schema.getJSONObject(i)
            out.put(JSONObject()
                .put("name", t.getString("name"))
                .put("description", t.getString("description"))
                .put("parameters", t.getJSONObject("input_schema")))
        }
        out
    }

    override suspend fun think(userText: String): Brain.Thought = withContext(Dispatchers.IO) {
        var lastProblem = "unknown problem"
        for ((i, m) in attempts.withIndex()) {
            try {
                return@withContext ask(m, userText)
            } catch (e: TryAgain) {
                lastProblem = e.message ?: "busy"
            } catch (e: InterruptedIOException) {      // timeout
                lastProblem = "Gemini took too long to answer"
            } catch (e: ModelNotFound) {
                lastProblem = e.message ?: "model not found"
                continue                                 // no wait, just try the next model
            }
            if (i < attempts.lastIndex) delay(700L * (i + 1))
        }
        throw IOException("Gemini is busy right now ($lastProblem).")
    }

    private class ModelNotFound(msg: String) : IOException(msg)

    private fun ask(model: String, userText: String): Brain.Thought {
        val body = JSONObject()
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", Brain.systemPrompt()))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userText)))))
            .put("tools", JSONArray().put(JSONObject()
                .put("functionDeclarations", functionDeclarations)))
            .put("generationConfig", JSONObject().put("temperature", 0))

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching {
                    JSONObject(raw).getJSONObject("error").getString("message")
                }.getOrDefault(raw.take(300))
                when (response.code) {
                    429, 500, 502, 503, 504 -> throw TryAgain("$model: ${response.code}")
                    404 -> throw ModelNotFound("$model not available")
                    else -> throw IOException("Gemini error ${response.code}: $msg")   // e.g. wrong API key
                }
            }

            val json = JSONObject(raw)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw IOException("Gemini gave no answer (${json.optJSONObject("promptFeedback")?.optString("blockReason") ?: "empty"}).")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()

            val reply = StringBuilder()
            val actions = mutableListOf<Action>()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought", false)) continue          // skip internal reasoning
                part.optJSONObject("functionCall")?.let { fc ->
                    actions += Action(fc.getString("name"), fc.optJSONObject("args") ?: JSONObject())
                }
                if (part.has("text")) reply.append(part.optString("text")).append(' ')
            }
            return Brain.Thought(reply.toString().trim(), actions)
        }
    }

    companion object {
        /** Tried in order when the main model is busy. Change GEMINI_MODEL to pick the first one. */
        private val FALLBACK_MODELS = listOf("gemini-3.5-flash-lite", "gemini-2.5-flash")
    }
}
