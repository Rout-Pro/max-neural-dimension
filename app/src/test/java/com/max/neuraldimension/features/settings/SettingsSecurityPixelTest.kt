package com.max.neuraldimension.features.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.db.SecurityEvent
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import com.max.neuraldimension.domain.PinLock
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
 * Blueprint Part 19 evidence: the production SettingsScreen rendered on the
 * JVM (Robolectric + Roborazzi) with the real AppViewModel and seeded PIN +
 * security events, scrolled to the Security section. These are JVM-rendered
 * pixels, NOT device or runtime evidence. The captured PNG (archived as a CI
 * artifact) is the decisive evidence; semantic existence assertions and the
 * non-blank metric only supplement it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSecurityPixelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settings_security_section_with_pin_and_events() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDb.get(app)
        runBlocking(Dispatchers.IO) {
            SettingsRepository(app).update {
                MaxSettings(pinVerifier = PinLock.newVerifier("1234"))
            }
            db.securityDao().clear()
            db.securityDao().insert(SecurityEvent(type = "pin_set", ts = 1700000000000))
            db.securityDao().insert(SecurityEvent(type = "pin_failed", ts = 1700003600000))
        }
        val vm = AppViewModel(app)
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.securityEvents.value.size < 2 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
        assertTrue("timed out waiting for security events", vm.securityEvents.value.size >= 2)

        composeRule.setContent { SettingsScreen(vm) }
        // Scroll the LAST row of the Security section into view (header-only
        // scrolls leave content below the fold).
        composeRule.onNodeWithText("Erase all data").performScrollTo()
        composeRule.waitForIdle()

        val path = "build/outputs/roborazzi/settings_security.png"
        composeRule.onRoot().captureRoboImage(path)
        PixelCheck.assertNonBlankPng(path)

        composeRule.onNodeWithText("App PIN").assertExists()
        composeRule.onNodeWithText("On").assertExists()
        composeRule.onNodeWithText("Security event history").assertExists()
        composeRule.onNodeWithText("Failed unlock attempt").assertExists()
        composeRule.onNodeWithText("Erase all data").assertExists()
    }
}
