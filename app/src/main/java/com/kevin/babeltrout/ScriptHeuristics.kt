package com.kevin.babeltrout

import java.util.Locale

/**
 * Cheap, deterministic language hints based on script and marker characters/words.
 *
 * These complement ML Kit language ID, which is weak on very short utterances
 * ("merci", "salaam") and cannot tell Farsi from Arabic or Ukrainian from Russian
 * reliably on a single word. Everything here is pure Kotlin so it is unit-tested.
 */
object ScriptHeuristics {

    fun containsArabicScript(text: String): Boolean = text.any {
        it in '\u0600'..'\u06FF' ||   // Arabic
            it in '\u0750'..'\u077F' || // Arabic Supplement
            it in '\uFB50'..'\uFDFF' || // Presentation Forms-A
            it in '\uFE70'..'\uFEFF'    // Presentation Forms-B
    }

    fun containsCyrillic(text: String): Boolean = text.any { it in '\u0400'..'\u04FF' }

    fun containsDevanagari(text: String): Boolean = text.any { it in '\u0900'..'\u097F' }

    fun containsNonLatinScript(text: String): Boolean =
        containsArabicScript(text) || containsCyrillic(text) || containsDevanagari(text)

    fun hasScript(text: String, script: Script): Boolean = when (script) {
        Script.ARABIC -> containsArabicScript(text)
        Script.CYRILLIC -> containsCyrillic(text)
        Script.DEVANAGARI -> containsDevanagari(text)
        Script.LATIN -> !containsNonLatinScript(text)
    }

    // Letters that exist in Persian but not in standard Arabic: پ چ ژ گ, Persian kaf (ک),
    // Persian yeh (ی), and the Extended Arabic-Indic (Persian) digits.
    private val farsiMarkers = setOf('گ', 'چ', 'پ', 'ژ', 'ک', 'ی')

    fun looksFarsi(text: String): Boolean =
        text.any { it in farsiMarkers || it in '\u06F0'..'\u06F9' }

    private val ukrainianMarkers = setOf('і', 'ї', 'є', 'ґ', 'І', 'Ї', 'Є', 'Ґ')

    fun looksUkrainian(text: String): Boolean = text.any { it in ukrainianMarkers }

    private val spanishWords = listOf(" el ", " la ", " de ", " que ", " y ", " por ", " para ", " con ", " está ", " es ", " dónde ")
    private val frenchWords = listOf(" le ", " la ", " les ", " des ", " est ", " pour ", " avec ", " une ", " où ", " je ", " vous ")

    // "la", "de" and accented vowels are shared, so count evidence rather than stopping at the first hit.
    private fun spanishEvidence(lowered: String): Int {
        val padded = " ${lowered.replace(Regex("[¿?¡!.,]"), " ")} "
        return spanishWords.count { padded.contains(it) } + lowered.count { it in "ñ¿¡" } * 2 + lowered.count { it in "áíóú" }
    }

    private fun frenchEvidence(lowered: String): Int {
        val padded = " ${lowered.replace(Regex("[?!.,]"), " ")} "
        return frenchWords.count { padded.contains(it) } + lowered.count { it in "àâçèêëîïôùûü" } * 2
    }

    fun looksSpanish(lowered: String): Boolean = spanishEvidence(lowered) > 0

    fun looksFrench(lowered: String): Boolean = frenchEvidence(lowered) > 0

    private val englishMarkers = listOf(
        " the ", " and ", " is ", " are ", " to ", " of ",
        " hello ", " hi ", " yes ", " no ", " please ", " thanks ",
        " thank ", " good ", " morning ", " evening ", " okay ", " ok ",
        " how ", " what ", " where ", " why ", " who ", " when ",
    )

    fun looksEnglish(lowered: String): Boolean {
        val padded = " $lowered "
        return englishMarkers.any { padded.contains(it) }
    }

    /** 0 = no evidence, higher = stronger evidence that [transcript] is in [code]. */
    fun scoreLanguage(transcript: String, code: String): Int {
        val lowered = transcript.lowercase(Locale.US)
        return when (Languages.normalizeCode(code)) {
            "fa" -> when {
                containsArabicScript(transcript) && looksFarsi(transcript) -> 3
                containsArabicScript(transcript) -> 1
                else -> 0
            }

            "ar" -> when {
                containsArabicScript(transcript) && !looksFarsi(transcript) -> 3
                containsArabicScript(transcript) -> 1
                else -> 0
            }

            "uk" -> when {
                looksUkrainian(transcript) -> 3
                containsCyrillic(transcript) -> 1
                else -> 0
            }

            "ru" -> when {
                containsCyrillic(transcript) && !looksUkrainian(transcript) -> 3
                containsCyrillic(transcript) -> 1
                else -> 0
            }

            "hi" -> if (containsDevanagari(transcript)) 3 else 0
            "fr" -> if (looksFrench(lowered)) 2 else 0
            "es" -> if (looksSpanish(lowered)) 2 else 0
            "en" -> if (looksEnglish(lowered)) 2 else 0
            else -> 0
        }
    }

