package com.max.neuraldimension.features.assistant

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.db.Reminder
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Orchestration tests for the real ReminderWorker.doWork under a synchronous
 * test WorkManager with real Room/DataStore: quiet deferral preserves the
 * user-requested triggerAt and records deferredUntil; normal delivery posts
 * and marks fired.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderWorkerOrchestrationTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository
    private lateinit var wm: WorkManager
    private lateinit var db: AppDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        wm = WorkManager.getInstance(context)
        db = AppDb.get(context)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        repo = SettingsRepository(context)
        val main = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        shadowOf(context.packageManager).addResolveInfoForIntent(
            main,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = context.packageName
                    name = "com.max.neuraldimension.MainActivity"
                }
            }
        )
    }

    private fun setSettings(s: MaxSettings) = runBlocking { repo.update { s } }

    private fun runReminderWorker(id: Long, title: String): ListenableWorker.Result = runBlocking(Dispatchers.IO) {
        TestListenableWorkerBuilder<ReminderWorker>(context)
            .setInputData(
                Data.Builder()
                    .putLong(ReminderWorker.KEY_ID, id)
                    .putString(ReminderWorker.KEY_TITLE, title)
                    .build()
            )
            .build().doWork()
    }

    private fun notifications(): List<Notification> =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications

    private fun pendingReminders(): List<WorkInfo> =
        wm.getWorkInfosByTag("nd-reminder").get().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun quiet_deferral_preserves_requested_time_and_records_deferred_until() {
        val z = java.time.ZonedDateTime.now()
        val start = z.minusMinutes(30)
        val end = z.plusMinutes(30)
        setSettings(
            MaxSettings(
                quietHoursEnabled = true,
                quietStartMin = start.hour * 60 + start.minute,
                quietEndMin = end.hour * 60 + end.minute
            )
        )
        val requestedAt = System.currentTimeMillis()
        val id = runBlocking(Dispatchers.IO) { db.reminderDao().insert(Reminder(title = "call mom", triggerAt = requestedAt, workId = "old-work")) }

        val result = runReminderWorker(id, "call mom")

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, notifications().size)
        val row = runBlocking(Dispatchers.IO) { db.reminderDao().reminders().first() }.single()
        assertEquals(requestedAt, row.triggerAt) // user-requested time preserved
        assertTrue(!row.fired)
        assertNotEquals("old-work", row.workId)
        assertTrue(row.workId.isNotBlank())
        // deferredUntil targets the end of the quiet window (25-35 min out, at the window's HH:mm)
        val now = System.currentTimeMillis()
        assertTrue(row.deferredUntil in (now + 24 * 60_000)..(now + 36 * 60_000))
        val deferredLocal = java.time.Instant.ofEpochMilli(row.deferredUntil).atZone(java.time.ZoneId.systemDefault())
        assertEquals(end.hour * 60 + end.minute, deferredLocal.hour * 60 + deferredLocal.minute)
        assertEquals(1, pendingReminders().size)
    }

    @Test
    fun normal_delivery_posts_and_marks_fired_without_touching_deferral() {
        setSettings(MaxSettings())
        val requestedAt = System.currentTimeMillis()
        val id = runBlocking(Dispatchers.IO) { db.reminderDao().insert(Reminder(title = "standup", triggerAt = requestedAt)) }

        val result = runReminderWorker(id, "standup")

        assertTrue(result is ListenableWorker.Result.Success)
        val posted = notifications()
        assertEquals(1, posted.size)
        assertEquals("MAX Reminder", posted[0].extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("standup", posted[0].extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val row = runBlocking(Dispatchers.IO) { db.reminderDao().reminders().first() }.single()
        assertTrue(row.fired)
        assertEquals(0L, row.deferredUntil)
        assertEquals(requestedAt, row.triggerAt)
    }
}
