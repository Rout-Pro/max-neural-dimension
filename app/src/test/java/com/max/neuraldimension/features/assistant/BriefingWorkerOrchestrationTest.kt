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
import com.max.neuraldimension.data.db.TaskItem
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.TimeZone

/**
 * Orchestration tests: run the REAL BriefingWorker.doWork against a synchronous
 * test WorkManager, real Room and real DataStore. Assertions are behavioral
 * (posted notifications, pending-work presence/identity, settings generation),
 * not re-implemented logic. Clock-target correctness of the delays themselves is
 * covered by QuietHoursTest (policy) and BriefingSchedulerRequestTest
 * (construction); here we prove the worker wires them together.
 *
 * No production seam: each test fully overwrites settings and wipes Room tables.
 */
@RunWith(RobolectricTestRunner::class)
class BriefingWorkerOrchestrationTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository
    private lateinit var wm: WorkManager
    private lateinit var db: AppDb
    private lateinit var defaultZone: TimeZone

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        defaultZone = TimeZone.getDefault()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        wm = WorkManager.getInstance(context)
        db = AppDb.get(context)
        runBlocking { db.clearAllTables() }
        repo = SettingsRepository(context)
        // Give getLaunchIntentForPackage a target so PendingIntent construction runs the production path.
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

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultZone)
    }

    private fun setSettings(s: MaxSettings) = runBlocking { repo.update { s } }

    private fun runWorker(generation: Int): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<BriefingWorker>(context)
            .setInputData(Data.Builder().putInt(BriefingScheduler.KEY_GENERATION, generation).build())
            .build().doWork()
    }

    private fun pendingBriefing(): List<WorkInfo> =
        wm.getWorkInfosForUniqueWork(BriefingScheduler.UNIQUE_WORK).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }

    private fun notifications(): List<Notification> =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications

    private fun firePending(wi: WorkInfo) {
        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(wi.id)
    }

    /** Quiet window covering "now": now-30min .. now+30min local (wrap-safe by construction). */
    private fun quietSettingsAroundNow(): MaxSettings {
        val z = java.time.ZonedDateTime.now()
        val start = z.minusMinutes(30)
        val end = z.plusMinutes(30)
        return MaxSettings(
            briefingEnabled = true,
            quietHoursEnabled = true,
            quietStartMin = start.hour * 60 + start.minute,
            quietEndMin = end.hour * 60 + end.minute
        )
    }

    @Test
    fun disabled_no_post_no_rearm() {
        setSettings(MaxSettings(briefingEnabled = false, briefingGeneration = 0))
        val result = runWorker(0)
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, notifications().size)
        assertEquals(0, pendingBriefing().size)
    }

    @Test
    fun stale_generation_no_post_no_rearm() {
        setSettings(MaxSettings(briefingEnabled = true, briefingGeneration = 5))
        val result = runWorker(4)
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, notifications().size)
        assertEquals(0, pendingBriefing().size)
    }

    @Test
    fun quiet_window_defers_without_posting_and_redefer_stays_quiet() {
        setSettings(quietSettingsAroundNow())
        val result = runWorker(0)
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, notifications().size)
        val pending = pendingBriefing()
        assertEquals(1, pending.size)
        // Firing the deferred link while the window is still active re-defers: still no post.
        firePending(pending[0])
        assertEquals(0, notifications().size)
        assertEquals(1, pendingBriefing().size)
    }

    @Test
    fun normal_fire_posts_briefing_from_room_data_and_chains_live_link() {
        setSettings(MaxSettings(briefingEnabled = true, briefingGeneration = 0))
        runBlocking {
            db.taskDao().insert(TaskItem(title = "a"))
            db.taskDao().insert(TaskItem(title = "b"))
            db.reminderDao().insert(Reminder(title = "call mom", triggerAt = System.currentTimeMillis() + 45 * 60_000))
        }
        val result = runWorker(0)
        assertTrue(result is ListenableWorker.Result.Success)
        val posted = notifications()
        assertEquals(1, posted.size)
        assertEquals("MAX daily briefing", posted[0].extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val text = posted[0].extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("unexpected briefing text: $text", text.startsWith("2 open tasks - next reminder: call mom in "))
        // The chained link carries the same live generation: firing it posts again.
        val pending = pendingBriefing()
        assertEquals(1, pending.size)
        firePending(pending[0])
        assertEquals(2, notifications().size)
    }

    @Test
    fun keep_invariant_stale_chain_link_cannot_displace_fresher_schedule() {
        setSettings(MaxSettings(briefingEnabled = true, briefingGeneration = 0))
        runBlocking { BriefingScheduler.reconcile(context) } // generation 1, pending work gen=1
        // A late stale worker tries to chain with the old generation via KEEP:
        BriefingScheduler.chain(context, 0, 60_000)
        val pending = pendingBriefing()
        assertEquals(1, pending.size)
        // If KEEP held, the pending work is the gen=1 schedule: firing it posts.
        // The stale gen=0 link would have no-oped (gen mismatch) instead.
        firePending(pending[0])
        assertEquals(1, notifications().size)
    }

    @Test
    fun toggle_off_reconcile_cancels_pending_and_bumps_generation() {
        setSettings(MaxSettings(briefingEnabled = true, briefingGeneration = 0))
        runBlocking { BriefingScheduler.reconcile(context) }
        assertEquals(1, pendingBriefing().size)
        setSettings(MaxSettings(briefingEnabled = false, briefingGeneration = 1))
        runBlocking { BriefingScheduler.reconcile(context) }
        assertEquals(0, pendingBriefing().size)
        assertEquals(2, runBlocking { repo.settings.first() }.briefingGeneration)
    }

    @Test
    fun enable_reconcile_enqueues_current_generation_work_that_posts() {
        setSettings(MaxSettings(briefingEnabled = true, briefingGeneration = 0))
        runBlocking { BriefingScheduler.reconcile(context) }
        val pending = pendingBriefing()
        assertEquals(1, pending.size)
        firePending(pending[0])
        assertEquals(1, notifications().size)
    }

    @Test
    fun timezone_change_reconcile_replaces_schedule_single_pending_and_valid() {
        setSettings(MaxSettings(briefingEnabled = true, briefingMinOfDay = 8 * 60, briefingGeneration = 0))
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"))
        runBlocking { BriefingScheduler.reconcile(context) }
        assertEquals(1, pendingBriefing().size)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        runBlocking { BriefingScheduler.reconcile(context) }
        val pending = pendingBriefing()
        assertEquals(1, pending.size) // REPLACE: old-zone schedule displaced
        assertEquals(2, runBlocking { repo.settings.first() }.briefingGeneration)
        firePending(pending[0])
        assertEquals(1, notifications().size) // pending work carries the current generation
    }
}
