package com.max.neuraldimension.domain

import com.max.neuraldimension.data.db.Reminder
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.Personality
import org.json.JSONArray
import org.json.JSONObject

/**
 * Blueprint Part 19: on-device backup. Exports reminders, memories and
 * user-facing settings as one JSON document and strictly validates an
 * incoming document before anything is applied.
 *
 * Auth/security state is deliberately NOT exported: backendUrl, appToken,
 * backendVerifiedFor, the PIN verifier/fail counters and the briefing
 * generation never leave the device in a plaintext file, and monotonic
 * scheduler state must never roll back. On import the caller merges these
 * settings onto the current ones so the device's sign-in and PIN survive.
 */
object Backup {

    const val FORMAT = "max-backup"
    const val VERSION = 1

    /** Thrown for any malformed or out-of-range backup document. Nothing is applied. */
    class BackupFormatException(message: String) : Exception(message)

    /** The portable content of a backup. */
    data class Payload(
        val exportedAt: Long,
        val settings: MaxSettings,
        val memories: List<String>,
        /** workId is never carried: imported reminders get fresh work on scheduling. */
        val reminders: List<Reminder>
    )

    fun encode(p: Payload): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", p.exportedAt)
        val s = p.settings
        val sj = JSONObject()
        sj.put("userName", s.userName)
        sj.put("assistantName", s.assistantName)
        sj.put("thinkingMode", s.thinkingMode)
        sj.put("personality", s.personality.name)
        sj.put("customPersonality", s.customPersonality)
        sj.put("speechRate", s.speechRate.toDouble())
        sj.put("language", s.language)
        sj.put("voiceName", s.voiceName)
        sj.put("animIntensity", s.animIntensity.toDouble())
        sj.put("reducedMotion", s.reducedMotion)
        sj.put("quietHoursEnabled", s.quietHoursEnabled)
        sj.put("quietStartMin", s.quietStartMin)
        sj.put("quietEndMin", s.quietEndMin)
        sj.put("briefingEnabled", s.briefingEnabled)
        sj.put("briefingMinOfDay", s.briefingMinOfDay)
        sj.put("mouthAnimation", s.mouthAnimation)
        sj.put("holoPanels", s.holoPanels)
        sj.put("robotAccent", s.robotAccent)
        root.put("settings", sj)
        val ma = JSONArray()
        p.memories.forEach { ma.put(it) }
        root.put("memories", ma)
        val ra = JSONArray()
        p.reminders.forEach { r ->
            val rj = JSONObject()
            rj.put("title", r.title)
            rj.put("triggerAt", r.triggerAt)
            rj.put("fired", r.fired)
            rj.put("deferredUntil", r.deferredUntil)
            ra.put(rj)
        }
        root.put("reminders", ra)
        return root.toString(2)
    }

    /** Parses and fully validates a backup document. Throws BackupFormatException on any problem. */
    fun decode(text: String): Payload {
        val root = try { JSONObject(text) } catch (e: Exception) {
            throw BackupFormatException("not valid JSON")
        }
        val fmt = root.opt("format")
        if (fmt !is String || fmt != FORMAT) throw BackupFormatException("not a MAX backup")
        val ver = root.opt("version")
        if (ver !is Int || ver != VERSION) throw BackupFormatException("unsupported backup version")
        val exportedAt = when (val v = root.opt("exportedAt")) {
            is Long -> v
            is Int -> v.toLong()
            else -> 0L
        }

        val sj = root.optJSONObject("settings") ?: throw BackupFormatException("missing settings")
        val personality = try {
            Personality.valueOf(sj.reqString("personality"))
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("unknown personality")
        }
        val speechRate = sj.reqNum("speechRate").toFloat()
        if (speechRate < 0.25f || speechRate > 4f) throw BackupFormatException("speechRate out of range")
        val anim = sj.reqNum("animIntensity").toFloat()
        if (anim < 0f || anim > 1f) throw BackupFormatException("animIntensity out of range")
        val qStart = sj.reqInt("quietStartMin")
        val qEnd = sj.reqInt("quietEndMin")
        val bMin = sj.reqInt("briefingMinOfDay")
        if (qStart !in 0..1439 || qEnd !in 0..1439 || bMin !in 0..1439) {
            throw BackupFormatException("minutes-of-day out of range")
        }
        val settings = MaxSettings(
            userName = sj.reqString("userName"),
            assistantName = sj.reqString("assistantName"),
            thinkingMode = sj.reqBool("thinkingMode"),
            personality = personality,
            customPersonality = sj.reqString("customPersonality"),
            speechRate = speechRate,
            language = sj.reqString("language"),
            voiceName = sj.reqString("voiceName"),
            animIntensity = anim,
            reducedMotion = sj.reqBool("reducedMotion"),
            quietHoursEnabled = sj.reqBool("quietHoursEnabled"),
            quietStartMin = qStart,
            quietEndMin = qEnd,
            briefingEnabled = sj.reqBool("briefingEnabled"),
            briefingMinOfDay = bMin,
            // Added after v1 shipped: optional on decode so older backup files
            // still restore, falling back to the same defaults as a fresh install.
            mouthAnimation = sj.optBoolOr("mouthAnimation", true),
            holoPanels = sj.optBoolOr("holoPanels", true),
            robotAccent = sj.optStringOr("robotAccent", "violet")
        )

        val ma = root.optJSONArray("memories") ?: throw BackupFormatException("missing memories")
        val memories = List(ma.length()) { i ->
            val v = ma.opt(i)
            if (v !is String) throw BackupFormatException("invalid memory entry")
            v
        }

        val ra = root.optJSONArray("reminders") ?: throw BackupFormatException("missing reminders")
        val reminders = List(ra.length()) { i ->
            val rj = ra.optJSONObject(i) ?: throw BackupFormatException("invalid reminder entry")
            val title = rj.reqString("title")
            if (title.isBlank()) throw BackupFormatException("reminder with blank title")
            val triggerAt = rj.reqLong("triggerAt")
            if (triggerAt <= 0) throw BackupFormatException("reminder triggerAt out of range")
            val deferred = rj.reqLong("deferredUntil")
            if (deferred < 0) throw BackupFormatException("reminder deferredUntil out of range")
            Reminder(
                title = title,
                triggerAt = triggerAt,
                fired = rj.reqBool("fired"),
                deferredUntil = deferred,
                workId = ""
            )
        }

        return Payload(exportedAt = exportedAt, settings = settings, memories = memories, reminders = reminders)
    }

    private fun JSONObject.reqString(k: String): String {
        val v = opt(k)
        if (v !is String) throw BackupFormatException("missing or invalid '$k'")
        return v
    }

    private fun JSONObject.reqBool(k: String): Boolean {
        val v = opt(k)
        if (v !is Boolean) throw BackupFormatException("missing or invalid '$k'")
        return v
    }

    private fun JSONObject.reqInt(k: String): Int {
        val v = opt(k)
        if (v !is Int) throw BackupFormatException("missing or invalid '$k'")
        return v
    }

    private fun JSONObject.reqLong(k: String): Long {
        return when (val v = opt(k)) {
            is Long -> v
            is Int -> v.toLong()
            else -> throw BackupFormatException("missing or invalid '$k'")
        }
    }

    private fun JSONObject.reqNum(k: String): Double {
        val v = opt(k)
        if (v !is Number) throw BackupFormatException("missing or invalid '$k'")
        return v.toDouble()
    }

    private fun JSONObject.optBoolOr(k: String, def: Boolean): Boolean {
        val v = opt(k) ?: return def
        if (v !is Boolean) throw BackupFormatException("invalid '$k'")
        return v
    }

    private fun JSONObject.optStringOr(k: String, def: String): String {
        val v = opt(k) ?: return def
        if (v !is String) throw BackupFormatException("invalid '$k'")
        return v
    }
}
