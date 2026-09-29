package com.kevin.babeltrout

import android.Manifest
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * The app-owned microphone for hands-free conversation.
 *
 * Records 16 kHz mono audio continuously on its own thread, runs the Silero voice-activity detector
 * (via sherpa-onnx) on every 32 ms frame, and reports turns through [TurnDetector] events.
 * [muted] drops audio entirely (used while a translation is processed and spoken) so the app never
 * translates its own voice. Unlike SpeechRecognizer, nothing here restarts, so there are no beeps and
 * no gaps between turns.
 */
class VoiceActivityMic(
    private val assets: AssetManager,
    private val onEvent: (TurnDetector.Event) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    @Volatile var muted = false
        set(value) {
            if (field && !value) resetRequested = true
            field = value
        }

    /** Pause length that ends a turn. Applied when the mic next starts. */
    @Volatile var endOfTurnSilenceSeconds = 1.2f

    @Volatile private var running = false
    @Volatile private var resetRequested = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (running) return
        running = true
        thread = Thread({ captureLoop() }, "babeltrout-mic").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun captureLoop() {
        var record: AudioRecord? = null
        var vad: Vad? = null
        try {
            vad = Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = endOfTurnSilenceSeconds,
                        minSpeechDuration = 0.25f,
                        windowSize = FRAME,
                        maxSpeechDuration = 120f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // tuned for ASR: no automatic gain tricks
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, SAMPLE_RATE * 2), // one second of headroom
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable (in use by another app?)" }
            record.startRecording()

            val detector = TurnDetector(sampleRate = SAMPLE_RATE)
            val shorts = ShortArray(FRAME)
            val floats = FloatArray(FRAME)
            while (running) {
                val read = record.read(shorts, 0, FRAME)
                if (read < 0) error("Microphone read failed ($read)")
                if (read < FRAME) continue
                if (muted) continue
                if (resetRequested) {
                    resetRequested = false
                    vad.reset()
                    detector.reset()
                }
                for (i in 0 until FRAME) floats[i] = shorts[i] / 32768f
                vad.acceptWaveform(floats)
                while (!vad.empty()) vad.pop() // we use the live flag, not the segment queue
                val speaking = vad.isSpeechDetected()
                detector.process(shorts.copyOf(), speaking).forEach(onEvent)
            }
        } catch (t: Throwable) {
            if (running) onError(t)
        } finally {
            running = false
            record?.let { runCatching { it.stop() }; it.release() }
            vad?.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = 512 // Silero's window at 16 kHz (32 ms)
    }
}
