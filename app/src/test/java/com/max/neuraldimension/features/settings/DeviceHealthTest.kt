package com.max.neuraldimension.features.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device-free (Robolectric) evidence for the Part 20 Device Health facts:
 * recursive byte accounting over app-private storage, backup-file counting,
 * and size formatting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceHealthTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun stats_count_bytes_and_backups() {
        val before = DeviceHealth.stats(app)
        val sub = File(app.filesDir, "healthtest").apply { mkdirs() }
        File(sub, "a.bin").writeBytes(ByteArray(100))
        File(sub, "b.bin").writeBytes(ByteArray(50))
        val backups = File(app.filesDir, "backups").apply { mkdirs() }
        File(backups, "max-backup-1.json").writeText("{}")
        File(backups, "max-backup-2.json").writeText("{}")
        File(backups, "not-a-backup.txt").writeText("x")

        val s = DeviceHealth.stats(app)
        assertEquals(before.appBytes + 100 + 50 + 2 + 2 + 1, s.appBytes)
        assertEquals(before.backupCount + 2, s.backupCount)

        sub.deleteRecursively()
        backups.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun empty_and_formatting() {
        assertEquals(0, DeviceHealth.dirBytes(File(app.filesDir, "does-not-exist")))
        assertEquals("512 B", DeviceHealth.formatBytes(512))
        assertEquals("1.5 KB", DeviceHealth.formatBytes(1536))
        assertEquals("2.0 MB", DeviceHealth.formatBytes(2L * 1024 * 1024))
        assertTrue(DeviceHealth.stats(app).appBytes >= 0)
    }
}