    /** Best guess from script alone, used when ML Kit language ID returns "und" or an unsupported code. */
    fun guessFromScript(transcript: String): String {
        if (containsArabicScript(transcript)) {
            return if (looksFarsi(transcript)) "fa" else "ar"
        }
        if (containsCyrillic(transcript)) {
            return if (looksUkrainian(transcript)) "uk" else "ru"
        }
        if (containsDevanagari(transcript)) {
            return "hi"
        }
        val lowered = transcript.lowercase(Locale.US)
        val spanish = spanishEvidence(lowered)
        val french = frenchEvidence(lowered)
        return when {
            spanish == 0 && french == 0 -> "en"
            french > spanish -> "fr"
            else -> "es"
        }
    }

    /**
     * The recognizer returns up to 5 alternatives. When one of the conversation languages uses a
     * non-Latin script, prefer an alternative actually written in that script.
     */
    fun pickBestTranscriptForPair(candidates: List<String>, codeA: String, codeB: String): String {
        if (candidates.isEmpty()) {
            return ""
        }
        for (code in listOf(codeA, codeB)) {
            val script = Languages.script(code)
            if (script != Script.LATIN) {
                candidates.firstOrNull { hasScript(it, script) }?.let { return it }
            }
        }
        return candidates.first()
    }

    fun scoreAutoCandidate(transcript: String, sourceCode: String, index: Int): Int {
        val script = Languages.script(sourceCode)
        var score = scoreLanguage(transcript, sourceCode) * 4
        score += when {
            script != Script.LATIN -> if (hasScript(transcript, script)) 6 else -2
            else -> if (!containsNonLatinScript(transcript)) 2 else 0
        }
        score += minOf(transcript.count { it.isLetter() } / 6, 4)
        score -= index
        return score
    }

    fun scoreForcedCandidate(transcript: String, sourceCode: String): Int {
        val script = Languages.script(sourceCode)
        var score = scoreLanguage(transcript, sourceCode) * 5
        score += when {
            script != Script.LATIN -> if (hasScript(transcript, script)) 8 else -2
            else -> if (!containsNonLatinScript(transcript)) 2 else -1
        }
        score += minOf(transcript.length / 8, 4)
        return score
    }

    fun pickBestForcedTranscript(candidates: List<String>, sourceCode: String): String =
        candidates.maxByOrNull { scoreForcedCandidate(it, sourceCode) } ?: candidates.first()
}

/**
 * Decides which of the two conversation languages an utterance is in.
 *
 * Stateful: when the evidence is a tie, it assumes speakers alternate turns.
 */
class PairSourceResolver {

    var lastSourceCode: String? = null
        private set
    private var tiePrefersA = true

    fun reset() {
        lastSourceCode = null
        tiePrefersA = true
    }

    /** Step 1: trust the recognizer's own language detection if it is confident or matches the script. */
    fun fromRecognizerHint(transcript: String, codeA: String, codeB: String, hint: String, hintConfident: Boolean): String? {
        val normalizedHint = Languages.normalizeCode(hint)
        if (normalizedHint != codeA && normalizedHint != codeB) {
            return null
        }
        val script = Languages.script(normalizedHint)
        val hintMatchesScript = script != Script.LATIN && ScriptHeuristics.hasScript(transcript, script)
        return if (hintConfident || hintMatchesScript) remember(normalizedHint) else null
    }

    /** Step 2: ML Kit language ID result (already normalized), then script and marker heuristics, then turn-taking. */
    fun resolve(transcript: String, codeA: String, codeB: String, hint: String, mlDetected: String): String {
        if (mlDetected == codeA || mlDetected == codeB) {
            return remember(mlDetected)
        }

        val pair = setOf(codeA, codeB)
        if (ScriptHeuristics.containsArabicScript(transcript)) {
            if (pair == setOf("fa", "ar")) {
                return remember(if (ScriptHeuristics.looksFarsi(transcript)) "fa" else "ar")
            }
            pair.firstOrNull { Languages.script(it) == Script.ARABIC }?.let { return remember(it) }
        }
        if (ScriptHeuristics.containsCyrillic(transcript)) {
            if (pair == setOf("uk", "ru")) {
                return remember(if (ScriptHeuristics.looksUkrainian(transcript)) "uk" else "ru")
            }
            pair.firstOrNull { Languages.script(it) == Script.CYRILLIC }?.let { return remember(it) }
        }
        if (ScriptHeuristics.containsDevanagari(transcript) && "hi" in pair) {
            return remember("hi")
        }

        val scoreA = ScriptHeuristics.scoreLanguage(transcript, codeA)
        val scoreB = ScriptHeuristics.scoreLanguage(transcript, codeB)
        if (scoreA != scoreB) {
            return remember(if (scoreB > scoreA) codeB else codeA)
        }

        val normalizedHint = Languages.normalizeCode(hint)
        if (normalizedHint == codeA || normalizedHint == codeB) {
            return remember(normalizedHint)
        }
        when (lastSourceCode) {
            codeA -> return remember(codeB)
            codeB -> return remember(codeA)
        }
        val chosen = if (tiePrefersA) codeA else codeB
        tiePrefersA = !tiePrefersA
        return remember(chosen)
    }

    private fun remember(code: String): String {
        lastSourceCode = code
        return code
    }
}
