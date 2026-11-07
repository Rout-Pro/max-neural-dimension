package com.max.neuraldimension.features.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure relisten-gate semantics: mute/continuous/busy/speaking (M-A). */
class VoiceFlowTest {
    @Test fun resumesWhenContinuousUnmutedIdle() {
        assertTrue(VoiceEngine.shouldResumeListening(continuous = true, muted = false, busy = false, speaking = false))
    }
    @Test fun muteSuppressesRelisten() {
        assertFalse(VoiceEngine.shouldResumeListening(continuous = true, muted = true, busy = false, speaking = false))
    }
    @Test fun notContinuousSuppresses() {
        assertFalse(VoiceEngine.shouldResumeListening(continuous = false, muted = false, busy = false, speaking = false))
    }
    @Test fun busySuppresses() {
        assertFalse(VoiceEngine.shouldResumeListening(continuous = true, muted = false, busy = true, speaking = false))
    }
    @Test fun speakingSuppresses() {
        assertFalse(VoiceEngine.shouldResumeListening(continuous = true, muted = false, busy = false, speaking = true))
    }
}
