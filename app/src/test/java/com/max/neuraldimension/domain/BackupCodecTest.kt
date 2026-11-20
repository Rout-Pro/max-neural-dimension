package com.max.neuraldimension.domain

import com.max.neuraldimension.data.db.Reminder
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.Personality
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device-free evidence for the Part 19 backup codec: round-trip fidelity of
 * every exported field, proof that auth/PIN material never enters the
 * document, and strict rejection of malformed or out-of-range documents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCodecTest {

    private fun sample() = Backup.Payload(
        exportedAt = 1700000000000,
        settings = MaxSettings(
            userName = "Rout",
            assistantName = "MAX",
            thinkingMode = true,
            personality = Personality.SWEETHEART,
            customPersonality = "be kind",
            speechRate = 1.5f,
            language = "hi-IN",
            voiceName = "hi-in-x-hia-local",
            animIntensity = 0.7f,
            reducedMotion = true,
            memories = setOf("likes tea", "birthday Oct 17"),
            quietHoursEnabled = true,
            quietStartMin = 1320,
            quietEndMin = 420,
            briefingEnabled = true,
            briefingMinOfDay = 480,
            mouthAnimation = false,
            holoPanels = false,
            robotAccent = "cyan",
            backendUrl = "https://example.workers.dev",
            appToken = "SECRET-TOKEN",
            pinVerifier = "v1:120000:c2FsdA==:aGFzaA=="
        ),
        memories = listOf("likes tea", "birthday Oct 17"),
        reminders = listOf(
            Reminder(title = "call mom", triggerAt = 1700003600000, fired = false, deferredUntil = 0),
            Reminder(title = "old one", triggerAt = 1699990000000, fired = true, deferredUntil = 1699991000000)
        )
    )

    @Test
    fun round_trip_preserves_every_exported_field() {
        val p = sample()
        val d = Backup.decode(Backup.encode(p))
        assertEquals(p.exportedAt, d.exportedAt)
        val s = d.settings
        assertEquals("Rout", s.userName)
        assertEquals("MAX", s.assistantName)
        assertEquals(true, s.thinkingMode)
        assertEquals(Personality.SWEETHEART, s.personality)
        assertEquals("be kind", s.customPersonality)
        assertEquals(1.5f, s.speechRate, 0.0001f)
        assertEquals("hi-IN", s.language)
        assertEquals("hi-in-x-hia-local", s.voiceName)
        assertEquals(0.7f, s.animIntensity, 0.0001f)
        assertEquals(true, s.reducedMotion)
        assertEquals(true, s.quietHoursEnabled)
        assertEquals(1320, s.quietStartMin)
        assertEquals(420, s.quietEndMin)
        assertEquals(true, s.briefingEnabled)
        assertEquals(480, s.briefingMinOfDay)
        assertEquals(false, s.mouthAnimation)
        assertEquals(false, s.holoPanels)
        assertEquals("cyan", s.robotAccent)
        assertEquals(p.memories, d.memories)
        assertEquals(2, d.reminders.size)
        val fut = d.reminders[0]
        assertEquals("call mom", fut.title)
        assertEquals(1700003600000, fut.triggerAt)
        assertEquals(false, fut.fired)
        assertEquals(0, fut.deferredUntil)
        assertEquals("", fut.workId)
        val old = d.reminders[1]
        assertEquals("old one", old.title)
        assertEquals(true, old.fired)
        assertEquals(1699991000000, old.deferredUntil)
    }

    @Test
    fun export_never_contains_auth_or_pin_material() {
        val text = Backup.encode(sample())
        assertFalse(text.contains("SECRET-TOKEN"))
        assertFalse(text.contains("example.workers.dev"))
        assertFalse(text.contains("c2FsdA=="))
        assertFalse(text.contains("appToken"))
        assertFalse(text.contains("backendUrl"))
        assertFalse(text.contains("pinVerifier"))
        assertFalse(text.contains("backendVerifiedFor"))
        assertFalse(text.contains("briefingGeneration"))
    }

    @Test
    fun rejects_garbage_and_wrong_envelope() {
        assertThrows(Backup.BackupFormatException::class.java) { Backup.decode("not json at all") }
        assertThrows(Backup.BackupFormatException::class.java) { Backup.decode("{}") }
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode("""{"format":"other-app","version":1,"settings":{},"memories":[],"reminders":[]}""")
        }
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode("""{"format":"max-backup","version":2,"settings":{},"memories":[],"reminders":[]}""")
        }
    }

    @Test
    fun v1_document_without_robot_fields_decodes_with_defaults() {
        // Fields added after v1 shipped are optional on decode: older backup
        // files restore with fresh-install defaults for them.
        val doc = JSONObject(Backup.encode(sample()))
        doc.getJSONObject("settings").apply {
            remove("mouthAnimation")
            remove("holoPanels")
            remove("robotAccent")
        }
        val d = Backup.decode(doc.toString())
        assertEquals(true, d.settings.mouthAnimation)
        assertEquals(true, d.settings.holoPanels)
        assertEquals("violet", d.settings.robotAccent)

        // Wrong-typed optional fields are still rejected.
        val bad = JSONObject(Backup.encode(sample()))
        bad.getJSONObject("settings").put("mouthAnimation", "yes")
        assertThrows(Backup.BackupFormatException::class.java) { Backup.decode(bad.toString()) }
    }

    @Test
    fun rejects_invalid_reminders() {
        val base = JSONObject(Backup.encode(sample()))

        fun withReminder(mutate: (JSONObject) -> Unit): String {
            val doc = JSONObject(base.toString())
            val ra = doc.getJSONArray("reminders")
            val one = JSONObject(ra.getJSONObject(0).toString())
            mutate(one)
            doc.put("reminders", JSONArray().put(one))
            return doc.toString()
        }

        // Missing title.
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode(withReminder { it.remove("title") })
        }
        // Blank title.
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode(withReminder { it.put("title", "   ") })
        }
        // Zero triggerAt.
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode(withReminder { it.put("triggerAt", 0) })
        }
        // Negative deferredUntil.
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode(withReminder { it.put("deferredUntil", -5) })
        }
        // Wrong type for fired (string instead of boolean).
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode(withReminder { it.put("fired", "yes") })
        }
    }

    @Test
    fun rejects_invalid_settings_and_memories() {
        val base = JSONObject(Backup.encode(sample()))

        // Missing settings object entirely.
        val noSettings = JSONObject(base.toString())
        noSettings.remove("settings")
        assertThrows(Backup.BackupFormatException::class.java) { Backup.decode(noSettings.toString()) }

        fun withSettings(mutate: (JSONObject) -> Unit): String {
            val doc = JSONObject(base.toString())
            val sj = doc.getJSONObject("settings")
            mutate(sj)
            return doc.toString()
        }

        // Unknown personality.
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode(withSettings { it.put("personality", "ROBOT_OVERLORD") })
        }
        // speechRate out of range.
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode(withSettings { it.put("speechRate", 99.0) })
        }
        // quietStartMin out of range.
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode(withSettings { it.put("quietStartMin", 1440) })
        }
        // Missing userName.
        assertThrows(Backup.BackupFormatException::class.java) {
            Backup.decode(withSettings { it.remove("userName") })
        }
        // Memory entry of wrong type.
        val badMem = JSONObject(base.toString())
        badMem.put("memories", JSONArray().put(42))
        assertThrows(Backup.BackupFormatException::class.java) { Backup.decode(badMem.toString()) }
    }
}
