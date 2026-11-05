package com.max.neuraldimension.domain

import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.Personality
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
            Personality.CUSTOM ->
                s.customPersonality.trim().ifBlank {
                    "You are ${s.assistantName}, a precise, intelligent and professional AI companion. " +
                        "Be concise, technical when needed, neutral and reliable."
                }
        }
        val mem = if (s.memories.isEmpty()) "" else "\nRemembered facts about the user:\n" +
            s.memories.joinToString("\n") { "- $it" }
        return base + "\nThe user's name is ${s.userName}. Reply in the user's language (Hindi/Hinglish/English as they use)." + mem
    }
}

/**
 * Talks only to the user's secure backend (see backend/). Provider keys never
 * touch the APK or the phone (blueprint Part 11).
 */
class BackendClient(private val http: OkHttpClient = defaultClient()) {

    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun isConfigured(s: MaxSettings): Boolean = s.backendUrl.isNotBlank()

    suspend fun testConnection(s: MaxSettings): String = withContext(Dispatchers.IO) {
        if (!isConfigured(s)) throw AiException("Secure backend URL is not set yet")
        val req = Request.Builder()
            .url(s.backendUrl + "/v1/health")
            .apply { if (s.appToken.isNotBlank()) header("Authorization", "Bearer ${s.appToken}") }
            .get().build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.isSuccessful) "Backend reachable: ${body.take(120)}"
            else "Backend error ${r.code}: ${body.take(200)}"
        }
    }

    /** Streams a reply from the secure backend; onToken per text chunk. */
    suspend fun streamChat(s: MaxSettings, history: List<ChatTurn>, onToken: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (!isConfigured(s)) throw AiException("NO_BACKEND")
            val msgs = JSONArray()
            history.forEach { t ->
                msgs.put(JSONObject()
                    .put("role", if (t.role == "user") "user" else "assistant")
                    .put("content", t.text))
            }
            val body = JSONObject()
                .put("messages", msgs)
                .put("mode", if (s.thinkingMode) "thinking" else "fast")
                .put("system", Personalities.systemPrompt(s))
            val req = Request.Builder()
                .url(s.backendUrl + "/v1/chat")
                .apply { if (s.appToken.isNotBlank()) header("Authorization", "Bearer ${s.appToken}") }
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val full = StringBuilder()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw AiException("Backend error ${r.code}: ${r.body?.string()?.take(300)}")
                val source = r.body!!.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    try {
                        val obj = JSONObject(payload)
                        val err = obj.optString("error", "")
                        if (err.isNotEmpty()) throw AiException(err)
                        val txt = obj.optString("t", "")
                        if (txt.isNotEmpty()) { full.append(txt); onToken(txt) }
                    } catch (e: AiException) {
                        throw e
                    } catch (_: Exception) { }
                }
            }
            return@withContext full.toString()
        }
}
