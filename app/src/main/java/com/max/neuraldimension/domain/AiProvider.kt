package com.max.neuraldimension.domain

import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.Personality
import com.max.neuraldimension.data.prefs.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatTurn(val role: String, val text: String)

class AiException(message: String) : Exception(message)

object Personalities {
    fun systemPrompt(s: MaxSettings): String {
        val base = when (s.personality) {
            Personality.PROFESSIONAL ->
                "You are ${s.assistantName}, a precise, intelligent and professional AI companion. " +
                    "Be concise, technical when needed, neutral and reliable."
            Personality.SWEETHEART ->
                "You are ${s.assistantName}, a warm, gentle, playful and caring AI companion. " +
                    "Be friendly, natural and encouraging while staying honest."
        }
        val mem = if (s.memories.isEmpty()) "" else "\nRemembered facts about the user:\n" +
            s.memories.joinToString("\n") { "- $it" }
        return base + "\nThe user's name is ${s.userName}. Reply in the user's language (Hindi/Hinglish/English as they use)." + mem
    }
}

class AiClient(private val http: OkHttpClient = defaultClient()) {

    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Streams a reply, invoking onToken for each text chunk. Returns the full text. */
    suspend fun streamChat(s: MaxSettings, key: String, history: List<ChatTurn>, onToken: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) throw AiException("NO_KEY")
            when (s.provider) {
                Provider.GEMINI -> streamGemini(s, key, history, onToken)
                Provider.OPENAI -> streamOpenAi(s.openAiBaseUrl, s.openAiModel, key, s, history, onToken)
                Provider.GROQ -> streamOpenAi(s.groqBaseUrl, s.groqModel, key, s, history, onToken)
            }
        }

    /** Lightweight connection test: one tiny non-streaming generation. */
    suspend fun testConnection(s: MaxSettings, key: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw AiException("No API key saved for ${s.provider.label}")
        when (s.provider) {
            Provider.GEMINI -> {
                val model = activeModel(s)
                val body = JSONObject().put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Say OK")))))
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) "Connected to Gemini ($model)"
                    else "Gemini error ${r.code}: ${r.body?.string()?.take(200)}"
                }
            }
            Provider.OPENAI, Provider.GROQ -> {
                val base = if (s.provider == Provider.GROQ) s.groqBaseUrl else s.openAiBaseUrl
                val req = Request.Builder()
                    .url(base.trimEnd('/') + "/models")
                    .header("Authorization", "Bearer $key")
                    .get().build()
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) "Connected to ${s.provider.label}"
                    else "${s.provider.label} error ${r.code}: ${r.body?.string()?.take(200)}"
                }
            }
        }
    }

    fun activeModel(s: MaxSettings): String = when (s.provider) {
        Provider.GEMINI -> if (s.thinkingMode) s.modelThinking else s.modelFast
        Provider.OPENAI -> s.openAiModel
        Provider.GROQ -> s.groqModel
    }

    private fun streamGemini(s: MaxSettings, key: String, history: List<ChatTurn>, onToken: (String) -> Unit): String {
        val model = activeModel(s)
        val contents = JSONArray()
        history.forEach { t ->
            contents.put(JSONObject()
                .put("role", if (t.role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
        }
        val body = JSONObject()
            .put("contents", contents)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Personalities.systemPrompt(s)))))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val full = StringBuilder()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw AiException("Gemini error ${r.code}: ${r.body?.string()?.take(300)}")
            val source = r.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                try {
                    val obj = JSONObject(payload)
                    val candidates = obj.optJSONArray("candidates") ?: continue
                    val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val txt = parts.getJSONObject(i).optString("text", "")
                        if (txt.isNotEmpty()) { full.append(txt); onToken(txt) }
                    }
                } catch (_: Exception) { }
            }
        }
        return full.toString()
    }

    private fun streamOpenAi(baseUrl: String, model: String, key: String, s: MaxSettings, history: List<ChatTurn>, onToken: (String) -> Unit): String {
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", Personalities.systemPrompt(s)))
        history.forEach { t ->
            msgs.put(JSONObject().put("role", if (t.role == "user") "user" else "assistant").put("content", t.text))
        }
        val body = JSONObject().put("model", model).put("messages", msgs).put("stream", true)
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val full = StringBuilder()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw AiException("HTTP ${r.code}: ${r.body?.string()?.take(300)}")
            val source = r.body!!.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue
                try {
                    val obj = JSONObject(payload)
                    val choices = obj.optJSONArray("choices") ?: continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val txt = delta.optString("content", "")
                    if (txt.isNotEmpty()) { full.append(txt); onToken(txt) }
                } catch (_: Exception) { }
            }
        }
        return full.toString()
    }
}
