package com.max.neuraldimension.features.hologram

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Task
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.max.neuraldimension.core.theme.NdBlack
import com.max.neuraldimension.core.theme.NdOk
import com.max.neuraldimension.core.theme.NdTheme
import com.max.neuraldimension.testutil.PixelCheck
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Blueprint Part 24, floating panels: the production HoloPanel composables
 * rendered on the JVM (Robolectric + Roborazzi) inside the app theme, fed the
 * same kind of content the Home dashboard feeds them. This is an ISOLATED
 * production section fed test state, not the full Home screen (which hosts
 * the GL robot view and stays device-bound). JVM-rendered pixels, NOT device
 * or runtime evidence; the captured PNG is the decisive evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoloPanelPixelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun holo_panels_render_with_content_and_click_actions() {
        composeRule.setContent {
            NdTheme {
                Column(
                    Modifier.fillMaxSize().background(NdBlack).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HoloPanel("AI STATUS", Icons.Default.Psychology, 0, {}, Modifier.weight(1f).fillMaxHeight()) {
                            PanelLine("Backend", "Linked", NdOk)
                            PanelLine("Model", "via backend")
                        }
                        HoloPanel("TASKS", Icons.Default.Task, 1, {}, Modifier.weight(1f).fillMaxHeight()) {
                            PanelLine("Open", "3")
                            PanelLine("Done", "12")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val path = "build/outputs/roborazzi/holo_panels.png"
        composeRule.onRoot().captureRoboImage(path)
        PixelCheck.assertNonBlankPng(path)

        composeRule.onNodeWithText("AI STATUS").assertExists()
        composeRule.onNodeWithText("TASKS").assertExists()
        composeRule.onNodeWithText("Linked").assertExists()
        // Panel interactions: each panel exposes a click action (expand target).
        // The clickable container lives in the unmerged semantics tree.
        composeRule.onNode(hasClickAction() and hasAnyChild(hasText("AI STATUS")), useUnmergedTree = true).assertExists()
        composeRule.onNode(hasClickAction() and hasAnyChild(hasText("TASKS")), useUnmergedTree = true).assertExists()
    }
}
