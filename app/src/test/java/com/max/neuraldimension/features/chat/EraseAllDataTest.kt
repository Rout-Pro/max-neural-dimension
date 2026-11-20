package com.max.neuraldimension.features.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.db.Note
import com.max.neuraldimension.data.db.SecurityEvent
import com.max.neuraldimension.data.db.TaskItem
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import com.max.neuraldimension.domain.PinLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device-free (Robolectric) evidence for the Part 19 user-controlled data
 * deletion: seeds DataStore, Room and attachment files, runs the production
 * wipe, then asserts every store is empty, settings are defaults (PIN gone),
 * files are deleted and a single data_wiped event remains in the security
 * history. Proves the wipe logic only; real-device file/WorkManager behavior
 * is unproved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EraseAllDataTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDb
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        db = AppDb.get(app)
        repo = SettingsRepository(app)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
    }

    @Test
    fun wipe_clears_tables_settings_files_and_leaves_one_wipe_event() {
        // Seed: settings with PIN + memories, Room rows, attachment files.
        runBlocking(Dispatchers.IO) {
            repo.update {
                MaxSettings(
                    pinVerifier = PinLock.newVerifier("1234"),
                    memories = setOf("likes tea"),
                    quietHoursEnabled = true
                )
            }
            db.taskDao().insert(TaskItem(title = "do thing"))
            db.noteDao().insert(Note(text = "secret note"))
            db.securityDao().insert(SecurityEvent(type = "pin_set", ts = 1000))
        }
        val att = java.io.File(app.filesDir, "att_test.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val keep = java.io.File(app.filesDir, "other.bin").apply { writeBytes(byteArrayOf(4)) }
        assertTrue(att.exists())

        val vm = AppViewModel(app)
        runBlocking(Dispatchers.IO) { vm.wipeNow() }

        runBlocking(Dispatchers.IO) {
            assertTrue(db.taskDao().tasks().first().isEmpty())
            assertTrue(db.noteDao().notes().first().isEmpty())
            val events = db.securityDao().events().first()
            assertEquals(1, events.size)
            assertEquals("data_wiped", events[0].type)
            val s = repo.settings.first()
            assertEquals(MaxSettings(), s)
            assertFalse(PinLock.isSet(s.pinVerifier))
        }
        assertFalse(att.exists())
        assertTrue("non-attachment files are untouched", keep.exists())
        assertEquals(false, vm.lockState.value)
        keep.delete()
    }

    @Test
    fun wipe_on_empty_stores_is_clean_and_idempotent() {
        val vm = AppViewModel(app)
        runBlocking(Dispatchers.IO) {
            vm.wipeNow()
            vm.wipeNow()
            // Each wipe erases prior history (clearAllTables) and leaves exactly one fresh data_wiped record.
            val events = db.securityDao().events().first()
            assertEquals(1, events.size)
            assertEquals("data_wiped", events[0].type)
            assertEquals(MaxSettings(), repo.settings.first())
        }
    }
}
