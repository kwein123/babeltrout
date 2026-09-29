package com.kevin.babeltrout

import com.kevin.babeltrout.TurnLanguageChooser.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLanguageChooserTest {

    private fun choose(vararg c: Candidate, last: String? = null) = TurnLanguageChooser.choose(c.toList(), last)

    @Test
    fun `nothing heard`() {
        assertNull(choose(Candidate("en", "", null), Candidate("uk", " ", null)))
    }

    @Test
    fun `single result wins`() {
        assertEquals("uk", choose(Candidate("en", "", null), Candidate("uk", "Так", null))!!.candidate.languageCode)
    }

    @Test
    fun `ukrainian speech - english recognizer returns junk english, ukrainian returns cyrillic`() {
        val choice = choose(
            Candidate("en", "yak spravy", 0.4f),
            Candidate("uk", "як справи", 0.8f),
            last = "en",
        )!!
        assertEquals("uk", choice.candidate.languageCode)
        // Latin junk is still "English script", so script can't decide here; confidence does.
        assertEquals("recognizer confidence", choice.reason)
    }

    @Test
    fun `ukrainian speech without confidences - text language id decides`() {
        val choice = choose(
            Candidate("en", "yak spravy", null, textLanguageScore = 0.05f),
            Candidate("uk", "як справи", null, textLanguageScore = 0.9f),
            last = "en",
        )!!
        assertEquals("uk", choice.candidate.languageCode)
        assertEquals("text language ID", choice.reason)
    }

    @Test
    fun `wrong-script answer loses on script alone`() {
        // Ukrainian recognizer transcribes English speech as Latin words: not its script.
        val choice = choose(Candidate("en", "see you tomorrow", null), Candidate("uk", "see you tomorrow", null))!!
        assertEquals("en", choice.candidate.languageCode)
        assertEquals("script", choice.reason)
    }

    @Test
    fun `english speech - ukrainian recognizer spells english in cyrillic, english recognizer more confident`() {
        val choice = choose(
            Candidate("en", "how are you", 0.92f),
            Candidate("uk", "хау ар ю", 0.41f),
            last = "uk",
        )!!
        assertEquals("en", choice.candidate.languageCode)
        assertEquals("recognizer confidence", choice.reason)
    }

    @Test
    fun `english speech - no confidences, text language id decides`() {
        val choice = choose(
            Candidate("en", "how are you", null, textLanguageScore = 0.95f),
            Candidate("uk", "хау ар ю", null, textLanguageScore = 0.1f),
        )!!
        assertEquals("en", choice.candidate.languageCode)
        assertEquals("text language ID", choice.reason)
    }

    @Test
    fun `latin pair relies on confidence`() {
        val choice = choose(Candidate("en", "hola amigo", 0.3f), Candidate("es", "hola amigo", 0.9f))!!
        assertEquals("es", choice.candidate.languageCode)
    }

    @Test
    fun `farsi vs arabic uses marker letters when other signals tie`() {
        val choice = choose(Candidate("ar", "چطوری", null), Candidate("fa", "چطوری", null))!!
        assertEquals("fa", choice.candidate.languageCode)
        assertEquals("marker words", choice.reason)
    }

    @Test
    fun `complete tie alternates speakers`() {
        val choice = choose(Candidate("en", "mmm", null), Candidate("fr", "mmm", null), last = "en")!!
        assertEquals("fr", choice.candidate.languageCode)
        assertEquals("turn-taking", choice.reason)
    }

    @Test
    fun `script matching tolerates a stray latin word`() {
        assertTrue(TurnLanguageChooser.matchesOwnScript(Candidate("uk", "я купив iPhone вчора", null)))
        assertFalse(TurnLanguageChooser.matchesOwnScript(Candidate("uk", "how are you", null)))
        assertFalse(TurnLanguageChooser.matchesOwnScript(Candidate("en", "хау ар ю", null)))
        assertFalse(TurnLanguageChooser.matchesOwnScript(Candidate("en", "123", null)))
    }
}
