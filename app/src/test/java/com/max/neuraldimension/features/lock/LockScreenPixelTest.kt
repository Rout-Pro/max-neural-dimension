package com.max.neuraldimension.features.lock

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.github.takahirom.roborazzi.captureRoboImage
import com.max.neuraldimension.testutil.PixelCheck
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers

/**
 * Blueprint Part 19 evidence: the production LockScreen composable rendered on
 * the JVM (Robolectric + Roborazzi), fed deterministic test state (3 digits
 * entered, error visible). This is an ISOLATED production screen under test
 * state - NOT a full app-gate flow and NOT device or runtime evidence. The
 * captured PNG (archived as a CI artifact) is the decisive evidence;
 * semantic-tree existence assertions and the non-blank metric only supplement
 * it. assertIsDisplayed is deliberately NOT used: Robolectric's window model
 * makes that predicate unreliable on a headless runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockScreenPixelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lockscreen_locked_state_with_error() {
        composeRule.setContent {
            LockScreen(
                entered = 3,
                error = "Wrong PIN",
                onDigit = {},
                onDelete = {},
                onSubmit = {}
            )
        }
        composeRule.waitForIdle()

        val path = "build/outputs/roborazzi/lockscreen_locked.png"
        composeRule.onRoot().captureRoboImage(path)
        PixelCheck.assertNonBlankPng(path)

        composeRule.onNodeWithText("MAX LOCKED").assertExists()
        composeRule.onNodeWithText("Enter your PIN").assertExists()
        composeRule.onNodeWithText("Wrong PIN").assertExists()
        // Full numeric pad rendered: 1-9 plus 0.
        composeRule.onAllNodesWithText("0").assertCountEquals(1)
        composeRule.onNodeWithText("9").assertExists()
        composeRule.onNodeWithText("OK").assertExists()
        composeRule.onNodeWithText("DEL").assertExists()
    }
}
