package com.max.neuraldimension.features.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Fires a scheduled reminder as a real system notification and marks it fired.
 * Scheduled via WorkManager (survives process death; exact timing is inexact
 * by design - WorkManager may defer under battery policies).
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val reminderId = inputData.getLong(KEY_ID, -1L)
        val title = inputData.getString(KEY_TITLE) ?: "Reminder"

        // Blueprint Part 17: with opt-in quiet hours enabled, a fire inside the
        // window is deferred to the end of the window (never dropped). The DB row
        // tracks the replacement work id (Cancel still works) and the deferred fire
        // instant in deferredUntil; triggerAt keeps the user-requested time.
        // Deferral is one-way: turning quiet hours off afterwards does not move a
        // deferred reminder back (its requested time has already passed by then).
        val settings = SettingsRepository(applicationContext).settings.first()
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        if (QuietHours.isQuiet(now, settings.quietHoursEnabled, settings.quietStartMin, settings.quietEndMin, zone)) {
            val next = QuietHours.nextAllowedFireMillis(
                now, settings.quietHoursEnabled, settings.quietStartMin, settings.quietEndMin, zone
            )
            val work = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(next - now, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .addTag("nd-reminder")
                .build()
            WorkManager.getInstance(applicationContext).enqueue(work)
            if (reminderId > 0) {
                val db = AppDb.get(applicationContext)
                db.reminderDao().reminders().first().firstOrNull { it.id == reminderId }
                    ?.let { db.reminderDao().update(it.copy(deferredUntil = next, workId = work.id.toString())) }
            }
            return Result.success()
        }

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val pi = PendingIntent.getActivity(
            applicationContext, reminderId.toInt(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("MAX Reminder")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            nm.notify(NOTIF_BASE + reminderId.toInt(), notif)
        } catch (se: SecurityException) {
            return Result.failure() // POST_NOTIFICATIONS not granted
        }
        if (reminderId > 0) {
            val db = AppDb.get(applicationContext)
            // mark fired so the list reflects it
            db.reminderDao().reminders().first().firstOrNull { it.id == reminderId }
                ?.let { db.reminderDao().update(it.copy(fired = true)) }
        }
        return Result.success()
    }

    companion object {
        const val KEY_ID = "reminder_id"
        const val KEY_TITLE = "reminder_title"
        const val CHANNEL_ID = "nd_reminders"
        const val NOTIF_BASE = 42000
    }
}
