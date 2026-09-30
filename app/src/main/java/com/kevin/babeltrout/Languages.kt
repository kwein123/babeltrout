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
 * Every language Babeltrout knows how to handle ([catalog]) and the starting set of buttons ([defaultCodes]).
 * The user's own list is kept by [LanguageSelection]; buttons, spinners, model downloads and voice checks
 * follow that list, while lookups here (labels, locales, scripts) cover the whole catalog so text in a
 * language the user has since removed still displays correctly.
 *
 * A language in a new script also needs rules in [TransliterationEngine] and [ScriptHeuristics].
 */
object Languages {

    val catalog: List<LanguageOption> = listOf(
        LanguageOption("en", "English", "en-US", Script.LATIN, "Hello, this is a voice test."),
        LanguageOption("fa", "Farsi", "fa-IR", Script.ARABIC, "سلام، این یک آزمایش صدا است."),
        LanguageOption("uk", "Ukrainian", "uk-UA", Script.CYRILLIC, "Привіт, це перевірка голосу."),
        LanguageOption("ru", "Russian", "ru-RU", Script.CYRILLIC, "Привет, это проверка голоса."),
        LanguageOption("ar", "Arabic", "ar-SA", Script.ARABIC, "مرحبا، هذا اختبار للصوت."),
        LanguageOption("fr", "French", "fr-FR", Script.LATIN, "Bonjour, ceci est un test de voix."),
        LanguageOption("es", "Spanish", "es-ES", Script.LATIN, "Hola, esta es una prueba de voz."),
        LanguageOption("hi", "Hindi", "hi-IN", Script.DEVANAGARI, "नमस्ते, यह आवाज़ का परीक्षण है।"),
        LanguageOption("de", "German", "de-DE", Script.LATIN, "Hallo, das ist ein Sprachtest."),
        // Addable languages. All Latin script, so detection and transliteration need no new rules.
        LanguageOption("ca", "Catalan", "ca-ES", Script.LATIN, "Hola, això és una prova de veu."),
        LanguageOption("hr", "Croatian", "hr-HR", Script.LATIN, "Bok, ovo je test glasa."),
        LanguageOption("cs", "Czech", "cs-CZ", Script.LATIN, "Ahoj, toto je test hlasu."),
        LanguageOption("da", "Danish", "da-DK", Script.LATIN, "Hej, dette er en stemmetest."),
        LanguageOption("nl", "Dutch", "nl-NL", Script.LATIN, "Hallo, dit is een stemtest."),
        LanguageOption("fi", "Finnish", "fi-FI", Script.LATIN, "Hei, tämä on äänitesti."),
        LanguageOption("hu", "Hungarian", "hu-HU", Script.LATIN, "Helló, ez egy hangteszt."),
        LanguageOption("id", "Indonesian", "id-ID", Script.LATIN, "Halo, ini adalah tes suara."),
        LanguageOption("it", "Italian", "it-IT", Script.LATIN, "Ciao, questa è una prova della voce."),
        LanguageOption("pl", "Polish", "pl-PL", Script.LATIN, "Cześć, to jest test głosu."),
        LanguageOption("pt", "Portuguese", "pt-BR", Script.LATIN, "Olá, este é um teste de voz."),
        LanguageOption("ro", "Romanian", "ro-RO", Script.LATIN, "Bună, acesta este un test de voce."),
        LanguageOption("sk", "Slovak", "sk-SK", Script.LATIN, "Ahoj, toto je test hlasu."),
        LanguageOption("sv", "Swedish", "sv-SE", Script.LATIN, "Hej, det här är ett rösttest."),
        LanguageOption("tr", "Turkish", "tr-TR", Script.LATIN, "Merhaba, bu bir ses testidir."),
        LanguageOption("vi", "Vietnamese", "vi-VN", Script.LATIN, "Xin chào, đây là bài kiểm tra giọng nói."),
    )

    /** Buttons a new install starts with, in button order. */
    val defaultCodes: List<String> = listOf("en", "fa", "uk", "ru", "ar", "fr", "es", "hi", "de")

    /** Every translation pivots through English, so it can't be removed. */
    const val PIVOT_CODE = "en"

    val codes: Set<String> = catalog.map { it.code }.toSet()

    private val byCode = catalog.associateBy { it.code }

    // TTS engines (SherpaTTS in particular) often report ISO 639-2/3 codes such as "fas". The
    // terminology codes come from Locale; the extras are bibliographic or macrolanguage variants.
    private val iso3Aliases: Map<String, String> =
        catalog.mapNotNull { option ->
            runCatching { Locale.forLanguageTag(option.code).isO3Language }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { it to option.code }
        }.toMap() + mapOf(
            "per" to "fa", "pes" to "fa", "prs" to "fa",
            "arb" to "ar",
            "fre" to "fr",
            "ger" to "de",
            "cze" to "cs", "dut" to "nl", "rum" to "ro", "slo" to "sk",
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
