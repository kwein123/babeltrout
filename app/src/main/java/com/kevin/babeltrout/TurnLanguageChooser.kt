package com.kevin.babeltrout

/**
 * Hands-free conversation recognizes each turn twice, once per conversation language, each recognizer
 * locked to its language. This picks which result is what the speaker actually said.
 *
 * Why not let the recognizer auto-detect? Auto-detection needs a couple of seconds of speech (single
 * words came back "no match") and, fed app-supplied audio, stuck to the first language. A recognizer
 * locked to the wrong language still returns *something* (English words for Ukrainian speech, or
 * Cyrillic spellings of English), so the choice uses several independent signals, strongest first.
 */
object TurnLanguageChooser {

    data class Candidate(
        val languageCode: String,
        val text: String,
        /** Recognizer confidence in 0..1, or null when the recognizer didn't report one. */
        val confidence: Float?,
        /** ML Kit language-ID confidence that [text] is in [languageCode] (0..1), or null if unknown. */
        val textLanguageScore: Float? = null,
    )

    data class Choice(val candidate: Candidate, val reason: String)

    private const val CONFIDENCE_MARGIN = 0.15f
    private const val TEXT_SCORE_MARGIN = 0.2f

    fun choose(candidates: List<Candidate>, lastSourceCode: String?): Choice? {
        val usable = candidates.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return null
        if (usable.size == 1) return Choice(usable.single(), "only result")

        // 1. Script: a Ukrainian/Farsi/Hindi/Arabic recognizer that heard its language answers in its script.
        //    If exactly one candidate is written the way its language is written, it wins.
        val scriptMatches = usable.filter { matchesOwnScript(it) }
        if (scriptMatches.size == 1) return Choice(scriptMatches.single(), "script")
        val pool = scriptMatches.ifEmpty { usable }

        // 2. Recognizer confidence, when both report it and one is clearly higher.
        val withConfidence = pool.filter { it.confidence != null && it.confidence > 0f }
        if (withConfidence.size == pool.size) {
            val sorted = withConfidence.sortedByDescending { it.confidence }
            if (sorted[0].confidence!! - sorted[1].confidence!! >= CONFIDENCE_MARGIN) {
                return Choice(sorted[0], "recognizer confidence")
            }
        }

        // 3. Text-level language ID: does each transcript read like its own language?
        val withTextScore = pool.filter { it.textLanguageScore != null }
        if (withTextScore.size == pool.size) {
            val sorted = withTextScore.sortedByDescending { it.textLanguageScore }
            if (sorted[0].textLanguageScore!! - sorted[1].textLanguageScore!! >= TEXT_SCORE_MARGIN) {
                return Choice(sorted[0], "text language ID")
            }
        }

        // 4. Marker words/letters (the same heuristics the rest of the app uses).
        val scored = pool.map { it to ScriptHeuristics.scoreLanguage(it.text, it.languageCode) }
            .sortedByDescending { it.second }
        if (scored[0].second > scored[1].second) return Choice(scored[0].first, "marker words")

        // 5. Weak confidence lead, then turn-taking: people usually alternate.
        if (withConfidence.size == pool.size) {
            return Choice(withConfidence.maxByOrNull { it.confidence!! }!!, "recognizer confidence (close)")
        }
        pool.firstOrNull { it.languageCode != lastSourceCode }?.let { return Choice(it, "turn-taking") }
        return Choice(pool.first(), "default")
    }

    /** True if the text is written in the script its language uses (Latin languages: no other script). */
    fun matchesOwnScript(candidate: Candidate): Boolean {
        val script = Languages.script(candidate.languageCode)
        val letters = candidate.text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return if (script == Script.LATIN) {
            !ScriptHeuristics.containsNonLatinScript(letters)
        } else {
            // Mostly in the expected script (allows a stray Latin brand name or number).
            val inScript = letters.count { ScriptHeuristics.hasScript(it.toString(), script) }
            inScript * 2 >= letters.length
        }
    }
}
