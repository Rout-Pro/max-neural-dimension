package com.max.neuraldimension.features.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Blueprint Part 17: builds the daily-briefing text from on-device data only.
 * Weather is session-only state in the chat view model, so it is not included here.
 */
object BriefingBuilder {
    suspend fun build(db: AppDb, nowMillis: Long): String {
        val openTasks = db.taskDao().tasks().first().count { !it.done }
        val next = db.reminderDao().reminders().first()
            .filter { !it.fired && it.triggerAt > nowMillis }
            .minByOrNull { it.triggerAt }
        val habits = db.habitDao().habits().first()
        val today = java.time.LocalDate.now().toEpochDay()
        val done = habits.count { it.lastDoneDay == today }
        val parts = mutableListOf<String>()
        parts.add("$openTasks open task" + (if (openTasks == 1) "" else "s"))
        if (next != null) parts.add(
            "next reminder: " + next.title + " in " +
                ((next.triggerAt - nowMillis) / 60000).coerceAtLeast(0) + "m"
        )
        if (habits.isNotEmpty()) parts.add("habits $done/${habits.size} done today")
        return parts.joinToString(" - ")
    }
}

/**
 * Blueprint Part 17: scheduled daily briefing as a system notification.
 *
 * Delivery timing is inexact by design: WorkManager may defer under battery
 * policies, so the user-facing copy says "around" the chosen time. Scheduling is
 * a self-rearming one-shot chain whose delay is recomputed from the clock on
 * every run (see [QuietHours.nextOccurrenceMillis]) - no cumulative drift, and
 * timezone/DST changes are picked up at each fire. A fire that lands inside
 * quiet hours is deferred to the end of the window instead of being dropped.
 */
class BriefingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).settings.first()
        if (!settings.briefingEnabled) return Result.success() // disabled since scheduling; do not re-arm
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val quiet = QuietHours.isQuiet(
            now, settings.quietHoursEnabled, settings.quietStartMin, settings.quietEndMin, zone
        )
        if (quiet) {
            // Defer this briefing to the end of the quiet window; the chain then
            // resumes normal daily occurrences from the next run.
            val next = QuietHours.nextAllowedFireMillis(
                now, settings.quietHoursEnabled, settings.quietStartMin, settings.quietEndMin, zone
            )
            scheduleAt(applicationContext, next - System.currentTimeMillis())
            return Result.success()
        }
        post(applicationContext, BriefingBuilder.build(AppDb.get(applicationContext), now))
        scheduleNext(applicationContext, settings.briefingMinOfDay)
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
        const val UNIQUE_WORK = "nd-briefing"
        const val CHANNEL_ID = "nd_briefing"
        private const val NOTIF_ID = 43001

        /** Schedule the next briefing at the next occurrence of [minOfDay] local time. */
        fun scheduleNext(context: Context, minOfDay: Int) {
            val now = System.currentTimeMillis()
            val next = QuietHours.nextOccurrenceMillis(now, minOfDay, ZoneId.systemDefault())
            scheduleAt(context, next - now)
        }

        internal fun scheduleAt(context: Context, delayMillis: Long) {
            val work = OneTimeWorkRequestBuilder<BriefingWorker>()
                .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .addTag(UNIQUE_WORK)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, work)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
