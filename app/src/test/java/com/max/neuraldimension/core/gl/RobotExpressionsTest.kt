package com.max.neuraldimension.core.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free evidence for the Part 4/6 expression machine: every mode yields
 * valid expression targets, the emotional expressions are visibly distinct,
 * SURPRISE matches its spec, and the decay table preserves the timings that
 * previously lived as ad-hoc delays. Pure JVM - no GL, no Android.
 */
class RobotExpressionsTest {

    private val modes = RobotState.Mode.values()

    @Test
    fun every_mode_yields_valid_targets() {
        modes.forEach { m ->
            val ex = RobotExpressions.targets(m, t = 1.7f, amp = 0.6f, intensity = 1.0f, idleGaze = 0.005f)
            assertTrue("$m eyeScale ${ex.eyeScale}", ex.eyeScale in 0.8f..1.6f)
            assertTrue("$m tilt ${ex.tilt}", ex.tilt in -0.2f..0.2f)
            assertTrue("$m mouthFloor ${ex.mouthFloor}", ex.mouthFloor in 0f..0.8f)
            assertTrue("$m mouthWide ${ex.mouthWide}", ex.mouthWide in 0.8f..1.6f)
            assertTrue("$m smile ${ex.smile}", ex.smile in 0f..1f)
            assertTrue("$m blinkFloor ${ex.blinkFloor}", ex.blinkFloor in 0f..0.6f)
            assertTrue("$m ringSpeed ${ex.ringSpeed}", ex.ringSpeed in 0.1f..3.0f)
            assertTrue("$m eyeShift ${ex.eyeShift}", ex.eyeShift in -0.06f..0.06f)
            assertTrue("$m headRx ${ex.headRx}", ex.headRx in -0.2f..0.2f)
        }
    }

    @Test
    fun emotional_expressions_are_visibly_distinct() {
        val happy = RobotExpressions.targets(RobotState.Mode.HAPPY, 1f, 0f, 1f, 0f)
        val excited = RobotExpressions.targets(RobotState.Mode.EXCITED, 1f, 0f, 1f, 0f)
        val surprise = RobotExpressions.targets(RobotState.Mode.SURPRISE, 1f, 0f, 1f, 0f)
        val confused = RobotExpressions.targets(RobotState.Mode.CONFUSED, 1f, 0f, 1f, 0f)
        val curious = RobotExpressions.targets(RobotState.Mode.CURIOUS, 1f, 0f, 1f, 0f)

        // EXCITED is a bigger HAPPY: more smile and more mouth.
        assertTrue(excited.smile > happy.smile)
        assertTrue(excited.mouthFloor > happy.mouthFloor)
        // SURPRISE: widest eyes, no smile at all.
        assertTrue(surprise.eyeScale > excited.eyeScale)
        assertEquals(0f, surprise.smile, 0.0001f)
        assertNotEquals(happy.smile, surprise.smile)
        // CONFUSED tilts the other way from every positive expression.
        assertTrue(confused.tilt < 0f)
        assertTrue(curious.tilt > 0f && happy.tilt > 0f)
        // LISTENING widens the eyes attentively.
        val listening = RobotExpressions.targets(RobotState.Mode.LISTENING, 1f, 0f, 1f, 0f)
        assertTrue(listening.eyeScale > 1.15f)
        assertTrue(listening.faceEmissive != null)
    }

    @Test
    fun surprise_matches_part4_spec() {
        val s = RobotExpressions.targets(RobotState.Mode.SURPRISE, 2f, 0f, 1f, 0f)
        assertTrue("eyes wide", s.eyeScale >= 1.3f)
        assertTrue("small open mouth", s.mouthFloor in 0.2f..0.5f && s.mouthWide > 1f)
        assertTrue("head raise", s.headRx < 0f)
        assertEquals("no smile", 0f, s.smile, 0.0001f)
    }

    @Test
    fun decay_table_preserves_existing_timings() {
        assertEquals(4000L, RobotExpressions.decayMillis(RobotState.Mode.CONFUSED))
        assertEquals(3500L, RobotExpressions.decayMillis(RobotState.Mode.ERROR))
        assertEquals(4000L, RobotExpressions.decayMillis(RobotState.Mode.HAPPY))
        assertEquals(4000L, RobotExpressions.decayMillis(RobotState.Mode.EXCITED))
        assertEquals(2500L, RobotExpressions.decayMillis(RobotState.Mode.SURPRISE))
        // Conversation states are event-driven and never decay.
        assertNull(RobotExpressions.decayMillis(RobotState.Mode.IDLE))
        assertNull(RobotExpressions.decayMillis(RobotState.Mode.LISTENING))
        assertNull(RobotExpressions.decayMillis(RobotState.Mode.THINKING))
        assertNull(RobotExpressions.decayMillis(RobotState.Mode.SPEAKING))
        assertNull(RobotExpressions.decayMillis(RobotState.Mode.CURIOUS))
    }

    @Test
    fun effective_mode_decays_only_after_the_window() {
        val since = 10_000L
        assertEquals(
            RobotState.Mode.HAPPY,
            RobotExpressions.effectiveMode(RobotState.Mode.HAPPY, since, since + 3999)
        )
        assertEquals(
            RobotState.Mode.IDLE,
            RobotExpressions.effectiveMode(RobotState.Mode.HAPPY, since, since + 4000)
        )
        // A newer mode set during the window is untouched (decay is per-mode-since).
        assertEquals(
            RobotState.Mode.THINKING,
            RobotExpressions.effectiveMode(RobotState.Mode.THINKING, since, since + 999_999)
        )
        assertEquals(
            RobotState.Mode.SURPRISE,
            RobotExpressions.effectiveMode(RobotState.Mode.SURPRISE, since, since + 2499)
        )
        assertEquals(
            RobotState.Mode.IDLE,
            RobotExpressions.effectiveMode(RobotState.Mode.SURPRISE, since, since + 2500)
        )
    }
}
