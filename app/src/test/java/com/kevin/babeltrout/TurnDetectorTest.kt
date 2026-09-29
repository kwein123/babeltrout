package com.kevin.babeltrout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnDetectorTest {

    private fun frame(value: Int, size: Int = 512) = ShortArray(size) { value.toShort() }

    private fun TurnDetector.feed(vararg speech: Boolean, startValue: Int = 0): List<TurnDetector.Event> =
        speech.withIndex().flatMap { (i, s) -> process(frame(startValue + i), s) }

    @Test
    fun `silence produces no events`() {
        val d = TurnDetector()
        assertTrue(d.feed(false, false, false).isEmpty())
        assertFalse(d.isInTurn)
    }

    @Test
    fun `a turn starts with pre-roll and ends on silence`() {
        val d = TurnDetector(sampleRate = 16_000, preRollMillis = 64) // 1024 samples = two frames
        val events = d.feed(false, false, false, true, true, false, startValue = 1)

        val start = events.first() as TurnDetector.Event.TurnStarted
        // Only the most recent two silent frames (values 2 and 3) are kept as pre-roll.
        assertEquals(1024, start.preRoll.size)
        assertEquals(2, start.preRoll.first().toInt())
        assertEquals(3, start.preRoll.last().toInt())

        val audio = events.filterIsInstance<TurnDetector.Event.Audio>()
        assertEquals(listOf(4, 5, 6), audio.map { it.samples.first().toInt() }) // two speech frames + trailing frame
        val end = events.last() as TurnDetector.Event.TurnEnded
        assertEquals(TurnDetector.Reason.SILENCE, end.reason)
        assertFalse(d.isInTurn)
    }

    @Test
    fun `pre-roll is not replayed into the next turn`() {
        val d = TurnDetector(preRollMillis = 64)
        d.feed(false, true, false)
        val second = d.feed(true).first() as TurnDetector.Event.TurnStarted
        assertEquals(512, second.preRoll.size) // just the trailing frame of the previous turn
    }

    @Test
    fun `long turns are capped and wait for silence before restarting`() {
        val d = TurnDetector(sampleRate = 16_000, maxTurnMillis = 96) // 1536 samples = three frames
        val events = d.feed(true, true, true, true, true)
        val ends = events.filterIsInstance<TurnDetector.Event.TurnEnded>()
        assertEquals(1, ends.size)
        assertEquals(TurnDetector.Reason.MAX_LENGTH, ends.single().reason)
        assertFalse("still speaking after the cap: no new turn yet", d.isInTurn)

        val resumed = d.feed(false, true)
        assertTrue(resumed.first() is TurnDetector.Event.TurnStarted)
    }

    @Test
    fun `reset forgets pre-roll and the current turn`() {
        val d = TurnDetector(preRollMillis = 64)
        d.feed(false, false, true)
        assertTrue(d.isInTurn)
        d.reset()
        assertFalse(d.isInTurn)
        val start = d.feed(true).first() as TurnDetector.Event.TurnStarted
        assertEquals(0, start.preRoll.size)
    }
}
