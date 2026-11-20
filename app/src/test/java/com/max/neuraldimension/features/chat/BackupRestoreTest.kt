package com.max.neuraldimension.features.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.db.Reminder
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import com.max.neuraldimension.domain.Backup
import com.max.neuraldimension.domain.PinLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device-free (Robolectric) evidence for the Part 19 backup/restore flow:
 * export writes a JSON file in app storage, a full wipe, then import restores
 * settings fields, memories and reminders while the device's auth/PIN state
 * is NOT resurrected from the file (it was wiped and is never exported).
 * WorkManager static state persists across test classes in this JVM, so
 * scheduling of the imported future reminder may or may not run here; the
 * assertions accept both and check consistency either way. Real-device
 * scheduling behavior is unproved by this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDb
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        db = AppDb.get(app)
        repo = SettingsRepository(app)
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
            repo.update { MaxSettings() }
        }
    }

    @Test
    fun export_then_import_after_wipe_restores_data_without_auth_state() {
        val futureAt = System.currentTimeMillis() + 3_600_000
        runBlocking(Dispatchers.IO) {
            repo.update {
                MaxSettings(
                    userName = "Routy",
                    quietHoursEnabled = true,
                    quietStartMin = 1300,
                    memories = setOf("m1", "m2"),
                    briefingEnabled = true,
                    appToken = "tok-secret",
                    pinVerifier = PinLock.newVerifier("9999")
                )
            }
            db.reminderDao().insert(Reminder(title = "future", triggerAt = futureAt))
            db.reminderDao().insert(Reminder(title = "past fired", triggerAt = 1000, fired = true))
        }
        val vm = AppViewModel(app)
        val f = runBlocking(Dispatchers.IO) { vm.exportBackup() }
        assertTrue(f.exists())
        assertTrue(vm.latestBackupFile() != null)

        // Production wipe: everything gone, settings back to defaults.
        runBlocking(Dispatchers.IO) { vm.wipeNow() }
        runBlocking(Dispatchers.IO) {
            assertEquals(MaxSettings(), repo.settings.first())
            assertTrue(db.reminderDao().reminders().first().isEmpty())
        }

        val sum = runBlocking(Dispatchers.IO) { vm.importBackup(f.readText()) }
        assertEquals(2, sum.memories)
        assertEquals(2, sum.reminders)
        // 0 when WorkManager is uninitialized in this JVM, 1 when a prior test
        // class initialized it; both are valid here.
        assertTrue(sum.scheduled == 0 || sum.scheduled == 1)

        runBlocking(Dispatchers.IO) {
            val s = repo.settings.first()
            assertEquals("Routy", s.userName)
            assertEquals(true, s.quietHoursEnabled)
            assertEquals(1300, s.quietStartMin)
            assertEquals(true, s.briefingEnabled)
            assertEquals(setOf("m1", "m2"), s.memories)
            // Auth state was wiped and is not carried by the backup.
            assertEquals("", s.appToken)
            assertFalse(PinLock.isSet(s.pinVerifier))

            val rs = db.reminderDao().reminders().first()
            assertEquals(2, rs.size)
            val fut = rs.first { it.title == "future" }
            assertFalse(fut.fired)
            assertEquals(futureAt, fut.triggerAt)
            if (sum.scheduled == 1) assertTrue(fut.workId.isNotBlank()) else assertEquals("", fut.workId)
            val past = rs.first { it.title == "past fired" }
            assertTrue(past.fired)
        }
        f.delete()
    }

    @Test
    fun malformed_backup_changes_nothing() {
        runBlocking(Dispatchers.IO) {
            repo.update { MaxSettings(userName = "KeepMe", memories = setOf("keep")) }
            db.reminderDao().insert(Reminder(title = "stay", triggerAt = 123))
        }
        val vm = AppViewModel(app)
        val badDocs = listOf(
            "not json at all",
            "{}",
            """{"format":"max-backup","version":99,"settings":{},"memories":[],"reminders":[]}""",
            """{"format":"max-backup","version":1,"settings":{"userName":"X"},"memories":[],"reminders":[]}"""
        )
        badDocs.forEach { doc ->
            assertThrows(Backup.BackupFormatException::class.java) {
                runBlocking(Dispatchers.IO) { vm.importBackup(doc) }
            }
        }
        runBlocking(Dispatchers.IO) {
            val s = repo.settings.first()
            assertEquals("KeepMe", s.userName)
            assertEquals(setOf("keep"), s.memories)
            val rs = db.reminderDao().reminders().first()
            assertEquals(1, rs.size)
            assertEquals("stay", rs[0].title)
        }
    }
}
