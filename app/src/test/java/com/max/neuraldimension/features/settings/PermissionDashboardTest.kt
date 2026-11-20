package com.max.neuraldimension.features.settings

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows

/**
 * Device-free (Robolectric) evidence for the Part 19 permission dashboard:
 * the status mapping reflects real grant/deny state. Granting UX itself is
 * the system's and is not simulated here; real-device prompt behavior is
 * unproved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionDashboardTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun denied_by_default_then_granted_after_grant() {
        val shadow = Shadows.shadowOf(app)
        shadow.denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)

        val denied = PermissionDashboard.entries(app)
        assertEquals(3, denied.size)
        assertTrue(denied.none { it.granted })

        shadow.grantPermissions(Manifest.permission.RECORD_AUDIO)
        val after = PermissionDashboard.entries(app)
        assertTrue(after.first { it.permission == Manifest.permission.RECORD_AUDIO }.granted)
        assertFalse(after.first { it.permission == Manifest.permission.CAMERA }.granted)
        assertFalse(after.first { it.permission == Manifest.permission.POST_NOTIFICATIONS }.granted)
    }

    @Test
    fun covers_exactly_the_declared_dangerous_permissions() {
        val perms = PermissionDashboard.entries(app).map { it.permission }
        assertEquals(
            listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS),
            perms
        )
    }
}
