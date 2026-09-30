package com.kevin.babeltrout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptHeuristicsTest {

    @Test
    fun `detects scripts`() {
        assertTrue(ScriptHeuristics.containsArabicScript("سلام"))
        assertTrue(ScriptHeuristics.containsArabicScript("ﻻ")) // presentation-form lam-alef
        assertTrue(ScriptHeuristics.containsCyrillic("привіт"))
        assertTrue(ScriptHeuristics.containsDevanagari("नमस्ते"))
        assertFalse(ScriptHeuristics.containsNonLatinScript("hello there"))
    }

    @Test
    fun `tells farsi from arabic`() {
        assertTrue(ScriptHeuristics.looksFarsi("این کتاب خوب است"))   // Persian kaf and yeh
        assertTrue(ScriptHeuristics.looksFarsi("۱۲۳"))               // Persian digits
        assertFalse(ScriptHeuristics.looksFarsi("هذا كتاب جيد"))     // Arabic kaf and yeh
        assertEquals("fa", ScriptHeuristics.guessFromScript("چطوری"))
        assertEquals("ar", ScriptHeuristics.guessFromScript("مرحبا"))
    }

    @Test
    fun `tells ukrainian from russian`() {
        assertEquals("uk", ScriptHeuristics.guessFromScript("Привіт, як справи?"))
        assertEquals("ru", ScriptHeuristics.guessFromScript("Привет, как дела?"))
    }

    @Test
    fun `script guess covers hindi and latin languages`() {
        assertEquals("hi", ScriptHeuristics.guessFromScript("आप कैसे हैं"))
        assertEquals("es", ScriptHeuristics.guessFromScript("¿dónde está el baño?"))
        assertEquals("fr", ScriptHeuristics.guessFromScript("où est la gare"))
        assertEquals("en", ScriptHeuristics.guessFromScript("where is the station"))
        assertEquals("de", ScriptHeuristics.guessFromScript("Wo ist der Bahnhof?"))
    }

    @Test
    fun `german is told apart from spanish and french`() {
        // "es" is also a Spanish marker; German still wins on count.
        assertEquals("de", ScriptHeuristics.guessFromScript("es ist gut"))
        // Umlauts and ß are German evidence on their own.
        assertEquals("de", ScriptHeuristics.guessFromScript("Schön, Grüße"))
        // "ü" used to count as French.
        assertFalse(ScriptHeuristics.looksFrench("für über"))
        assertTrue(ScriptHeuristics.looksGerman("für über"))
        assertEquals(2, ScriptHeuristics.scoreLanguage("Ich bin müde", "de"))
        assertEquals(0, ScriptHeuristics.scoreLanguage("where is the station", "de"))
    }

    @Test
    fun `marker words match at the start and end of the transcript`() {
        // Regression: markers used to require a leading space, so "the" as the first word was missed.
        assertTrue(ScriptHeuristics.looksEnglish("the train"))
        assertTrue(ScriptHeuristics.looksSpanish("la casa"))
    }

    @Test
    fun `pickBestTranscriptForPair prefers the non-latin alternative`() {
        val candidates = listOf("salaam khoobi", "سلام خوبی")
        assertEquals("سلام خوبی", ScriptHeuristics.pickBestTranscriptForPair(candidates, "en", "fa"))
        assertEquals("salaam khoobi", ScriptHeuristics.pickBestTranscriptForPair(candidates, "en", "fr"))
        assertEquals("", ScriptHeuristics.pickBestTranscriptForPair(emptyList(), "en", "fa"))
    }

    @Test
    fun `forced source picks transcript in the right script`() {
        val candidates = listOf("namaste", "नमस्ते")
        assertEquals("नमस्ते", ScriptHeuristics.pickBestForcedTranscript(candidates, "hi"))
    }
}
