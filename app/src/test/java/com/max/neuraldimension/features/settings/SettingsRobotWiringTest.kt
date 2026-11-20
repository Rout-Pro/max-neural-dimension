package com.max.neuraldimension.features.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.max.neuraldimension.core.gl.RobotState
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import com.max.neuraldimension.features.chat.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Device-free (Robolectric) evidence for the Part 20 robot settings wiring:
 * the production AppViewModel applies mouthAnimation/robotAccent to RobotState
 * (which the GL renderer reads every frame), and fresh installs get the
 * defaults (on/on/violet). The renderer's pixel effect itself is GL-bound and
 * not proved here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRobotWiringTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun settings_drive_robot_state() {
        runBlocking(Dispatchers.IO) {
            SettingsRepository(app).update {
                MaxSettings(mouthAnimation = false, robotAccent = "cyan", holoPanels = false)
            }
        }
        AppViewModel(app)
        val deadline = System.currentTimeMillis() + 10_000
        while ((RobotState.mouthAnimation || RobotState.accent != "cyan") && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
        assertEquals(false, RobotState.mouthAnimation)
        assertEquals("cyan", RobotState.accent)
        val s = runBlocking(Dispatchers.IO) { SettingsRepository(app).settings.first() }
        assertEquals(false, s.mouthAnimation)
        assertEquals(false, s.holoPanels)
        assertEquals("cyan", s.robotAccent)
        // Restore static defaults for other test classes sharing this JVM.
        RobotState.mouthAnimation = true
        RobotState.accent = "violet"
    }

    @Test
    fun defaults_are_on_and_violet() {
        runBlocking(Dispatchers.IO) { SettingsRepository(app).update { MaxSettings() } }
        val s = runBlocking(Dispatchers.IO) { SettingsRepository(app).settings.first() }
        assertEquals(true, s.mouthAnimation)
        assertEquals(true, s.holoPanels)
        assertEquals("violet", s.robotAccent)
    }
}
