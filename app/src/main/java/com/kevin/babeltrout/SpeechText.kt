package com.kevin.babeltrout

/** Pure helpers for feeding long text to Android TextToSpeech. */
object SpeechText {

    private val sentenceEnd = Regex("(?<=[.!?؟।\\n])\\s+")
    private val clauseEnd = Regex("(?<=[,،;؛:])\\s+")

    /**
     * Splits [text] into pieces no longer than [maxLength] (TextToSpeech.getMaxSpeechInputLength()
     * is 4000 on most devices), preferring sentence, then clause, then word boundaries.
     */
    fun chunk(text: String, maxLength: Int): List<String> {
        require(maxLength > 0)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxLength) return listOf(trimmed)

        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) out += current.toString().trim()
            current.clear()
        }
        fun add(piece: String) {
            if (current.isNotEmpty() && current.length + 1 + piece.length > maxLength) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }

        for (sentence in trimmed.split(sentenceEnd)) {
            if (sentence.length <= maxLength) { add(sentence); continue }
            for (clause in sentence.split(clauseEnd)) {
                if (clause.length <= maxLength) { add(clause); continue }
                for (word in clause.split(Regex("\\s+"))) {
                    if (word.length <= maxLength) add(word)
                    else word.chunked(maxLength).forEach(::add)
                }
            }
        }
        flush()
        return out
    }

    /**
     * How long to wait for TTS to finish before giving up. The old fixed 15 s cut off long
     * conversation-mode translations mid-sentence; this scales with length and speech rate.
     */
    fun speakTimeoutMillis(text: String, speechRate: Float): Long {
        val rate = speechRate.coerceIn(0.25f, 4f)
        val estimated = (text.length * 110L / rate).toLong()
        return (estimated + 8_000L).coerceIn(15_000L, 300_000L)
    }
}
