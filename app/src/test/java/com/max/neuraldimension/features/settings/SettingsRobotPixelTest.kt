package com.max.neuraldimension.features.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import com.max.neuraldimension.features.chat.AppViewModel
import com.max.neuraldimension.testutil.PixelCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Blueprint Part 20 evidence: the production SettingsScreen rendered on the
 * JVM (Robolectric + Roborazzi) with the real AppViewModel, capturing (1) the
 * Robot section with the new mouth-animation / holographic-panels / accent
 * controls and (2) the Device Health section with real storage stats. These
 * are JVM-rendered pixels, NOT device or runtime evidence. The captured PNGs
 * (archived as CI artifacts) are the decisive evidence; semantic existence
 * assertions and the non-blank metric only supplement them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRobotPixelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settings_robot_section_and_device_health() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runBlocking(Dispatchers.IO) {
            AppDb.get(app).clearAllTables()
            SettingsRepository(app).update {
                MaxSettings(mouthAnimation = false, holoPanels = true, robotAccent = "cyan")
            }
        }
        val vm = AppViewModel(app)
        // One real backup file so Device Health shows a nonzero backup count.
        runBlocking(Dispatchers.IO) { vm.exportBackup() }

        composeRule.setContent { SettingsScreen(vm) }
        // Scroll the LAST row of the Robot section into view.
        composeRule.onNodeWithText("Accent").performScrollTo()
        composeRule.waitForIdle()

        val robotPath = "build/outputs/roborazzi/settings_robot.png"
        composeRule.onRoot().captureRoboImage(robotPath)
        PixelCheck.assertNonBlankPng(robotPath)

        composeRule.onNodeWithText("Mouth animation").assertExists()
        composeRule.onNodeWithText("Holographic panels").assertExists()
        composeRule.onNodeWithText("Accent").assertExists()
        composeRule.onNodeWithText("Cyan").assertExists()

        // Scroll the Device Health section into view for the second capture.
        composeRule.onNodeWithText("App data").performScrollTo()
        composeRule.waitForIdle()

        val healthPath = "build/outputs/roborazzi/settings_device_health.png"
        composeRule.onRoot().captureRoboImage(healthPath)
        PixelCheck.assertNonBlankPng(healthPath)

        composeRule.onNodeWithText("Device Health").assertExists()
        composeRule.onNodeWithText("App data").assertExists()
        composeRule.onNodeWithText("Backups").assertExists()
    }
}
