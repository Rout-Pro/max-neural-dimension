package com.max.neuraldimension.features.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
 * Blueprint Part 24 groundwork, different screen sizes: the production
 * SettingsScreen (the app's most complex layout) rendered on the JVM at a
 * small phone (360dp) and a tablet (600dp) qualifier, one capture each.
 * JVM-rendered pixels, NOT device or runtime evidence; the captured PNGs are
 * the decisive evidence, supplemented by existence and non-blank assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenSizePixelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun vm(): AppViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runBlocking(Dispatchers.IO) {
            AppDb.get(app).clearAllTables()
            SettingsRepository(app).update { MaxSettings() }
        }
        return AppViewModel(app)
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.SmallPhone)
    fun settings_on_small_phone() {
        val vm = vm()
        composeRule.setContent { SettingsScreen(vm) }
        composeRule.waitForIdle()
        val path = "build/outputs/roborazzi/settings_phone_small.png"
        composeRule.onRoot().captureRoboImage(path)
        PixelCheck.assertNonBlankPng(path)
        composeRule.onNodeWithText("SETTINGS").assertExists()
        composeRule.onNodeWithText("Profile").assertExists()
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Nexus7)
    fun settings_on_tablet() {
        val vm = vm()
        composeRule.setContent { SettingsScreen(vm) }
        composeRule.waitForIdle()
        val path = "build/outputs/roborazzi/settings_tablet.png"
        composeRule.onRoot().captureRoboImage(path)
        PixelCheck.assertNonBlankPng(path)
        composeRule.onNodeWithText("SETTINGS").assertExists()
        composeRule.onNodeWithText("Profile").assertExists()
    }
}
