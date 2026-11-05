package com.max.neuraldimension.core.gl

/**
 * Shared state between the app (voice engine, chat, UI) and the GL robot renderer.
 * The renderer reads these every frame; writers are on the main/voice threads.
 */
object RobotState {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, HAPPY, CURIOUS, CONFUSED, EXCITED, ERROR }

    @Volatile var mode: Mode = Mode.IDLE
    /** Live speech amplitude 0..1 driving the jaw/mouth. */
    @Volatile var mouthAmp: Float = 0f
    /** 0.3f..1.5f user animation intensity setting. */
    @Volatile var intensity: Float = 1f
    @Volatile var reducedMotion: Boolean = false
    /** Assistant display name used on dashboards. */
    @Volatile var assistantName: String = "MAX"
}
