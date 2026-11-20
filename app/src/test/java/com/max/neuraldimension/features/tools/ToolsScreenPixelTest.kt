package com.max.neuraldimension.features.tools

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.db.Reminder
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
 * Blueprint Part 17 evidence: the production ToolsScreen reminder card rendered
 * on the JVM (Robolectric + Roborazzi) with the real AppViewModel and a real
 * Room row carrying a quiet-hours deferral (deferredUntil). JVM-rendered pixels,
 * NOT device or runtime evidence. The captured PNG (archived as a CI artifact)
 * is the decisive evidence; semantic existence assertions and the non-blank
 * metric only supplement it. assertIsDisplayed is deliberately NOT used:
 * Robolectric's window model makes that predicate unreliable on a headless runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsScreenPixelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reminders_list_shows_deferred_reminder() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDb.get(app)
        val now = System.currentTimeMillis()
        runBlocking(Dispatchers.IO) {
            db.reminderDao().insert(Reminder(title = "Pixel proof reminder", triggerAt = now + 30 * 60_000))
            db.reminderDao().insert(
                Reminder(
                    title = "Quiet deferred reminder",
                    triggerAt = now + 10 * 60_000,
                    deferredUntil = now + 120 * 60_000
                )
            )
        }

        val vm = AppViewModel(app)
        val deadline = System.currentTimeMillis() + 10_000
        fun seeded() = vm.reminders.value.any { it.title == "Quiet deferred reminder" }
        while (!seeded() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
        assertTrue("timed out waiting for seeded reminders", seeded())

        composeRule.setContent { ToolsScreen(vm) }
        composeRule.onNodeWithText("Reminders").performScrollTo()
        composeRule.waitForIdle()

        val path = "build/outputs/roborazzi/tools_reminders_deferred.png"
        composeRule.onRoot().captureRoboImage(path)
        PixelCheck.assertNonBlankPng(path)

        composeRule.onNodeWithText("Pixel proof reminder", substring = true).assertExists()
        composeRule.onNodeWithText("quiet hours: moved from", substring = true).assertExists()
    }
}
