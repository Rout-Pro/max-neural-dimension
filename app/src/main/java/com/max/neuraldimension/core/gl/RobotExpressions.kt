package com.max.neuraldimension.core.gl

import kotlin.math.sin

/**
 * Blueprint Parts 4/6: the expression machine, pure and JVM-testable. All
 * per-mode facial/animation target math lives here; the GL renderer consumes
 * these targets every frame and only smooths and draws them. Emotional states
 * carry a display decay so the robot settles back to IDLE unless refreshed;
 * conversation states are event-driven and never decay.
 */
object RobotExpressions {

    /** Per-frame expression targets for one mode. Defaults = neutral/IDLE. */
    data class Targets(
        val eyeScale: Float = 1f,
        val eyeShift: Float = 0f,
        val tilt: Float = 0f,
        /** Speech-safe mouthOpen floor (emotions blend a mouth shape over the amp). */
        val mouthFloor: Float = 0f,
        val mouthWide: Float = 1f,
        val smile: Float = 0f,
        val pulse: Float = 0f,
        /** Minimum blink morph (a partial close reads as a smile's eye squeeze). */
        val blinkFloor: Float = 0f,
        /** Ring rotation speed factor (default 0.5). */
        val ringSpeed: Float = 0.5f,
        /** Extra head pitch added on top of the idle sway. */
        val headRx: Float = 0f,
        /** Face-panel emissive override (LISTENING attention glow). */
        val faceEmissive: FloatArray? = null
    )

    /**
     * Display decay per mode in milliseconds; null = event-driven (the mode
     * ends when the app sets another one). Values preserve the timings that
     * previously lived as ad-hoc delays in AppViewModel.
     */
    fun decayMillis(mode: RobotState.Mode): Long? = when (mode) {
        RobotState.Mode.HAPPY -> 4000L
        RobotState.Mode.EXCITED -> 4000L
        RobotState.Mode.CONFUSED -> 4000L
        RobotState.Mode.ERROR -> 3500L
        RobotState.Mode.SURPRISE -> 2500L
        else -> null
    }

    /** The mode to display right now: the raw mode unless its decay window elapsed. */
    fun effectiveMode(mode: RobotState.Mode, modeSinceMs: Long, nowMs: Long): RobotState.Mode {
        val d = decayMillis(mode) ?: return mode
        return if (nowMs - modeSinceMs >= d) RobotState.Mode.IDLE else mode
    }

    /**
     * Expression targets for [mode] at animation time [t] (seconds), speech
     * amplitude [amp] (0..1), user [intensity] (0.2..1.6) and the renderer's
     * current IDLE saccade offset [idleGaze].
     */
    fun targets(mode: RobotState.Mode, t: Float, amp: Float, intensity: Float, idleGaze: Float): Targets =
        when (mode) {
            RobotState.Mode.LISTENING -> Targets(
                eyeScale = 1.18f,
                eyeShift = 0f,
                tilt = 0.05f,
                ringSpeed = 1.6f,
                pulse = 0.25f + 0.2f * sin(t * 4f),
                faceEmissive = floatArrayOf(0.16f, 0.07f, 0.32f)
            )
            RobotState.Mode.THINKING -> Targets(
                eyeShift = 0.035f * sin(t * 2.4f),
                tilt = 0.10f,
                ringSpeed = 2.6f,
                pulse = 0.3f + 0.25f * sin(t * 7f)
            )
            RobotState.Mode.SPEAKING -> Targets(
                headRx = 0.045f * sin(t * 6.5f) * amp,
                pulse = amp * 0.8f
            )
            RobotState.Mode.HAPPY -> Targets(
                blinkFloor = 0.38f,
                mouthFloor = 0.55f,
                mouthWide = 1.3f,
                tilt = 0.08f,
                smile = 0.85f
            )
            RobotState.Mode.EXCITED -> Targets(
                blinkFloor = 0.38f,
                mouthFloor = 0.65f,
                mouthWide = 1.35f,
                tilt = 0.08f,
                smile = 1f,
                eyeScale = 1.15f,
                headRx = 0.05f * sin(t * 8f) * intensity,
                pulse = 0.4f + 0.3f * sin(t * 9f)
            )
            RobotState.Mode.CURIOUS -> Targets(
                eyeScale = 1.12f,
                tilt = 0.13f,
                eyeShift = 0.02f * sin(t * 1.2f)
            )
            RobotState.Mode.CONFUSED -> Targets(
                tilt = -0.11f
            )
            RobotState.Mode.SURPRISE -> Targets(
                // Part 4: surprise - eyes wide, small open mouth, quick head raise,
                // no smile (distinct from HAPPY/EXCITED), fast light pulse.
                eyeScale = 1.35f,
                mouthFloor = 0.35f,
                mouthWide = 1.25f,
                tilt = 0f,
                smile = 0f,
                headRx = -0.05f,
                pulse = 0.2f + 0.2f * sin(t * 6f)
            )
            RobotState.Mode.ERROR -> Targets(
                pulse = 0.5f
            )
            RobotState.Mode.IDLE -> Targets(
                // Gaze saccades: slow drift biased back to center, toward the
                // person in front of the screen. The saccade schedule itself is
                // renderer state; the offset arrives as idleGaze.
                eyeShift = idleGaze
            )
        }
}
