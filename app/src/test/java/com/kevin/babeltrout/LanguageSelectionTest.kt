package com.kevin.babeltrout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageSelectionTest {

    @Test
    fun `new installs start with the nine original languages`() {
        assertEquals(listOf("en", "fa", "uk", "ru", "ar", "fr", "es", "hi", "de"), LanguageSelection.parse(null).codes)
        assertEquals(LanguageSelection.defaults.codes, LanguageSelection.parse("").codes)
    }

    @Test
    fun `round-trips through storage`() {
        val selection = LanguageSelection.defaults.remove("ar").add("it")
        assertEquals(selection.codes, LanguageSelection.parse(selection.serialize()).codes)
        assertEquals("it", selection.codes.last())
    }

    @Test
    fun `parse drops unknown and duplicate codes and restores english`() {
        assertEquals(listOf("en", "fa", "de"), LanguageSelection.parse("fa, xx, fa-IR, de").codes)
        // Too few usable languages: fall back to the defaults rather than a one-button app.
        assertEquals(LanguageSelection.defaults.codes, LanguageSelection.parse("xx,yy").codes)
    }

    @Test
    fun `english and the last pair can't be removed`() {
        assertFalse(LanguageSelection.defaults.canRemove("en"))
        val pair = LanguageSelection.parse("en,fa")
        assertFalse(pair.canRemove("fa"))
        assertTrue(LanguageSelection.parse("en,fa,de").canRemove("fa"))
        assertFalse(LanguageSelection.defaults.canRemove("it")) // not in the list
    }

    @Test
    fun `addable lists catalog languages not yet chosen, alphabetically`() {
        val addable = LanguageSelection.defaults.addable
        assertTrue(addable.none { it.code in LanguageSelection.defaults.codes })
        assertEquals(addable.map { it.label }.sorted(), addable.map { it.label })
        assertTrue(addable.any { it.code == "it" })
        assertFalse(LanguageSelection.defaults.remove("ar").addable.none { it.code == "ar" })
    }

    @Test
    fun `adding twice is harmless`() {
        val once = LanguageSelection.defaults.add("it")
        assertEquals(once.codes, once.add("it").codes)
    }

    @Test
    fun `catalog aliases cover three-letter codes of added languages`() {
        assertEquals("it", Languages.normalizeCode("ita"))
        assertEquals("nl", Languages.normalizeCode("dut"))
        assertEquals("nl", Languages.normalizeCode("nld"))
        assertEquals("fa", Languages.normalizeCode("fas"))
    }
}
