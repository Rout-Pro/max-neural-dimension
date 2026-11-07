package com.max.neuraldimension.features.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Blueprint Part 17 support: repairs the briefing schedule when the device
 * clock basis changes. A pending WorkManager delay is an absolute instant
 * computed under the old timezone, so after a timezone change it would fire at
 * the wrong local clock time; reconcile re-derives the delay in the current
 * zone (REPLACE) and bumps the schedule generation, which also retires any
 * chain link still carrying the old basis.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_TIME_SET &&
            action != Intent.ACTION_DATE_CHANGED
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BriefingScheduler.reconcile(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
