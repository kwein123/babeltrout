package com.kevin.babeltrout

import android.os.ParcelFileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue

/**
 * One turn's audio, streamed into SpeechRecognizer via RecognizerIntent.EXTRA_AUDIO_SOURCE.
 *
 * The recognizer reads 16 kHz mono PCM16 from [readSide]; the session ends when we close the write
 * side. A dedicated writer thread drains a queue so the microphone thread never blocks on the pipe
 * (the recognizer can take a few hundred milliseconds to start reading).
 */
class RecognizerAudioPipe {

    private val pipe = ParcelFileDescriptor.createPipe()

    /** Put this in the intent. The caller closes it (via [close]) when the session is over. */
    val readSide: ParcelFileDescriptor get() = pipe[0]

    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var failed = false

    private val writer = Thread({
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
            try {
                while (true) {
                    val chunk = queue.take()
                    if (chunk === END) {
                        // A little trailing silence helps the recognizer finalize the last word.
                        out.write(toBytes(ShortArray(TRAILING_SILENCE_SAMPLES)))
                        break
                    }
                    out.write(toBytes(chunk))
                }
            } catch (_: IOException) {
                failed = true // recognizer closed its end (error or cancel); drop the rest
            } catch (_: InterruptedException) {
                // abandoned
            }
        }
    }, "babeltrout-recognizer-pipe").apply { start() }

    fun write(samples: ShortArray) {
        if (!failed) queue.put(samples)
    }

    /** No more audio for this turn: flush, append trailing silence, and close the write side. */
    fun finish() {
        queue.put(END)
    }

    /** Abandon the turn and release both ends. Safe to call more than once. */
    fun close() {
        if (writer.isAlive) {
            queue.clear()
            queue.put(END)
        }
        runCatching { pipe[0].close() }
    }

    private fun toBytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(samples)
        return buffer.array()
    }

    private companion object {
        val END = ShortArray(0)
        const val TRAILING_SILENCE_SAMPLES = 16_000 * 3 / 10 // 0.3 s
    }
}
