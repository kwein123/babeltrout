package com.kevin.babeltrout

import java.util.Locale

/** Writing systems the transliteration engine knows how to read and write. */
enum class Script { LATIN, ARABIC, CYRILLIC, DEVANAGARI }

data class LanguageOption(
    val code: String,
    val label: String,
    val localeTag: String,
    val script: Script,
    /** Short phrase used for voice previews and TTS probes. */
    val sampleText: String,
)

/**
 * Single source of truth for the languages Babeltrout supports.
 *
 * Adding a language here (plus a hold button in activity_main.xml and, for a new
 * script, rules in [TransliterationEngine]) is all that is needed; translation
 * model downloads, spinners, voice checks and language detection derive from this list.
 */
object Languages {

    val all: List<LanguageOption> = listOf(
        LanguageOption("uk", "Ukrainian", "uk-UA", Script.CYRILLIC, "Привіт, це перевірка голосу."),
        LanguageOption("ru", "Russian", "ru-RU", Script.CYRILLIC, "Привет, это проверка голоса."),
        LanguageOption("fa", "Farsi", "fa-IR", Script.ARABIC, "سلام، این یک آزمایش صدا است."),
        LanguageOption("ar", "Arabic", "ar-SA", Script.ARABIC, "مرحبا، هذا اختبار للصوت."),
        LanguageOption("hi", "Hindi", "hi-IN", Script.DEVANAGARI, "नमस्ते, यह आवाज़ का परीक्षण है।"),
        LanguageOption("fr", "French", "fr-FR", Script.LATIN, "Bonjour, ceci est un test de voix."),
        LanguageOption("es", "Spanish", "es-ES", Script.LATIN, "Hola, esta es una prueba de voz."),
        LanguageOption("en", "English", "en-US", Script.LATIN, "Hello, this is a voice test."),
    )

    val codes: Set<String> = all.map { it.code }.toSet()

    private val byCode = all.associateBy { it.code }

    // TTS engines (SherpaTTS in particular) often report ISO 639-2/3 codes such as "fas".
    private val iso3Aliases = mapOf(
        "fas" to "fa", "per" to "fa", "pes" to "fa", "prs" to "fa",
        "eng" to "en",
        "ukr" to "uk",
        "rus" to "ru",
        "ara" to "ar", "arb" to "ar",
        "fra" to "fr", "fre" to "fr",
        "spa" to "es",
        "hin" to "hi",
    )

    fun option(code: String): LanguageOption? = byCode[normalizeCode(code)]

    fun label(code: String): String = option(code)?.label ?: code

    fun localeTag(code: String): String = option(code)?.localeTag ?: "en-US"

    fun script(code: String): Script = option(code)?.script ?: Script.LATIN

    fun isRtl(code: String): Boolean = script(code) == Script.ARABIC

    /** "fa-IR", "FA", "fas", "fa_IR" -> "fa". Blank or null -> "". */
    fun normalizeCode(code: String?): String {
        if (code.isNullOrBlank()) {
            return ""
        }
        val base = code.lowercase(Locale.US).substringBefore('-').substringBefore('_')
        return iso3Aliases[base] ?: base
    }
}
