package com.kevin.babeltrout

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * Speaks text with a built-in Piper voice (sherpa-onnx), streaming audio to the speaker as it is
 * generated so the first words play quickly even for long translations.
 *
 * All model work runs on one dedicated thread: sherpa-onnx's OfflineTts is not thread-safe, and
 * loading a voice takes about a second, so the last-used voice stays loaded.
 */
class PiperSpeaker(private val store: PiperVoiceStore) {

    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "piper-tts") }.asCoroutineDispatcher()

    private var loadedKey: String? = null
    private var engine: OfflineTts? = null

    @Volatile private var stopRequested = false
    @Volatile private var track: AudioTrack? = null

    /**
     * Speaks [text]. Returns true if it played to the end, false if stopped or cancelled.
     * [onStart] runs (on the TTS thread) when the first audio is ready.
     */
    suspend fun speak(
        voice: PiperVoiceStore.Installed,
        text: String,
        speed: Float,
        onStart: () -> Unit = {},
    ): Boolean = withContext(dispatcher) {
        stopRequested = false
        val job = coroutineContext.job
        val tts = load(voice)
        val audio = buildTrack(tts.sampleRate())
        track = audio
        val sink = StreamingSink(audio, job, onStart)
        try {
            audio.play()
            // Sentence-sized pieces keep stop() responsive and memory flat on long text.
            for (piece in SpeechText.chunk(text, MAX_PIECE_CHARS)) {
                if (stopRequested || !job.isActive) break
                tts.generateWithCallback(piece, 0, speed.coerceIn(0.5f, 2f), sink)
            }
            val framesWritten = sink.framesWritten
            // Pad with silence so short utterances aren't held back waiting for a full buffer,
            // then let the real audio finish playing.
            if (framesWritten > 0 && !stopRequested) {
                val padding = FloatArray(audio.bufferSizeInFrames)
                audio.write(padding, 0, padding.size, AudioTrack.WRITE_NON_BLOCKING)
            }
            // Bounded by the audio's own length (plus slack) so a stalled track can't hang this thread.
            val drainDeadline = System.currentTimeMillis() + framesWritten * 1000 / tts.sampleRate() + 2_000
            while (!stopRequested && isActive && audio.playbackHeadPosition.toLong() < framesWritten &&
                System.currentTimeMillis() < drainDeadline
            ) {
                delay(30)
            }
            !stopRequested && isActive
        } finally {
            track = null
            runCatching { audio.stop() }
            audio.release()
        }
    }

    /**
     * Receives generated audio from sherpa-onnx and streams it to [audio].
     *
     * This MUST stay a named class (not a lambda): sherpa-onnx's native code finds the callback by the
     * exact JNI signature `invoke([F)Ljava/lang/Integer;`. A lambda compiled or shrunk by R8 only has
     * `invoke(Object)`, which crashed release builds with NoSuchMethodError. proguard-rules.pro keeps
     * this method; PiperSpeakerTest checks the signature exists.
     */
    internal inner class StreamingSink(
        private val audio: AudioTrack,
        private val job: Job,
        private val onStart: () -> Unit,
    ) : (FloatArray) -> Int {

        var framesWritten = 0L
            private set
        private var started = false

        /** Returns 1 to continue generating, 0 to stop. */
        override fun invoke(samples: FloatArray): Int {
            if (stopRequested || !job.isActive) return 0
            if (!started) {
                started = true
                onStart()
            }
            // Write in small non-blocking slices. A single blocking write of a whole sentence can
            // wait forever if stop() pauses the track mid-write, wedging this thread and every later
            // request queued behind it.
            var offset = 0
            while (offset < samples.size) {
                if (stopRequested || !job.isActive) return 0
                val count = minOf(WRITE_SLICE_FRAMES, samples.size - offset)
                val written = audio.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                when {
                    written < 0 -> return 0 // track released or in error
                    written == 0 -> Thread.sleep(10) // buffer full; let playback catch up
                    else -> {
                        offset += written
                        framesWritten += written
                    }
                }
            }
            return if (stopRequested || !job.isActive) 0 else 1
        }
    }

    /** Stops playback immediately (safe from any thread). */
    fun stop() {
        stopRequested = true
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    /** Loads the voice ahead of time so the first sentence isn't delayed. */
    suspend fun preload(voice: PiperVoiceStore.Installed) {
        withContext(dispatcher) { load(voice) }
    }

    fun release() {
        stop()
        dispatcher.executor.execute {
            engine?.release()
            engine = null
            loadedKey = null
        }
        dispatcher.close()
    }

    private fun load(voice: PiperVoiceStore.Installed): OfflineTts {
        engine?.let { if (loadedKey == voice.key) return it }
        engine?.release()
        engine = null

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = voice.modelFile.absolutePath,
                    tokens = voice.tokensFile.absolutePath,
                    dataDir = store.espeakDataDir.absolutePath,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        return OfflineTts(assetManager = null, config = config).also {
            engine = it
            loadedKey = voice.key
        }
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 4 / 2)) // about half a second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private companion object {
        const val MAX_PIECE_CHARS = 400
        const val WRITE_SLICE_FRAMES = 2048
    }
}
