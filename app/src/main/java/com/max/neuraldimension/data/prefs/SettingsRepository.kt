package com.max.neuraldimension.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "max_settings")

enum class Provider(val label: String) { GEMINI("Gemini"), OPENAI("OpenAI-compatible"), GROQ("Groq") }
enum class Personality(val label: String) { PROFESSIONAL("Professional"), SWEETHEART("Sweetheart") }

data class MaxSettings(
    val userName: String = "Rout",
    val assistantName: String = "MAX",
    val provider: Provider = Provider.GEMINI,
    val modelFast: String = "gemini-2.0-flash",
    val modelThinking: String = "gemini-2.5-flash",
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val groqBaseUrl: String = "https://api.groq.com/openai/v1",
    val openAiModel: String = "gpt-4o-mini",
    val groqModel: String = "llama-3.3-70b-versatile",
    val thinkingMode: Boolean = false,
    val personality: Personality = Personality.PROFESSIONAL,
    val speechRate: Float = 1.0f,
    val language: String = "en-IN",
    val animIntensity: Float = 1.0f,
    val reducedMotion: Boolean = false,
    val memories: Set<String> = emptySet()
)

class SettingsRepository(private val context: Context) {

    private object K {
        val USER_NAME = stringPreferencesKey("user_name")
        val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        val PROVIDER = stringPreferencesKey("provider")
        val MODEL_FAST = stringPreferencesKey("model_fast")
        val MODEL_THINKING = stringPreferencesKey("model_thinking")
        val OAI_BASE = stringPreferencesKey("oai_base")
        val GROQ_BASE = stringPreferencesKey("groq_base")
        val OAI_MODEL = stringPreferencesKey("oai_model")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val THINKING = booleanPreferencesKey("thinking_mode")
        val PERSONALITY = stringPreferencesKey("personality")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val LANGUAGE = stringPreferencesKey("language")
        val ANIM_INTENSITY = floatPreferencesKey("anim_intensity")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val MEMORIES = stringSetPreferencesKey("memories")
    }

    val settings: Flow<MaxSettings> = context.dataStore.data.map { p ->
        MaxSettings(
            userName = p[K.USER_NAME] ?: "Rout",
            assistantName = p[K.ASSISTANT_NAME] ?: "MAX",
            provider = p[K.PROVIDER]?.let { runCatching { Provider.valueOf(it) }.getOrNull() } ?: Provider.GEMINI,
            modelFast = p[K.MODEL_FAST] ?: "gemini-2.0-flash",
            modelThinking = p[K.MODEL_THINKING] ?: "gemini-2.5-flash",
            openAiBaseUrl = p[K.OAI_BASE] ?: "https://api.openai.com/v1",
            groqBaseUrl = p[K.GROQ_BASE] ?: "https://api.groq.com/openai/v1",
            openAiModel = p[K.OAI_MODEL] ?: "gpt-4o-mini",
            groqModel = p[K.GROQ_MODEL] ?: "llama-3.3-70b-versatile",
            thinkingMode = p[K.THINKING] ?: false,
            personality = p[K.PERSONALITY]?.let { runCatching { Personality.valueOf(it) }.getOrNull() } ?: Personality.PROFESSIONAL,
            speechRate = p[K.SPEECH_RATE] ?: 1.0f,
            language = p[K.LANGUAGE] ?: "en-IN",
            animIntensity = p[K.ANIM_INTENSITY] ?: 1.0f,
            reducedMotion = p[K.REDUCED_MOTION] ?: false,
            memories = p[K.MEMORIES] ?: emptySet()
        )
    }

    suspend fun update(block: (MaxSettings) -> MaxSettings) {
        // read-modify-write through DataStore prefs
        context.dataStore.edit { p ->
            val cur = snapshot(p)
            val n = block(cur)
            p[K.USER_NAME] = n.userName
            p[K.ASSISTANT_NAME] = n.assistantName
            p[K.PROVIDER] = n.provider.name
            p[K.MODEL_FAST] = n.modelFast
            p[K.MODEL_THINKING] = n.modelThinking
            p[K.OAI_BASE] = n.openAiBaseUrl
            p[K.GROQ_BASE] = n.groqBaseUrl
            p[K.OAI_MODEL] = n.openAiModel
            p[K.GROQ_MODEL] = n.groqModel
            p[K.THINKING] = n.thinkingMode
            p[K.PERSONALITY] = n.personality.name
            p[K.SPEECH_RATE] = n.speechRate
            p[K.LANGUAGE] = n.language
            p[K.ANIM_INTENSITY] = n.animIntensity
            p[K.REDUCED_MOTION] = n.reducedMotion
            p[K.MEMORIES] = n.memories
        }
    }

    private fun snapshot(p: androidx.datastore.preferences.core.Preferences) = MaxSettings(
        userName = p[K.USER_NAME] ?: "Rout",
        assistantName = p[K.ASSISTANT_NAME] ?: "MAX",
        provider = p[K.PROVIDER]?.let { runCatching { Provider.valueOf(it) }.getOrNull() } ?: Provider.GEMINI,
        modelFast = p[K.MODEL_FAST] ?: "gemini-2.0-flash",
        modelThinking = p[K.MODEL_THINKING] ?: "gemini-2.5-flash",
        openAiBaseUrl = p[K.OAI_BASE] ?: "https://api.openai.com/v1",
        groqBaseUrl = p[K.GROQ_BASE] ?: "https://api.groq.com/openai/v1",
        openAiModel = p[K.OAI_MODEL] ?: "gpt-4o-mini",
        groqModel = p[K.GROQ_MODEL] ?: "llama-3.3-70b-versatile",
        thinkingMode = p[K.THINKING] ?: false,
        personality = p[K.PERSONALITY]?.let { runCatching { Personality.valueOf(it) }.getOrNull() } ?: Personality.PROFESSIONAL,
        speechRate = p[K.SPEECH_RATE] ?: 1.0f,
        language = p[K.LANGUAGE] ?: "en-IN",
        animIntensity = p[K.ANIM_INTENSITY] ?: 1.0f,
        reducedMotion = p[K.REDUCED_MOTION] ?: false,
        memories = p[K.MEMORIES] ?: emptySet()
    )

    // --- API keys: on-device encrypted storage (Android Keystore backed) ---
    private fun secrets(): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, "max_keys", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun apiKey(provider: Provider): String =
        runCatching { secrets().getString("key_${provider.name}", "") ?: "" }.getOrDefault("")

    fun setApiKey(provider: Provider, key: String) {
        runCatching { secrets().edit().putString("key_${provider.name}", key).apply() }
    }

    fun hasKey(provider: Provider): Boolean = apiKey(provider).isNotBlank()
}
