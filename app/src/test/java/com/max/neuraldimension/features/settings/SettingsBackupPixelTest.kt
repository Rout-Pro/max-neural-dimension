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
import com.max.neuraldimension.data.db.Reminder
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
 * Blueprint Part 19 evidence: the production SettingsScreen rendered on the
 * JVM (Robolectric + Roborazzi) with the real AppViewModel and a real backup
 * file in app storage, scrolled to the Backup section. These are JVM-rendered
 * pixels, NOT device or runtime evidence. The captured PNG (archived as a CI
 * artifact) is the decisive evidence; semantic existence assertions and the
 * non-blank metric only supplement it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBackupPixelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settings_backup_section_with_exported_file() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = AppDb.get(app)
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
            SettingsRepository(app).update {
                MaxSettings(userName = "Rout", memories = setOf("likes tea"))
            }
            db.reminderDao().insert(Reminder(title = "call mom", triggerAt = 1700003600000))
        }
        val vm = AppViewModel(app)
        // Produce a real backup file so the Restore row and filename render.
        val f = runBlocking(Dispatchers.IO) { vm.exportBackup() }

        composeRule.setContent { SettingsScreen(vm) }
        // Scroll the LAST row of the Backup section into view (header-only
        // scrolls leave content below the fold).
        composeRule.onNodeWithText("Latest backup:", substring = true).performScrollTo()
        composeRule.waitForIdle()

        val path = "build/outputs/roborazzi/settings_backup.png"
        composeRule.onRoot().captureRoboImage(path)
        PixelCheck.assertNonBlankPng(path)

        composeRule.onNodeWithText("Export backup").assertExists()
        composeRule.onNodeWithText("Latest backup: ${f.name}").assertExists()
        composeRule.onNodeWithText("Restore").assertExists()
    }
}
