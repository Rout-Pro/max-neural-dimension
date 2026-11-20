package com.max.neuraldimension.features.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import com.max.neuraldimension.features.chat.AppViewModel
import com.max.neuraldimension.testutil.PixelCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.robolectric.shadows.ShadowLooper

/**
 * Blueprint Part 17/18 evidence: the production SettingsScreen rendered on the
 * JVM (Robolectric + Roborazzi) with the real AppViewModel and DataStore state.
 * These are JVM-rendered pixels, NOT device or runtime evidence. The captured
 * PNG (archived as a CI artifact) is the decisive evidence; semantic-tree
 * existence assertions and the non-blank metric only supplement it.
 * assertIsDisplayed is deliberately NOT used: Robolectric's window model makes
 * that predicate unreliable on a headless runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenPixelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun seed(transform: (MaxSettings) -> MaxSettings) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runBlocking(Dispatchers.IO) { SettingsRepository(app).update(transform) }
    }

    private fun awaitVm(cond: (AppViewModel) -> Boolean): AppViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app)
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond(vm) && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
        assertTrue("timed out waiting for ViewModel state", cond(vm))
        return vm
    }

    private fun captureSettings(pngName: String) {
        val path = "build/outputs/roborazzi/$pngName"
        composeRule.onRoot().captureRoboImage(path)
        PixelCheck.assertNonBlankPng(path)
    }

    @Test
    fun settings_quiet_off_briefing_off() {
        seed { it.copy(quietHoursEnabled = false, briefingEnabled = false) }
        val vm = awaitVm { !it.settings.value.quietHoursEnabled && !it.settings.value.briefingEnabled }

        composeRule.setContent { SettingsScreen(vm) }
        composeRule.onNodeWithText("Quiet hours and daily briefing").performScrollTo()
        composeRule.waitForIdle()

        captureSettings("settings_quiet_off.png")

        composeRule.onNodeWithText("Quiet hours").assertExists()
        composeRule.onNodeWithText("Daily briefing").assertExists()
        composeRule.onNodeWithText("From 22:00 to 07:00").assertDoesNotExist()
        composeRule.onNodeWithText("Around 08:00 each day").assertDoesNotExist()
    }

    @Test
    fun settings_quiet_on_shows_window() {
        seed { it.copy(quietHoursEnabled = true, quietStartMin = 22 * 60, quietEndMin = 7 * 60) }
        val vm = awaitVm { it.settings.value.quietHoursEnabled }

        composeRule.setContent { SettingsScreen(vm) }
        composeRule.onNodeWithText("Quiet hours and daily briefing").performScrollTo()
        composeRule.waitForIdle()

        captureSettings("settings_quiet_on.png")

        composeRule.onNodeWithText("Quiet hours").assertExists()
        composeRule.onNodeWithText("From 22:00 to 07:00").assertExists()
    }

    @Test
    fun settings_briefing_on_shows_time() {
        seed { it.copy(quietHoursEnabled = false, briefingEnabled = true, briefingMinOfDay = 8 * 60) }
        val vm = awaitVm { !it.settings.value.quietHoursEnabled && it.settings.value.briefingEnabled }

        composeRule.setContent { SettingsScreen(vm) }
        composeRule.onNodeWithText("Quiet hours and daily briefing").performScrollTo()
        composeRule.waitForIdle()

        captureSettings("settings_briefing_on.png")

        composeRule.onNodeWithText("Daily briefing").assertExists()
        composeRule.onNodeWithText("Around 08:00 each day").assertExists()
        composeRule.onNodeWithText("From 22:00 to 07:00").assertDoesNotExist()
    }
}
