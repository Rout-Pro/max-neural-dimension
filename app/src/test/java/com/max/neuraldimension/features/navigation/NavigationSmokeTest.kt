package com.max.neuraldimension.features.navigation

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.max.neuraldimension.data.db.AppDb
import com.max.neuraldimension.data.prefs.MaxSettings
import com.max.neuraldimension.data.prefs.SettingsRepository
import com.max.neuraldimension.features.chat.AppViewModel
import com.max.neuraldimension.features.chat.ChatScreen
import com.max.neuraldimension.features.settings.SettingsScreen
import com.max.neuraldimension.features.studio.StudioScreen
import com.max.neuraldimension.features.tools.ToolsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Blueprint Part 24 groundwork, navigation: every GL-free top-level screen
 * composes with the real AppViewModel on the JVM and renders its identifying
 * content. Home and Voice embed the GL robot view and stay device-bound
 * (carried limit); this covers Chat, Tools, Studio and Settings. JVM-rendered
 * semantics only - NOT device or runtime evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun freshVm(): AppViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runBlocking(Dispatchers.IO) {
            AppDb.get(app).clearAllTables()
            SettingsRepository(app).update { MaxSettings() }
        }
        return AppViewModel(app)
    }

    @Test
    fun chat_screen_renders() {
        val vm = freshVm()
        composeRule.setContent { ChatScreen(vm, onOpenSettings = {}) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start a conversation with", substring = true).assertExists()
    }

    @Test
    fun tools_screen_renders() {
        val vm = freshVm()
        composeRule.setContent { ToolsScreen(vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("TOOLS").assertExists()
    }

    @Test
    fun studio_screen_renders() {
        freshVm()
        composeRule.setContent { StudioScreen() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("WEB STUDIO").assertExists()
    }

    @Test
    fun settings_screen_renders() {
        val vm = freshVm()
        composeRule.setContent { SettingsScreen(vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SETTINGS").assertExists()
    }
}
