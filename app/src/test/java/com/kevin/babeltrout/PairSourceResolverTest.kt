package com.kevin.babeltrout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairSourceResolverTest {

    @Test
    fun `confident recognizer hint is trusted`() {
        val r = PairSourceResolver()
        assertEquals("en", r.fromRecognizerHint("hello", "en", "fa", "en-US", hintConfident = true))
    }

    @Test
    fun `unconfident hint is trusted only when the script agrees`() {
        val r = PairSourceResolver()
        assertEquals("fa", r.fromRecognizerHint("سلام", "en", "fa", "fa-IR", hintConfident = false))
        assertNull(r.fromRecognizerHint("hello", "en", "fa", "fa-IR", hintConfident = false))
        assertNull(r.fromRecognizerHint("hello", "en", "fa", "de-DE", hintConfident = true))
    }

    @Test
    fun `ml detection inside the pair wins`() {
        assertEquals("fa", PairSourceResolver().resolve("salaam", "en", "fa", "", mlDetected = "fa"))
    }

    @Test
    fun `script decides when ml detection is outside the pair`() {
        assertEquals("fa", PairSourceResolver().resolve("سلام", "en", "fa", "", mlDetected = "ur"))
        assertEquals("hi", PairSourceResolver().resolve("नमस्ते", "hi", "en", "", mlDetected = "mr"))
        assertEquals("uk", PairSourceResolver().resolve("привіт", "uk", "ru", "", mlDetected = "und"))
        assertEquals("ar", PairSourceResolver().resolve("مرحبا", "fa", "ar", "", mlDetected = "und"))
    }

    @Test
    fun `ties alternate speakers`() {
        val r = PairSourceResolver()
        val first = r.resolve("mmm", "en", "fr", "", mlDetected = "und")
        val second = r.resolve("mmm", "en", "fr", "", mlDetected = "und")
        assertEquals("en", first)
        assertEquals("fr", second)
        r.reset()
        assertNull(r.lastSourceCode)
    }
}
