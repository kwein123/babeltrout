package com.kevin.babeltrout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {

    @Test
    fun `normalizeCode strips region and case`() {
        assertEquals("fa", Languages.normalizeCode("fa-IR"))
        assertEquals("fa", Languages.normalizeCode("FA_ir"))
        assertEquals("en", Languages.normalizeCode("en"))
        assertEquals("", Languages.normalizeCode(null))
        assertEquals("", Languages.normalizeCode("  "))
    }

    @Test
    fun `normalizeCode maps three-letter codes that TTS engines report`() {
        // SherpaTTS and others expose Locale("fas"); these must still count as Farsi voices.
        assertEquals("fa", Languages.normalizeCode("fas"))
        assertEquals("fa", Languages.normalizeCode("pes"))
        assertEquals("hi", Languages.normalizeCode("hin"))
        assertEquals("uk", Languages.normalizeCode("ukr"))
        assertEquals("ar", Languages.normalizeCode("ara"))
    }

    @Test
    fun `every language has a unique code, locale and sample`() {
        assertEquals(Languages.all.size, Languages.codes.size)
        Languages.all.forEach {
            assertTrue(it.localeTag.startsWith(it.code + "-"))
            assertTrue(it.sampleText.isNotBlank())
            assertTrue("sample for ${it.code} should be in its own script",
                ScriptHeuristics.hasScript(it.sampleText, it.script))
        }
    }

    @Test
    fun `hindi is supported`() {
        val hindi = Languages.option("hi-IN")!!
        assertEquals("Hindi", hindi.label)
        assertEquals(Script.DEVANAGARI, hindi.script)
    }

    @Test
    fun `german is supported`() {
        val german = Languages.option("de-DE")!!
        assertEquals("German", german.label)
        assertEquals(Script.LATIN, german.script)
        assertEquals("de", Languages.normalizeCode("deu"))
        assertEquals("de", Languages.normalizeCode("ger"))
        assertFalse(Languages.isRtl("de"))
    }

    @Test
    fun `rtl only for arabic script`() {
        assertTrue(Languages.isRtl("fa"))
        assertTrue(Languages.isRtl("ar"))
        assertFalse(Languages.isRtl("hi"))
        assertFalse(Languages.isRtl("en"))
    }
}
