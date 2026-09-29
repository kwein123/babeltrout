package com.kevin.babeltrout

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Collections

/**
 * Recognizes one hands-free conversation turn in both conversation languages at once.
 *
 * Each "lane" is a SpeechRecognizer locked to one language and fed the same turn audio through its own
 * pipe. When every lane has finished, [onTurnRecognized] receives one candidate per language and
 * [TurnLanguageChooser] decides which one the speaker actually used.
 *
 * If the recognizer service refuses a second concurrent session (busy / too many requests), that lane
 * is retried after the others finish, replaying the buffered turn audio. Slower, but still correct.
 *
 * Threading: [beginTurnAudio], [write] and [finishAudio] are called on the microphone thread; everything
 * else, including all callbacks, runs on the main thread (SpeechRecognizer requires it).
 */
class DualTurnRecognizer(
    private val context: Context,
    private val buildIntent: (languageCode: String, audio: ParcelFileDescriptor) -> Intent,
    private val onPartialText: (languageCode: String, text: String) -> Unit,
    private val onTurnRecognized: (candidates: List<TurnLanguageChooser.Candidate>, errors: List<Int>) -> Unit,
) {
    private var lanes: List<Lane> = emptyList()

    /**
     * Some recognizers report a fixed placeholder confidence (seen on-device: Ukrainian always 0.9396984).
     * A score that repeats exactly for different text carries no information, so once that happens for a
     * language its scores are ignored while the app runs (state lives in the companion object so it
     * survives stopping and restarting the conversation mic).
     */
    private fun trustedConfidence(code: String, confidence: Float?, text: String): Float? {
        if (confidence == null || text.isBlank()) return confidence
        if (code in unreliableConfidence) return null
        val seen = confidenceHistory.getOrPut(code) { mutableMapOf() }
        val previousText = seen[confidence]
        if (previousText != null && previousText != text) {
            Log.i(TAG, "lane $code: confidence $confidence repeats for different text; ignoring its scores")
            unreliableConfidence += code
            return null
        }
        seen[confidence] = text
        return confidence
    }
    private val turnAudio: MutableList<ShortArray> = Collections.synchronizedList(mutableListOf())
    @Volatile private var pipes: Map<String, RecognizerAudioPipe> = emptyMap()
    @Volatile private var audioComplete = false
    private var turnStartedAt = 0L

    /** Mic thread: a turn began. Opens one pipe per language and buffers the audio for retries. */
    fun beginTurnAudio(codes: List<String>, preRoll: ShortArray) {
        turnAudio.clear()
        audioComplete = false
        pipes.values.forEach { it.close() }
        pipes = codes.associateWith { RecognizerAudioPipe() }
        write(preRoll)
    }

    /** Mic thread. */
    fun write(samples: ShortArray) {
        turnAudio.add(samples)
        pipes.values.forEach { it.write(samples) }
    }

    /** Mic thread: the turn is over; closing the pipes ends each recognition session. */
    fun finishAudio() {
        audioComplete = true
        pipes.values.forEach { it.finish() }
    }

    /** Main thread: start one recognition session per language for the turn begun on the mic thread. */
    fun startSessions() {
        val current = pipes
        if (lanes.map { it.code } != current.keys.toList()) {
            lanes.forEach { it.destroy() }
            lanes = current.keys.map { Lane(it) }
        }
        turnStartedAt = SystemClock.elapsedRealtime()
        lanes.forEach { lane -> current[lane.code]?.let { lane.start(it) } }
    }

    fun cancel() {
        lanes.forEach { it.cancel() }
        pipes.values.forEach { it.close() }
        pipes = emptyMap()
        turnAudio.clear()
    }

    fun destroy() {
        cancel()
        lanes.forEach { it.destroy() }
        lanes = emptyList()
    }

    private fun onLaneFinished() {
        val waiting = lanes.filter { it.retryPending }
        if (lanes.any { it.running }) return
        if (waiting.isNotEmpty() && audioComplete) {
            // The service refused concurrent sessions: replay the saved turn into the waiting lane(s).
            waiting.forEach { lane ->
                Log.i(TAG, "lane ${lane.code}: retrying sequentially")
                val replay = RecognizerAudioPipe()
                synchronized(turnAudio) { turnAudio.forEach(replay::write) }
                replay.finish()
                lane.start(replay, isRetry = true)
            }
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - turnStartedAt
        Log.i(TAG, "turn recognized in ${elapsed} ms: " + lanes.joinToString { "${it.code}=${it.text().length} chars conf=${it.confidence()} err=${it.error}" })
        pipes.values.forEach { it.close() }
        pipes = emptyMap()
        onTurnRecognized(
            lanes.map { TurnLanguageChooser.Candidate(it.code, it.text(), trustedConfidence(it.code, it.confidence(), it.text())) },
            lanes.mapNotNull { it.error },
        )
    }

    private inner class Lane(val code: String) : RecognitionListener {
        private val recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(this) }
        private var pipe: RecognizerAudioPipe? = null
        private val segments = mutableListOf<String>()
        private val confidences = mutableListOf<Float>()
        private var partial = ""
        private var retried = false
        var running = false
            private set
        var retryPending = false
            private set
        var error: Int? = null
            private set

        fun start(audio: RecognizerAudioPipe, isRetry: Boolean = false) {
            pipe = audio
            segments.clear()
            confidences.clear()
            partial = ""
            error = null
            retryPending = false
            retried = isRetry
            running = true
            runCatching { recognizer.startListening(buildIntent(code, audio.readSide)) }
                .onFailure { finish(SpeechRecognizer.ERROR_CLIENT) }
        }

        fun text(): String = (segments + partial.takeIf { segments.isEmpty() && it.isNotBlank() }.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        fun confidence(): Float? = confidences.filter { it > 0f }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

        fun cancel() {
            running = false
            retryPending = false
            runCatching { recognizer.cancel() }
            pipe?.close()
            pipe = null
        }

        fun destroy() {
            cancel()
            runCatching { recognizer.destroy() }
        }

        private fun collect(bundle: Bundle?) {
            val best = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (best.isNotBlank()) {
                segments += best
                bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()?.let { confidences += it }
            }
            partial = ""
        }

        private fun finish(errorCode: Int?) {
            if (!running) return
            running = false
            pipe?.close()
            pipe = null
            val refused = errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || errorCode == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
            if (refused && !retried && text().isBlank() && lanes.size > 1) {
                retryPending = true
            } else {
                error = errorCode
            }
            onLaneFinished()
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) {
                partial = text
                onPartialText(code, (segments + text).joinToString(" "))
            }
        }

        override fun onSegmentResults(segmentResults: Bundle) = collect(segmentResults)

        override fun onEndOfSegmentedSession() = finish(null)

        override fun onResults(results: Bundle?) {
            collect(results)
            finish(null)
        }

        override fun onError(errorCode: Int) {
            // NO_MATCH just means this language heard nothing it recognized; that's a normal outcome.
            finish(if (errorCode == SpeechRecognizer.ERROR_NO_MATCH) null else errorCode)
        }
    }

    private companion object {
        const val TAG = "Babeltrout"
        val confidenceHistory = mutableMapOf<String, MutableMap<Float, String>>()
        val unreliableConfidence = mutableSetOf<String>()
    }
}
