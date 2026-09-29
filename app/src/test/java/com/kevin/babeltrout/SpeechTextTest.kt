package com.kevin.babeltrout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun `short text is one chunk`() {
        assertEquals(listOf("Hello there."), SpeechText.chunk("  Hello there.  ", 4000))
        assertEquals(emptyList<String>(), SpeechText.chunk("   ", 4000))
    }

    @Test
    fun `long text splits on sentences and respects the limit`() {
        val text = (1..50).joinToString(" ") { "Sentence number $it is here." }
        val chunks = SpeechText.chunk(text, 100)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it, it.length <= 100) }
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `splits on persian and hindi sentence marks`() {
        val chunks = SpeechText.chunk("سلام؟ خوبم. नमस्ते। ठीक है।", 8)
        assertTrue(chunks.all { it.length <= 8 })
        assertEquals(4, chunks.size)
    }

    @Test
    fun `a single huge word is hard-split`() {
        val chunks = SpeechText.chunk("x".repeat(25), 10)
        assertEquals(listOf(10, 10, 5), chunks.map { it.length })
    }

    @Test
    fun `timeout grows with length and shrinks with speech rate`() {
        val short = SpeechText.speakTimeoutMillis("hi", 1f)
        val long = SpeechText.speakTimeoutMillis("x".repeat(1000), 1f)
        val longFast = SpeechText.speakTimeoutMillis("x".repeat(1000), 2f)
        assertEquals(15_000L, short)
        assertTrue(long > 100_000L)
        assertTrue(longFast < long)
        assertEquals(300_000L, SpeechText.speakTimeoutMillis("x".repeat(100_000), 1f))
    }
}
