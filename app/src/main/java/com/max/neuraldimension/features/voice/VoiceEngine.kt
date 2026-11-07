package com.max.neuraldimension.features.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.max.neuraldimension.core.gl.RobotState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Voice engine: real Android TTS + SpeechRecognizer.
 * Mouth sync is driven by an amplitude envelope measured from the actual
 * synthesized audio file, played back with MediaPlayer.
 */
class VoiceEngine(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        /** Continuous-conversation relisten gate. Pure; unit-tested (VoiceFlowTest). */
        fun shouldResumeListening(continuous: Boolean, muted: Boolean, busy: Boolean, speaking: Boolean): Boolean =
            continuous && !muted && !busy && !speaking
    }


    interface Listener {
        fun onListeningChanged(listening: Boolean)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onSpeechError(message: String)
        fun onSpeakingChanged(speaking: Boolean)
    }

    var listener: Listener? = null
    var languageTag: String = "en-IN"
    var speechRate: Float = 1.0f
    /** Selected TTS voice name; empty = system default for the language. */
    var voiceName: String = ""
    /** Resolved at each startListening: true when the on-device recognizer is in use. */
    @Volatile var recognizerIsOnDevice: Boolean = false
        private set

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    @Volatile var ttsReady: Boolean = false
        private set
    @Volatile var speaking: Boolean = false
        private set
    @Volatile var listening: Boolean = false
        private set

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                applyVoice()
            }
        }
    }

    fun applyVoice() {
        val t = tts ?: return
        val locale = Locale.forLanguageTag(languageTag)
        t.setLanguage(locale)
        if (voiceName.isNotBlank()) {
            runCatching { t.voices?.firstOrNull { it.name == voiceName }?.let { t.setVoice(it) } }
        }
        t.setSpeechRate(speechRate)
    }

    /** Installed TTS voices for the current language (device + downloadable). */
    fun availableVoiceNames(): List<String> {
        val t = tts ?: return emptyList()
        if (!ttsReady) return emptyList()
        val lang = Locale.forLanguageTag(languageTag).language
        return runCatching {
            t.voices?.filter { it.locale.language == lang }?.map { it.name }?.sorted()
        }.getOrNull() ?: emptyList()
    }

    /** Explicit on-device recognition (API 31+ per the Android SDK reference). */
    fun onDeviceRecognitionAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /** Honest label for the active/predicted recognition path - never claims offline. */
    fun recognitionModeDescription(): String = when {
        !recognitionAvailable() -> "not available on this device"
        onDeviceRecognitionAvailable() -> "on-device recognizer"
        else -> "device speech service (may use network)"
    }

    fun setRate(rate: Float) { speechRate = rate; tts?.setSpeechRate(rate) }

    fun recognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    // ---------------- Speaking with amplitude-synced mouth ----------------

    fun speak(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            speaking = true
            RobotState.mode = RobotState.Mode.SPEAKING
            withContext(Dispatchers.Main) { listener?.onSpeakingChanged(true) }
            try {
                val spoken = synthesizeAndPlay(text)
                if (!spoken) speakDirect(text)
            } finally {
                speaking = false
                RobotState.mouthAmp = 0f
                if (RobotState.mode == RobotState.Mode.SPEAKING) RobotState.mode = RobotState.Mode.IDLE
                withContext(Dispatchers.Main) { listener?.onSpeakingChanged(false) }
            }
        }
    }

    /** Synthesize to a WAV file, measure its amplitude envelope, then play it. */
    private suspend fun synthesizeAndPlay(text: String): Boolean {
        Log.i("MAX_VOICE", "synth start len=" + text.length + " t=" + SystemClock.uptimeMillis())
        val t = tts ?: return false
        if (!ttsReady) return false
        val file = File(context.cacheDir, "max_tts_${System.currentTimeMillis()}.wav")
        val done = CompletableDeferred<Boolean>()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(file.exists() && file.length() > 100) }
            @Deprecated("deprecated")
            override fun onError(utteranceId: String?) { done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
        })
        val res = t.synthesizeToFile(text, Bundle(), file, "max-utt")
        if (res != TextToSpeech.SUCCESS) { file.delete(); return false }
        val ok = withTimeoutOrNull(30_000) { done.await() } ?: false
        if (!ok) { file.delete(); return false }
        val env = analyzeWav(file)
        if (env == null) { file.delete(); return false }
        Log.i("MAX_VOICE", "synth done bytes=" + file.length() + " windows=" + env.first.size + " windowMs=" + env.second + " t=" + SystemClock.uptimeMillis())
        playWithEnvelope(file, env.first, env.second)
        file.delete()
        return true
    }

    /** Returns amplitude windows (0..1) and window length in ms. */
    private fun analyzeWav(file: File): Pair<FloatArray, Int>? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val bytes = ByteArray(min(len, 8L * 1024 * 1024).toInt())
                raf.readFully(bytes)
                // parse RIFF chunks
                var pos = 12
                var dataOff = -1; var dataLen = 0
                var channels = 1; var sampleRate = 22050; var bits = 16
                while (pos + 8 <= bytes.size) {
                    val id = String(bytes, pos, 4)
                    val size = readLE32(bytes, pos + 4)
                    if (id == "fmt " && pos + 16 <= bytes.size) {
                        channels = readLE16(bytes, pos + 10)
                        sampleRate = readLE32(bytes, pos + 12)
                        bits = readLE16(bytes, pos + 22)
                    }
                    if (id == "data") { dataOff = pos + 8; dataLen = min(size, bytes.size - dataOff) }
                    pos += 8 + size + (size and 1)
                }
                if (dataOff < 0 || bits != 16 || dataLen < 200 || sampleRate <= 0) return null
                val windowMs = 50
                val bytesPerWindow = sampleRate * channels * 2 * windowMs / 1000
                val nWin = dataLen / bytesPerWindow
                if (nWin <= 0) return null
                val amps = FloatArray(nWin)
                var maxA = 0.0001f
                for (w in 0 until nWin) {
                    var sum = 0.0; var cnt = 0
                    var p = dataOff + w * bytesPerWindow
                    val end = p + bytesPerWindow
                    while (p + 1 < end) {
                        val v = (bytes[p + 1].toInt() shl 8) or (bytes[p].toInt() and 0xFF)
                        sum += (v * v).toDouble(); cnt++
                        p += 2 * channels
                    }
                    val rms = sqrt(sum / maxOf(cnt, 1)).toFloat() / 32768f
                    amps[w] = rms
                    if (rms > maxA) maxA = rms
                }
                for (w in 0 until nWin) amps[w] = min(1f, (amps[w] / maxA) * 1.15f)
                Pair(amps, windowMs)
            }
        } catch (e: Exception) { null }
    }

    private fun readLE16(b: ByteArray, o: Int) = (b[o + 1].toInt() and 0xFF shl 8) or (b[o].toInt() and 0xFF)
    private fun readLE32(b: ByteArray, o: Int) =
        (b[o + 3].toInt() and 0xFF shl 24) or (b[o + 2].toInt() and 0xFF shl 16) or
            (b[o + 1].toInt() and 0xFF shl 8) or (b[o].toInt() and 0xFF)

    private suspend fun playWithEnvelope(file: File, amps: FloatArray, windowMs: Int) {
        withContext(Dispatchers.Main) {
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.start()
                Log.i("MAX_VOICE", "playback start durMs=" + mp.duration + " t=" + SystemClock.uptimeMillis())
            } catch (e: Exception) {
                runCatching { mp.release() }
                player = null
                // audio path failed: still animate from the real envelope for its duration
                scope.launch(Dispatchers.IO) { animateEnvelopeOnly(amps, windowMs) }
                return@withContext
            }
            scope.launch(Dispatchers.IO) {
                try {
                    var lastLog = 0L
                    while (player === mp && mp.isPlaying) {
                        val idx = mp.currentPosition / windowMs
                        RobotState.mouthAmp = if (idx in amps.indices) amps[idx] else 0f
                        val now = SystemClock.uptimeMillis()
                        if (now - lastLog > 200) {
                            lastLog = now
                            Log.i("MAX_VOICE", "play posMs=" + mp.currentPosition + " amp=" + String.format("%.2f", RobotState.mouthAmp))
                        }
                        delay(30)
                    }
                } catch (_: Exception) { }
                RobotState.mouthAmp = 0f
                Log.i("MAX_VOICE", "playback end t=" + SystemClock.uptimeMillis())
                runCatching { if (player === mp) { mp.stop() } ; mp.release() }
                if (player === mp) player = null
            }
            // wait for playback to finish
            withContext(Dispatchers.IO) {
                while (player === mp) delay(100)
            }
        }
    }

    private suspend fun animateEnvelopeOnly(amps: FloatArray, windowMs: Int) {
        for (a in amps) {
            RobotState.mouthAmp = a
            delay(windowMs.toLong())
        }
        RobotState.mouthAmp = 0f
    }

    /** Last-resort path: stream straight to the speaker; mouth follows utterance activity. */
    private suspend fun speakDirect(text: String) {
        val t = tts ?: return
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.complete(true) }
                @Deprecated("deprecated")
                override fun onError(utteranceId: String?) { done.complete(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "max-direct")
        }
        scope.launch(Dispatchers.IO) {
            var phase = 0f
            while (!done.isCompleted) {
                phase += 0.55f
                RobotState.mouthAmp = (0.35f + 0.4f * abs(kotlin.math.sin(phase)))
                delay(60)
            }
            RobotState.mouthAmp = 0f
        }
        withTimeoutOrNull(120_000) { done.await() }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
        runCatching { player?.let { if (it.isPlaying) it.stop(); it.release() } }
        player = null
        speaking = false
        RobotState.mouthAmp = 0f
        if (RobotState.mode == RobotState.Mode.SPEAKING) RobotState.mode = RobotState.Mode.IDLE
    }

    // ---------------- Listening ----------------

    fun startListening() {
        if (listening) return
        if (!recognitionAvailable()) {
            listener?.onSpeechError("Speech recognition is not available on this device")
            return
        }
        scope.launch(Dispatchers.Main) {
            try {
                val onDevice = onDeviceRecognitionAvailable()
                recognizerIsOnDevice = onDevice
                val r = if (onDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                }
                recognizer = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        RobotState.mode = RobotState.Mode.LISTENING
                        listener?.onListeningChanged(true)
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        val amp = ((rmsdB + 2f) / 10f).coerceIn(0f, 1f)
                        RobotState.mouthAmp = 0f
                        pulseAmp = amp
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        listening = false
                        if (RobotState.mode == RobotState.Mode.LISTENING) RobotState.mode = RobotState.Mode.IDLE
                        listener?.onListeningChanged(false)
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Speech service error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during recognition"
                            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Speech server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                            else -> "Recognition error $error"
                        }
                        listener?.onSpeechError(msg)
                    }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        if (RobotState.mode == RobotState.Mode.LISTENING) RobotState.mode = RobotState.Mode.IDLE
                        listener?.onListeningChanged(false)
                        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = texts?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) listener?.onFinal(text)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = texts?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) listener?.onPartial(text)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                r.startListening(intent)
            } catch (e: Exception) {
                listening = false
                listener?.onSpeechError("Could not start recognizer: ${e.message}")
            }
        }
    }

    @Volatile var pulseAmp: Float = 0f
        private set

    fun stopListening() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
        if (RobotState.mode == RobotState.Mode.LISTENING) RobotState.mode = RobotState.Mode.IDLE
    }

    fun release() {
        stopListening()
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
    }
}
