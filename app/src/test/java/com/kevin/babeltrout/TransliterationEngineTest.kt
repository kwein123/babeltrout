package com.kevin.babeltrout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransliterationEngineTest {

    private fun fa(text: String) = TransliterationEngine.persianToLatin(text)

    @Test
    fun `persian lexicon words read naturally`() {
        assertEquals("salaam", fa("سلام"))
        assertEquals("mamnoon", fa("ممنون"))
        assertEquals("khodaahaafez", fa("خداحافظ"))
        assertEquals("salaam, chetori?", fa("سلام، چطوری؟"))
    }

    @Test
    fun `persian lexicon tolerates arabic letter variants`() {
        // Arabic yeh/kaf (ي ك) are common in machine output; they must normalize to Persian forms.
        assertEquals("kheyli", fa("خيلي"))
        assertEquals("ketaab", fa("كتاب"))
    }

    @Test
    fun `persian rules recover long vowels`() {
        assertEquals("miz", fa("میز"))            // medial yeh -> i
        assertEquals("biyaa", fa("بیا"))          // yeh before alef -> iy
        assertEquals("paayin", fa("پایین"))
        assertEquals("kooh", fa("کوه"))           // vav between consonants -> oo, heh after vowel -> h
        assertEquals("istgaah", fa("ایستگاه"))    // initial alef-yeh -> i
        assertEquals("daanshgaah", fa("دانشگاه"))
    }

    @Test
    fun `persian final heh is the -e ending`() {
        assertEquals("ktaabkhaane", fa("کتابخانه"))
    }

    @Test
    fun `persian silent vav after khe`() {
        assertEquals("khaastn", fa("خواستن"))
        assertEquals("khaahar", fa("خواهر"))
    }

    @Test
    fun `persian zwnj joins prefix with a hyphen`() {
        assertEquals("mikhaaham", fa("می\u200Cخواهم"))           // lexicon
        // Rules only (no lexicon entry); the true reading is "miravam".
        assertEquals("mi-room",fa("می\u200Cروم"))                // rules: short vowels are unwritten
    }

    @Test
    fun `persian digits and punctuation become ascii`() {
        assertEquals("123?", fa("۱۲۳؟"))
    }

    @Test
    fun `persian output is ascii`() {
        val out = fa("امروز هوا خیلی خوب است و من به دانشگاه می\u200Cروم")
        assertTrue(out, out.all { it.code < 128 })
    }

    @Test
    fun `arabic keeps the generic mapping`() {
        assertEquals("mrhba", TransliterationEngine.arabicScriptToLatin("مرحبا"))
        assertEquals("la", TransliterationEngine.arabicScriptToLatin("لا"))
    }

    @Test
    fun `hindi drops the final inherent vowel`() {
        assertEquals("namaste", TransliterationEngine.devanagariToLatin("नमस्ते"))
        assertEquals("dhanyavaad", TransliterationEngine.devanagariToLatin("धन्यवाद"))
        assertEquals("aap kaise hain?", TransliterationEngine.devanagariToLatin("आप कैसे हैं?"))
        assertEquals("hindee", TransliterationEngine.devanagariToLatin("हिंदी"))
    }

    @Test
    fun `hindi nukta and danda`() {
        assertEquals("zaroorat.", TransliterationEngine.devanagariToLatin("ज़रूरत।"))      // decomposed nukta
        assertEquals("zaroorat", TransliterationEngine.devanagariToLatin("ज़रूरत"))  // precomposed
    }

    @Test
    fun `latin to devanagari builds conjuncts and matras`() {
        assertEquals("नमस्ते", TransliterationEngine.latinToScript("namaste", "hi"))
        assertEquals("आप", TransliterationEngine.latinToScript("aap", "hi"))
    }

    @Test
    fun `cyrillic round trips to latin`() {
        assertEquals("Pryvit", TransliterationEngine.cyrillicToLatin("Привіт", "uk"))
        assertEquals("Privet", TransliterationEngine.cyrillicToLatin("Привет", "ru"))
    }

    @Test
    fun `latin to other scripts`() {
        assertEquals("سلام", TransliterationEngine.latinToScript("slaam", "fa"))
        assertEquals("салам", TransliterationEngine.latinToScript("salaam", "ru"))
        assertEquals("hello", TransliterationEngine.latinToScript("hello", "en"))
    }

    @Test
    fun `toLatin dispatches by language`() {
        assertEquals("salaam", TransliterationEngine.toLatin("سلام", "fa-IR"))
        assertEquals("namaste", TransliterationEngine.toLatin("नमस्ते", "hi"))
        assertEquals("hello", TransliterationEngine.toLatin("  hello  ", "en"))
        assertFalse(TransliterationEngine.toLatin("Привіт", "uk").any { it in 'Ѐ'..'ӿ' })
    }
}
