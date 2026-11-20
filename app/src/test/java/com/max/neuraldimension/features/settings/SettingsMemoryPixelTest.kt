package com.max.neuraldimension.features.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * Blueprint Part 13 evidence: the production SettingsScreen Memory section
 * rendered on the JVM (Robolectric + Roborazzi) with the real AppViewModel and
 * seeded facts, showing the per-fact Edit and Delete controls. These are
 * JVM-rendered pixels, NOT device or runtime evidence. The captured PNG
 * (archived as a CI artifact) is the decisive evidence; semantic existence
 * assertions and the non-blank metric only supplement it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsMemoryPixelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settings_memory_section_with_edit_and_delete() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runBlocking(Dispatchers.IO) {
            AppDb.get(app).clearAllTables()
            SettingsRepository(app).update {
                MaxSettings(memories = setOf("likes tea", "birthday Oct 17"))
            }
        }
        val vm = AppViewModel(app)

        composeRule.setContent { SettingsScreen(vm) }
        // Scroll the LAST row of the Memory section into view (the Add field).
        composeRule.onNodeWithText("Memories are stored only on this device", substring = true).performScrollTo()
        composeRule.waitForIdle()

        val path = "build/outputs/roborazzi/settings_memory.png"
        composeRule.onRoot().captureRoboImage(path)
        PixelCheck.assertNonBlankPng(path)

        composeRule.onNodeWithText("likes tea").assertExists()
        composeRule.onNodeWithText("birthday Oct 17").assertExists()
        // One Edit and one Delete per saved fact.
        org.junit.Assert.assertEquals(2, composeRule.onAllNodesWithText("Edit").fetchSemanticsNodes().size)
        org.junit.Assert.assertEquals(2, composeRule.onAllNodesWithText("Delete").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Add").assertExists()
    }
}
