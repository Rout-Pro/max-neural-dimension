package com.max.neuraldimension.features.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Blueprint Part 19: permission dashboard. Read-only status of the dangerous
 * permissions the app declares; granting always happens through the system's
 * own prompts or system settings, never silently.
 */
object PermissionDashboard {

    data class Entry(val label: String, val permission: String, val granted: Boolean)

    private val DANGEROUS = listOf(
        "Microphone" to Manifest.permission.RECORD_AUDIO,
        "Camera" to Manifest.permission.CAMERA,
        "Notifications" to Manifest.permission.POST_NOTIFICATIONS
    )

    fun entries(context: Context): List<Entry> = DANGEROUS.map { (label, perm) ->
        Entry(label, perm, ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED)
    }
}
