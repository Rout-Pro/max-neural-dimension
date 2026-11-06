package com.max.neuraldimension.features.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.max.neuraldimension.data.db.AppDb
import kotlinx.coroutines.flow.first

/**
 * Fires a scheduled reminder as a real system notification and marks it fired.
 * Scheduled via WorkManager (survives process death; exact timing is inexact
 * by design - WorkManager may defer under battery policies).
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val reminderId = inputData.getLong(KEY_ID, -1L)
        val title = inputData.getString(KEY_TITLE) ?: "Reminder"
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
