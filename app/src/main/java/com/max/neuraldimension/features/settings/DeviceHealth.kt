package com.max.neuraldimension.features.settings

import android.content.Context
import java.io.File

/**
 * Blueprint Part 20: on-device health facts for the Settings screen. Pure
 * filesystem accounting over app-private storage; no permissions needed.
 */
object DeviceHealth {

    data class Stats(
        /** Total bytes under the app's private files directory (recursive). */
        val appBytes: Long,
        /** Number of backup files under filesDir/backups. */
        val backupCount: Int
    )

    fun stats(context: Context): Stats {
        val files = context.filesDir
        return Stats(
            appBytes = dirBytes(files),
            backupCount = File(files, "backups").listFiles()
                ?.count { it.name.startsWith("max-backup-") && it.name.endsWith(".json") } ?: 0
        )
    }

    internal fun dirBytes(f: File): Long {
        if (!f.exists()) return 0
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirBytes(it) } ?: 0
    }

    /** Human-readable size, e.g. "512 B", "1.5 MB". */
    fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "%.1f MB".format(b / (1024.0 * 1024.0))
    }
}
