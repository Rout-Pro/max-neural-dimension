package com.max.neuraldimension.features.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * JVM unit tests for VoiceEngine's private WAV envelope parser (analyzeWav),
 * exercised by reflection so production code stays unchanged (V2).
 */
class VoiceEngineWavTest {

    private fun newEngine(): VoiceEngine {
        val ctx = Mockito.mock(Context::class.java)
        return VoiceEngine(ctx, CoroutineScope(Dispatchers.Default))
    }

    @Suppress("UNCHECKED_CAST")
    private fun analyze(engine: VoiceEngine, f: File): Pair<FloatArray, Int>? {
        val m = VoiceEngine::class.java.getDeclaredMethod("analyzeWav", File::class.java)
        m.isAccessible = true
        return m.invoke(engine, f) as Pair<FloatArray, Int>?
    }

    private fun writeWav(file: File, seconds: Double = 1.0, rate: Int = 22050,
                         channels: Int = 1, bits: Int = 16, tone: Boolean = true) {
        val n = (seconds * rate).toInt()
        val data = ByteArrayOutputStream()
        for (i in 0 until n) {
            val v = if (tone) (sin(2 * PI * 440.0 * i / rate) * 12000).toInt() else 0
            repeat(channels) {
                if (bits == 16) { data.write(v and 0xFF); data.write((v shr 8) and 0xFF) }
                else { data.write(((v shr 8) + 128) and 0xFF) }
            }
        }
        val pcm = data.toByteArray()
        val out = ByteArrayOutputStream()
        fun w32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun w16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); w32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); w32(16); w16(1); w16(channels); w32(rate)
        w32(rate * channels * bits / 8); w16(channels * bits / 8); w16(bits)
        out.write("data".toByteArray()); w32(pcm.size); out.write(pcm)
        file.writeBytes(out.toByteArray())
    }

    @Test fun toneYieldsFullEnvelope() {
        val f = File.createTempFile("tone", ".wav"); writeWav(f)
        val r = analyze(newEngine(), f)
        assertNotNull(r); r!!
        assertEquals(50, r.second)
        assertEquals(20, r.first.size)
        assertTrue("tone must produce strong windows", r.first.maxOrNull()!! > 0.5f)
        f.delete()
    }

    @Test fun silenceYieldsZeroEnvelope() {
        val f = File.createTempFile("silence", ".wav"); writeWav(f, tone = false)
        val r = analyze(newEngine(), f)
        assertNotNull(r); r!!
        assertTrue("silence must stay silent", r.first.all { it < 0.01f })
        f.delete()
    }

    @Test fun stereoParsed() {
        val f = File.createTempFile("stereo", ".wav"); writeWav(f, channels = 2)
        val r = analyze(newEngine(), f)
        assertNotNull(r); r!!
        assertEquals(20, r.first.size)
        f.delete()
    }

    @Test fun truncatedFileRejected() {
        val f = File.createTempFile("trunc", ".wav"); f.writeBytes(ByteArray(20) { 7 })
        assertNull(analyze(newEngine(), f))
        f.delete()
    }

    @Test fun non16BitRejected() {
        val f = File.createTempFile("8bit", ".wav"); writeWav(f, bits = 8)
        assertNull(analyze(newEngine(), f))
        f.delete()
    }
}
