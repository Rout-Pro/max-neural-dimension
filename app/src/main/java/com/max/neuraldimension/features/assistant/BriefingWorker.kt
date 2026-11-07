package com.max.neuraldimension.features.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.db.Reminder
import com.max.neuraldimension.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Effective fire instant: the deferred time when quiet hours moved it, else the user-requested time. */
internal fun Reminder.effectiveAt(): Long = if (deferredUntil > 0) deferredUntil else triggerAt

/**
 * Blueprint Part 17: builds the daily-briefing text from on-device data only.
 * Weather is session-only state in the chat view model, so it is not included here.
 * [compose] is pure and unit-tested; [build] is the thin Room-reading wrapper.
 */
object BriefingBuilder {

    fun compose(
        openTasks: Int,
        nextReminderTitle: String?,
        nextReminderInMin: Long?,
        habitsDone: Int,
        habitsTotal: Int
    ): String {
        val parts = mutableListOf<String>()
        parts.add("$openTasks open task" + (if (openTasks == 1) "" else "s"))
        if (nextReminderTitle != null && nextReminderInMin != null) {
            parts.add("next reminder: $nextReminderTitle in ${nextReminderInMin.coerceAtLeast(0)}m")
        }
        if (habitsTotal > 0) parts.add("habits $habitsDone/$habitsTotal done today")
        return parts.joinToString(" - ")
    }

    suspend fun build(db: AppDb, nowMillis: Long): String {
        val openTasks = db.taskDao().tasks().first().count { !it.done }
        val next = db.reminderDao().reminders().first()
            .filter { !it.fired && it.effectiveAt() > nowMillis }
            .minByOrNull { it.effectiveAt() }
        val habits = db.habitDao().habits().first()
        val today = java.time.LocalDate.now().toEpochDay()
        val done = habits.count { it.lastDoneDay == today }
        return compose(
            openTasks,
            next?.title,
            next?.let { (it.effectiveAt() - nowMillis) / 60000 },
            done,
            habits.size
        )
    }
}

/**
 * Single serialized entry point for every briefing schedule change (settings
 * toggle, time change, timezone/time-set broadcast, app cold start).
 *
 * Race design: every reconcile bumps [com.max.neuraldimension.data.prefs.MaxSettings.briefingGeneration]
 * inside the atomic DataStore update, then REPLACEs (or cancels) the unique work.
 * Each enqueued request carries its generation; [BriefingWorker] no-ops when its
 * generation no longer matches the stored one, so work enqueued before the user's
 * latest choice can never post or re-arm over it. Chain links use KEEP (never
 * REPLACE), so a late-finishing stale worker cannot kill a fresher pending
 * schedule; the worst residue is one pending link that no-ops at fire time.
 */
object BriefingScheduler {
    const val UNIQUE_WORK = "nd-briefing"
    const val KEY_GENERATION = "briefing_generation"

    suspend fun reconcile(context: Context) {
        val app = context.applicationContext
        val repo = SettingsRepository(app)
        repo.update { it.copy(briefingGeneration = it.briefingGeneration + 1) }
        val s = repo.settings.first()
        if (!s.briefingEnabled) {
            WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val now = System.currentTimeMillis()
        val delay = QuietHours.nextOccurrenceMillis(now, s.briefingMinOfDay, ZoneId.systemDefault()) - now
        WorkManager.getInstance(app).enqueueUniqueWork(
            UNIQUE_WORK, ExistingWorkPolicy.REPLACE, buildRequest(s.briefingGeneration, delay)
        )
    }

    fun buildRequest(generation: Int, delayMillis: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BriefingWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putInt(KEY_GENERATION, generation).build())
            .addTag(UNIQUE_WORK)
            .build()

    /** Chain continuation. KEEP: a fresher pending schedule always wins. */
    fun chain(context: Context, generation: Int, delayMillis: Long) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK, ExistingWorkPolicy.KEEP, buildRequest(generation, delayMillis)
        )
    }
}

/**
 * Blueprint Part 17: scheduled daily briefing as a system notification.
 *
 * Delivery timing is inexact by design: WorkManager may defer under battery
 * policies, so the user-facing copy says "around" the chosen time. Scheduling is
 * a self-rearming one-shot chain whose delay is recomputed from the clock on
 * every run (see [QuietHours.nextOccurrenceMillis]) - no cumulative drift, and
 * timezone changes are repaired by TimeChangeReceiver / app-start reconcile.
 * A fire inside quiet hours defers to the end of the window instead of posting.
 */
class BriefingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).settings.first()
        val generation = inputData.getInt(BriefingScheduler.KEY_GENERATION, -1)
        if (!settings.briefingEnabled) return Result.success() // turned off since scheduling; do not re-arm
        if (generation != settings.briefingGeneration) return Result.success() // superseded; fresher schedule owns the future
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        if (QuietHours.isQuiet(now, settings.quietHoursEnabled, settings.quietStartMin, settings.quietEndMin, zone)) {
            // Defer this briefing to the end of the quiet window; the chain then
            // resumes normal daily occurrences from the next run.
            val next = QuietHours.nextAllowedFireMillis(
                now, settings.quietHoursEnabled, settings.quietStartMin, settings.quietEndMin, zone
            )
            BriefingScheduler.chain(applicationContext, generation, next - System.currentTimeMillis())
            return Result.success()
        }
        post(applicationContext, BriefingBuilder.build(AppDb.get(applicationContext), now))
        val next = QuietHours.nextOccurrenceMillis(System.currentTimeMillis(), settings.briefingMinOfDay, zone)
        BriefingScheduler.chain(applicationContext, generation, next - System.currentTimeMillis())
        return Result.success()
    }

    private fun post(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Daily briefing", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val pi = PendingIntent.getActivity(
            context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MAX daily briefing")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(NOTIF_ID, notif)
        } catch (se: SecurityException) {
            // POST_NOTIFICATIONS not granted; the chain still re-arms so the
            // briefing starts appearing if permission is granted later.
        }
    }

    companion object {
        const val CHANNEL_ID = "nd_briefing"
        private const val NOTIF_ID = 43001
    }
}
