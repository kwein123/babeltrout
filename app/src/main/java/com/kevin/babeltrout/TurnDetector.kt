package com.kevin.babeltrout

/**
 * Turns a stream of (audio frame, "is someone speaking?") pairs into conversation turns.
 *
 * The voice-activity detector only reports speech after about a quarter second of it, so the start of
 * every turn would be clipped. This keeps a short rolling pre-roll buffer and prepends it when a turn
 * begins. It also caps turn length so a noisy room can't hold the recognizer open forever.
 *
 * Pure Kotlin: no Android types, so it is unit-tested directly.
 */
class TurnDetector(
    private val sampleRate: Int = 16_000,
    preRollMillis: Int = 600,
    maxTurnMillis: Int = 90_000,
) {
    sealed class Event {
        /** A turn began. [preRoll] is the audio just before detection, oldest first. */
        class TurnStarted(val preRoll: ShortArray) : Event()

        /** More audio for the turn in progress. */
        class Audio(val samples: ShortArray) : Event()

        /** The turn ended: silence long enough (as judged by the VAD), or the length cap was hit. */
        class TurnEnded(val reason: Reason) : Event()
    }

    enum class Reason { SILENCE, MAX_LENGTH }

    private val preRollCapacity = sampleRate * preRollMillis / 1000
    private val maxTurnSamples = sampleRate.toLong() * maxTurnMillis / 1000
    private val preRoll = ArrayDeque<ShortArray>()
    private var preRollSamples = 0
    private var inTurn = false
    private var turnSamples = 0L
    /** After a forced (max-length) end, wait for real silence before starting a new turn. */
    private var waitForSilence = false

    val isInTurn: Boolean get() = inTurn

    /** Feed one frame. Returns the events it produced, in order (usually zero or one). */
    fun process(frame: ShortArray, speechDetected: Boolean): List<Event> {
        if (!speechDetected) waitForSilence = false

        if (!inTurn) {
            if (speechDetected && !waitForSilence) {
                inTurn = true
                val start = Event.TurnStarted(drainPreRoll())
                turnSamples = frame.size.toLong()
                return listOf(start, Event.Audio(frame))
            }
            remember(frame)
            return emptyList()
        }

        if (!speechDetected) {
            inTurn = false
            turnSamples = 0
            // Include this frame: the VAD's silence verdict lags, so it may hold the last syllable.
            remember(frame)
            return listOf(Event.Audio(frame), Event.TurnEnded(Reason.SILENCE))
        }

        turnSamples += frame.size
        if (turnSamples >= maxTurnSamples) {
            inTurn = false
            turnSamples = 0
            waitForSilence = true
            return listOf(Event.Audio(frame), Event.TurnEnded(Reason.MAX_LENGTH))
        }
        return listOf(Event.Audio(frame))
    }

    /** Forget everything (used when the mic is muted and later resumed). */
    fun reset() {
        preRoll.clear()
        preRollSamples = 0
        inTurn = false
        turnSamples = 0
        waitForSilence = false
    }

    private fun remember(frame: ShortArray) {
        preRoll.addLast(frame)
        preRollSamples += frame.size
        while (preRollSamples - preRoll.first().size >= preRollCapacity) {
            preRollSamples -= preRoll.removeFirst().size
        }
    }

    private fun drainPreRoll(): ShortArray {
        val out = ShortArray(preRollSamples)
        var offset = 0
        for (chunk in preRoll) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        preRoll.clear()
        preRollSamples = 0
        return out
    }
}
