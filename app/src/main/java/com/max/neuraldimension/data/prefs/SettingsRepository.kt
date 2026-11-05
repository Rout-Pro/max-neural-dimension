package com.max.neuraldimension.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "max_settings")

enum class Personality(val label: String) { PROFESSIONAL("Professional"), SWEETHEART("Sweetheart") }

/**
 * Blueprint Part 11: provider secret keys live on a secure backend, never in the
 * APK or on the phone. The app only stores the backend endpoint and the app token
 * that authorizes this install against it.
 */
data class MaxSettings(
    val userName: String = "Rout",
    val assistantName: String = "MAX",
    val backendUrl: String = "",
    val appToken: String = "",
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
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val APP_TOKEN = stringPreferencesKey("app_token")
        val THINKING = booleanPreferencesKey("thinking_mode")
        val PERSONALITY = stringPreferencesKey("personality")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val LANGUAGE = stringPreferencesKey("language")
        val ANIM_INTENSITY = floatPreferencesKey("anim_intensity")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val MEMORIES = stringSetPreferencesKey("memories")
    }

    val settings: Flow<MaxSettings> = context.dataStore.data.map { p -> snapshot(p) }

    suspend fun update(block: (MaxSettings) -> MaxSettings) {
        context.dataStore.edit { p ->
            val n = block(snapshot(p))
            p[K.USER_NAME] = n.userName
            p[K.ASSISTANT_NAME] = n.assistantName
            p[K.BACKEND_URL] = n.backendUrl.trim().trimEnd('/')
            p[K.APP_TOKEN] = n.appToken.trim()
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
        backendUrl = p[K.BACKEND_URL] ?: "",
        appToken = p[K.APP_TOKEN] ?: "",
        thinkingMode = p[K.THINKING] ?: false,
        personality = p[K.PERSONALITY]?.let { runCatching { Personality.valueOf(it) }.getOrNull() } ?: Personality.PROFESSIONAL,
        speechRate = p[K.SPEECH_RATE] ?: 1.0f,
        language = p[K.LANGUAGE] ?: "en-IN",
        animIntensity = p[K.ANIM_INTENSITY] ?: 1.0f,
        reducedMotion = p[K.REDUCED_MOTION] ?: false,
        memories = p[K.MEMORIES] ?: emptySet()
    )
}
