package com.max.neuraldimension.core.gl

/**
 * Shared state between the app (voice engine, chat, UI) and the GL robot renderer.
 * The renderer reads these every frame; writers are on the main/voice threads.
 */
object RobotState {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, HAPPY, CURIOUS, CONFUSED, EXCITED, ERROR }

    /** Central transition log (logcat tag MAX_STATE) so mode wiring is runtime-assertable. */
    @Volatile var mode: Mode = Mode.IDLE
        set(v) {
            if (field != v) {
                field = v
                android.util.Log.i("MAX_STATE", "mode=" + v.name)
            }
        }
    /** Live speech amplitude 0..1 driving the jaw/mouth. */
    @Volatile var mouthAmp: Float = 0f
    /** 0.3f..1.5f user animation intensity setting. */
    @Volatile var intensity: Float = 1f
    @Volatile var reducedMotion: Boolean = false
    /** Assistant display name used on dashboards. */
    @Volatile var assistantName: String = "MAX"
    /** Home dashboard: frame the bust in the upper screen area so panels below never touch the face. */
    @Volatile var compactBust: Boolean = false
}
