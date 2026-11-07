package com.max.neuraldimension.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "max_settings")

enum class Personality(val label: String) { PROFESSIONAL("Professional"), SWEETHEART("Sweetheart"), CUSTOM("Custom") }

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
    val customPersonality: String = "",
    val speechRate: Float = 1.0f,
    val language: String = "en-IN",
    /** TTS voice name from the device engine; empty = system default for the language. */
    val voiceName: String = "",
    val animIntensity: Float = 1.0f,
    val reducedMotion: Boolean = false,
    val memories: Set<String> = emptySet(),
    /** "url\n token" pair that last passed an authenticated health test; stale entries stop matching when config changes. */
    val backendVerifiedFor: String = "",
    /** Blueprint Part 17: opt-in quiet hours, OFF by default so existing reminders are not silently changed. */
    val quietHoursEnabled: Boolean = false,
    /** Quiet window start/end as local minutes-of-day; wraps midnight when start > end. */
    val quietStartMin: Int = 22 * 60,
    val quietEndMin: Int = 7 * 60,
    /** Blueprint Part 17: opt-in scheduled daily briefing, OFF by default; delivery is inexact (WorkManager). */
    val briefingEnabled: Boolean = false,
    /** Briefing clock time as local minutes-of-day. */
    val briefingMinOfDay: Int = 8 * 60
)

class SettingsRepository(private val context: Context) {

    private object K {
        val USER_NAME = stringPreferencesKey("user_name")
        val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val APP_TOKEN = stringPreferencesKey("app_token")
        val THINKING = booleanPreferencesKey("thinking_mode")
        val PERSONALITY = stringPreferencesKey("personality")
        val CUSTOM_PERSONALITY = stringPreferencesKey("custom_personality")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val LANGUAGE = stringPreferencesKey("language")
        val VOICE_NAME = stringPreferencesKey("voice_name")
        val ANIM_INTENSITY = floatPreferencesKey("anim_intensity")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val MEMORIES = stringSetPreferencesKey("memories")
        val VERIFIED = stringPreferencesKey("backend_verified_for")
        val QUIET_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val QUIET_START = intPreferencesKey("quiet_start_min")
        val QUIET_END = intPreferencesKey("quiet_end_min")
        val BRIEFING_ENABLED = booleanPreferencesKey("briefing_enabled")
        val BRIEFING_TIME = intPreferencesKey("briefing_min_of_day")
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
            p[K.CUSTOM_PERSONALITY] = n.customPersonality
            p[K.SPEECH_RATE] = n.speechRate
            p[K.LANGUAGE] = n.language
            p[K.VOICE_NAME] = n.voiceName
            p[K.ANIM_INTENSITY] = n.animIntensity
            p[K.REDUCED_MOTION] = n.reducedMotion
            p[K.MEMORIES] = n.memories
            p[K.VERIFIED] = n.backendVerifiedFor
            p[K.QUIET_ENABLED] = n.quietHoursEnabled
            p[K.QUIET_START] = n.quietStartMin
            p[K.QUIET_END] = n.quietEndMin
            p[K.BRIEFING_ENABLED] = n.briefingEnabled
            p[K.BRIEFING_TIME] = n.briefingMinOfDay
        }
    }

    private fun snapshot(p: androidx.datastore.preferences.core.Preferences) = MaxSettings(
        userName = p[K.USER_NAME] ?: "Rout",
        assistantName = p[K.ASSISTANT_NAME] ?: "MAX",
        backendUrl = p[K.BACKEND_URL] ?: "",
        appToken = p[K.APP_TOKEN] ?: "",
        thinkingMode = p[K.THINKING] ?: false,
        personality = p[K.PERSONALITY]?.let { runCatching { Personality.valueOf(it) }.getOrNull() } ?: Personality.PROFESSIONAL,
        customPersonality = p[K.CUSTOM_PERSONALITY] ?: "",
        speechRate = p[K.SPEECH_RATE] ?: 1.0f,
        language = p[K.LANGUAGE] ?: "en-IN",
        voiceName = p[K.VOICE_NAME] ?: "",
        animIntensity = p[K.ANIM_INTENSITY] ?: 1.0f,
        reducedMotion = p[K.REDUCED_MOTION] ?: false,
        memories = p[K.MEMORIES] ?: emptySet(),
        backendVerifiedFor = p[K.VERIFIED] ?: "",
        quietHoursEnabled = p[K.QUIET_ENABLED] ?: false,
        quietStartMin = p[K.QUIET_START] ?: 22 * 60,
        quietEndMin = p[K.QUIET_END] ?: 7 * 60,
        briefingEnabled = p[K.BRIEFING_ENABLED] ?: false,
        briefingMinOfDay = p[K.BRIEFING_TIME] ?: 8 * 60
    )
}
