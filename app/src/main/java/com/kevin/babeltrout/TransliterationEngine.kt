package com.kevin.babeltrout

import java.util.Locale

/**
 * Rule-based "how do I say it" transliteration between Latin, Arabic-script, Cyrillic and
 * Devanagari text. It is intentionally approximate: the goal is a readable pronunciation
 * hint, not a scholarly romanization.
 *
 * Every conversion goes through a Latin intermediate form ([toLatin] then [latinToScript]).
 *
 * Persian is the hard case because short vowels are not written. [persianToLatin] uses a
 * lexicon of common conversational words, then positional rules for alef / vav / yeh / heh
 * that recover long vowels and the most common final "-e".
 */
object TransliterationEngine {

    // ---------------------------------------------------------------------------------------
    // Arabic script -> Latin
    // ---------------------------------------------------------------------------------------

    private val arabicToLatin = mapOf(
        'ا' to "a", 'آ' to "aa", 'أ' to "a", 'إ' to "e", 'ٱ' to "a", 'ء' to "", 'ؤ' to "u", 'ئ' to "y",
        'ب' to "b", 'پ' to "p", 'ت' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch", 'ح' to "h", 'خ' to "kh",
        'د' to "d", 'ذ' to "z", 'ر' to "r", 'ز' to "z", 'ژ' to "zh", 'س' to "s", 'ش' to "sh", 'ص' to "s",
        'ض' to "z", 'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ك' to "k",
        'ک' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'ة' to "a", 'و' to "u",
        'ي' to "y", 'ی' to "y", 'ى' to "a",
        '\u064E' to "a", '\u0650' to "i", '\u064F' to "u", '\u064B' to "an", '\u064D' to "in", '\u064C' to "un",
        '\u0652' to "", '\u0651' to "", '\u200C' to "", 'ـ' to "",
    )

    private val arabicPunctuation = mapOf('،' to ",", '؟' to "?", '؛' to ";", '٪' to "%")

    private fun asciiDigitOrNull(ch: Char): Char? = when (ch) {
        in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
        in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
        in '\u0966'..'\u096F' -> '0' + (ch - '\u0966')
        else -> null
    }

    /** Generic Arabic-script romanization (used for Arabic). */
    fun arabicScriptToLatin(text: String): String {
        val normalized = text.replace("ﻻ", "لا")
        val out = StringBuilder()
        var index = 0
        while (index < normalized.length) {
            if (normalized.startsWith("لا", index)) {
                out.append("la")
                index += 2
                continue
            }
            val ch = normalized[index]
            out.append(asciiDigitOrNull(ch) ?: arabicPunctuation[ch] ?: arabicToLatin[ch] ?: ch)
            index += 1
        }
        return collapseSpaces(out.toString())
    }

    // ---------------------------------------------------------------------------------------
    // Persian -> Latin ("Finglish" style, ASCII only so it round-trips through latinToScript)
    // ---------------------------------------------------------------------------------------

    /** Common conversational words whose short vowels can't be recovered by rules. */
    private val persianLexicon = mapOf(
        "سلام" to "salaam", "خوبی" to "khoobi", "خوبم" to "khoobam", "خوب" to "khoob",
        "ممنون" to "mamnoon", "ممنونم" to "mamnoonam", "مرسی" to "mersi", "متشکرم" to "motshakeram",
        "بله" to "bale", "نه" to "na", "آره" to "aare", "خداحافظ" to "khodaahaafez",
        "لطفا" to "lotfan", "لطفاً" to "lotfan", "چطوری" to "chetori", "چطور" to "chetor",
        "حال" to "haal", "حالت" to "haalet", "شما" to "shomaa", "من" to "man", "تو" to "to",
        "او" to "oo", "ما" to "maa", "آنها" to "aanhaa", "اسم" to "esm", "اسمم" to "esmam",
        "اسمت" to "esmet", "است" to "ast", "هست" to "hast", "هستم" to "hastam", "هستی" to "hasti",
        "نیست" to "nist", "و" to "va", "با" to "baa", "به" to "be", "از" to "az", "در" to "dar",
        "که" to "ke", "این" to "in", "آن" to "aan", "چه" to "che", "کجا" to "kojaa",
        "کجاست" to "kojaast", "چرا" to "cheraa", "چی" to "chi", "خیلی" to "kheyli", "آب" to "aab",
        "غذا" to "ghazaa", "نان" to "naan", "کمک" to "komak", "دکتر" to "doktor",
        "بیمارستان" to "bimaarestaan", "ایران" to "iraan", "فارسی" to "faarsi",
        "انگلیسی" to "engelisi", "امروز" to "emrooz", "فردا" to "fardaa", "دیروز" to "dirooz",
        "روز" to "rooz", "شب" to "shab", "صبح" to "sobh", "بخیر" to "bekheyr", "خانه" to "khaane",
        "پول" to "pool", "آقا" to "aaghaa", "خانم" to "khaanom", "دوست" to "doost",
        "ببخشید" to "bebakhshid", "چقدر" to "cheghadr", "بسیار" to "besyaar",
        "خوشحالم" to "khoshhaalam", "خوش" to "khosh", "آمدید" to "aamadid", "اینجا" to "injaa",
        "آنجا" to "aanjaa", "کار" to "kaar", "وقت" to "vaght", "ساعت" to "saa'at", "یک" to "yek",
        "دو" to "do", "سه" to "se", "چهار" to "chahaar", "پنج" to "panj", "شش" to "shesh",
        "هفت" to "haft", "هشت" to "hasht", "ده" to "dah", "مادر" to "maadar",
        "پدر" to "pedar", "برادر" to "baraadar", "خواهر" to "khaahar", "بچه" to "bachche",
        "دارم" to "daaram", "داری" to "daari", "دارد" to "daarad", "ندارم" to "nadaaram",
        "می\u200Cخواهم" to "mikhaaham", "میخواهم" to "mikhaaham", "می\u200Cخوام" to "mikhaam",
        "نمی\u200Cدانم" to "nemidaanam", "نمیدونم" to "nemidoonam", "می\u200Cدانم" to "midaanam",
        "بفرمایید" to "befarmaayid", "عزیزم" to "azizam", "جان" to "jaan", "باشه" to "baashe",
        "کتاب" to "ketaab", "زبان" to "zabaan", "حرف" to "harf", "بزنید" to "bezanid",
        "آهسته" to "aahesteh", "دوباره" to "dobaare", "بگویید" to "begooyid", "بگو" to "begoo",
    )

    private val persianConsonants = mapOf(
        'ب' to "b", 'پ' to "p", 'ت' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch", 'ح' to "h",
        'خ' to "kh", 'د' to "d", 'ذ' to "z", 'ر' to "r", 'ز' to "z", 'ژ' to "zh", 'س' to "s",
        'ش' to "sh", 'ص' to "s", 'ض' to "z", 'ط' to "t", 'ظ' to "z", 'غ' to "gh", 'ف' to "f",
        'ق' to "gh", 'ک' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n",
    )

    private const val ZWNJ = '\u200C'

    private fun normalizePersian(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                'ي', 'ى' -> append('ی')
                'ك' -> append('ک')
                'ة' -> append('ه')
                'ۀ' -> append("هٔ")
                'ـ' -> Unit // tatweel
                else -> append(ch)
            }
        }
    }

    private fun isPersianLetter(ch: Char): Boolean =
        ch in persianConsonants || ch in "اآأإوؤیئهعءة"

    fun persianToLatin(text: String): String {
        val normalized = normalizePersian(text)
        val out = StringBuilder()
        var index = 0
        while (index < normalized.length) {
            val ch = normalized[index]
            if (isPersianLetter(ch) || ch == ZWNJ) {
                // Whole word, including ZWNJ-joined parts such as می\u200Cخواهم.
                var end = index
                while (end < normalized.length && (isPersianLetter(normalized[end]) || normalized[end] == ZWNJ || normalized[end] in '\u064B'..'\u0655')) {
                    end++
                }
                out.append(persianWordToLatin(normalized.substring(index, end)))
                index = end
                continue
            }
            out.append(asciiDigitOrNull(ch) ?: arabicPunctuation[ch] ?: ch)
            index++
        }
        return collapseSpaces(out.toString())
    }

    private fun persianWordToLatin(word: String): String {
        persianLexicon[word]?.let { return it }
        persianLexicon[word.replace(ZWNJ.toString(), "")]?.let { return it }
        return word.split(ZWNJ).filter { it.isNotEmpty() }.joinToString("-") { part ->
            persianLexicon[part] ?: persianPartToLatin(part)
        }
    }

    private fun persianPartToLatin(word: String): String {
        val out = StringBuilder()
        // Letters only (diacritics handled inline) so positional rules can look at neighbours.
        var i = 0
        fun prevLetter(): Char? {
            var j = i - 1
            while (j >= 0 && word[j] in '\u064B'..'\u0655') j--
            return if (j >= 0) word[j] else null
        }
        fun nextLetter(offset: Int = 1): Char? {
            var j = i + 1
            var seen = 0
            while (j < word.length) {
                if (word[j] !in '\u064B'..'\u0655') {
                    seen++
                    if (seen == offset) return word[j]
                }
                j++
            }
            return null
        }
        fun isVowelLetter(c: Char?): Boolean = c != null && c in "اآو"

        while (i < word.length) {
            val ch = word[i]
            val atStart = prevLetter() == null
            val atEnd = nextLetter() == null
            when (ch) {
                'آ' -> out.append("aa")
                'ا', 'أ', 'إ', 'ٱ' -> when {
                    atStart && nextLetter() == 'ی' -> { out.append("i"); i++ }
                    atStart && nextLetter() == 'و' -> { out.append("oo"); i++ }
                    atStart -> out.append(if (ch == 'إ') "e" else "a")
                    else -> out.append("aa")
                }
                'و' -> when {
                    // Silent vav in خوا (khaahar, khaastan).
                    prevLetter() == 'خ' && nextLetter() == 'ا' -> Unit
                    atStart -> out.append("v")
                    isVowelLetter(prevLetter()) -> out.append("v")
                    nextLetter() == 'ا' -> out.append("v")
                    else -> out.append("oo")
                }
                'ی', 'ئ' -> when {
                    atStart -> out.append("y")
                    isVowelLetter(prevLetter()) && !atEnd -> out.append("y")
                    isVowelLetter(prevLetter()) && atEnd -> out.append("yi")
                    nextLetter() == 'ا' || nextLetter() == 'و' -> out.append("iy")
                    else -> out.append("i")
                }
                'ه' -> when {
                    // Final silent heh after a consonant is the "-e" ending (khaane, bachche).
                    atEnd && !atStart && !isVowelLetter(prevLetter()) -> out.append("e")
                    else -> out.append("h")
                }
                'ع' -> out.append(if (atStart) "a" else "'")
                'ء', 'ؤ' -> out.append("'")
                '\u064E' -> out.append("a")
                '\u0650' -> out.append("e")
                '\u064F' -> out.append("o")
                '\u064B' -> out.append("an")
                '\u0651', '\u0652', '\u0654' -> Unit
                else -> out.append(persianConsonants[ch] ?: ch)
            }
            i++
        }
        return out.toString()
    }

    // ---------------------------------------------------------------------------------------
    // Devanagari (Hindi) -> Latin
    // ---------------------------------------------------------------------------------------

    private val devaConsonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        // Precomposed nukta letters.
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "gh", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f", '\u095F' to "y",
    )

    private val devaNukta = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")

    private val devaIndependentVowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )

    private val devaMatras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", '\u0941' to "u", '\u0942' to "oo", '\u0943' to "ri",
        '\u0947' to "e", '\u0948' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o",
    )

    private const val VIRAMA = '\u094D'
    private const val NUKTA = '\u093C'

    private fun isDevanagariLetterOrMark(ch: Char?): Boolean = ch != null && ch in '\u0900'..'\u0963'

    fun devanagariToLatin(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val consonant = devaConsonants[ch]
            if (consonant != null) {
                var roman = consonant
                var j = i + 1
                if (j < text.length && text[j] == NUKTA) {
                    roman = devaNukta[ch] ?: consonant
                    j++
                }
                out.append(roman)
                val next = text.getOrNull(j)
                when {
                    next == VIRAMA -> j++
                    next != null && next in devaMatras -> { out.append(devaMatras.getValue(next)); j++ }
                    // Hindi drops the inherent "a" at the end of a word (राम = raam, not raama).
                    !isDevanagariLetterOrMark(next) -> Unit
                    else -> out.append("a")
                }
                i = j
                continue
            }
            when {
                ch in devaIndependentVowels -> out.append(devaIndependentVowels.getValue(ch))
                ch == '\u0902' || ch == '\u0901' -> out.append("n")
                ch == 'ः' -> out.append("h")
                ch == '।' || ch == '॥' -> out.append(".")
                ch == NUKTA || ch == VIRAMA -> Unit
                else -> out.append(asciiDigitOrNull(ch) ?: ch)
            }
            i++
        }
        return collapseSpaces(out.toString())
    }

    // ---------------------------------------------------------------------------------------
    // Cyrillic -> Latin
    // ---------------------------------------------------------------------------------------

    private val ukCyrillicToLatin = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'ґ' to "g", 'д' to "d", 'е' to "e", 'є' to "ye",
        'ж' to "zh", 'з' to "z", 'и' to "y", 'і' to "i", 'ї' to "yi", 'й' to "y", 'к' to "k", 'л' to "l",
        'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ь' to "",
        'ю' to "yu", 'я' to "ya", '\'' to "", 'ʼ' to "",
    )

    private val ruCyrillicToLatin = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya",
    )

    fun cyrillicToLatin(text: String, sourceScriptCode: String): String {
        val mapping = when (sourceScriptCode) {
            "uk" -> ukCyrillicToLatin
            "ru" -> ruCyrillicToLatin
            else -> return collapseSpaces(text)
        }
        val out = StringBuilder()
        text.forEach { ch ->
            val mapped = mapping[ch.lowercaseChar()]
            when {
                mapped == null -> out.append(ch)
                ch.isUpperCase() && mapped.isNotBlank() -> out.append(mapped.replaceFirstChar { it.uppercase(Locale.US) })
                else -> out.append(mapped)
            }
        }
        return collapseSpaces(out.toString())
    }

    // ---------------------------------------------------------------------------------------
    // Any supported language -> Latin
    // ---------------------------------------------------------------------------------------

    fun toLatin(text: String, languageCode: String): String = when (val code = Languages.normalizeCode(languageCode)) {
        "fa" -> persianToLatin(text)
        "ar" -> arabicScriptToLatin(text)
        "uk", "ru" -> cyrillicToLatin(text, code)
        "hi" -> devanagariToLatin(text)
        else -> collapseSpaces(text)
    }

    // ---------------------------------------------------------------------------------------
    // Latin -> target script
    // ---------------------------------------------------------------------------------------

    private val ukDigraphs = listOf(
        "shch" to "щ", "zh" to "ж", "kh" to "х", "ch" to "ч", "sh" to "ш", "ts" to "ц",
        "ya" to "я", "yu" to "ю", "yo" to "йо", "ye" to "є", "yi" to "ї", "ia" to "я", "iu" to "ю", "ie" to "є",
        "aa" to "а", "oo" to "у", "ee" to "і",
    )

    private val ukSingle = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "ґ", 'h' to "г",
        'i' to "і", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п",
        'q' to "к", 'r' to "р", 's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс",
        'y' to "й", 'z' to "з", '\'' to "",
    )

    private val ruDigraphs = listOf(
        "shch" to "щ", "zh" to "ж", "kh" to "х", "ch" to "ч", "sh" to "ш", "ts" to "ц",
        "ya" to "я", "yu" to "ю", "yo" to "ё", "ye" to "е", "yi" to "и", "ia" to "я", "iu" to "ю", "ie" to "е",
        "aa" to "а", "oo" to "у", "ee" to "и",
    )

    private val ruSingle = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г", 'h' to "х",
        'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п",
        'q' to "к", 'r' to "р", 's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс",
        'y' to "й", 'z' to "з", '\'' to "",
    )

    private val faDigraphs = listOf(
        "sh" to "ش", "kh" to "خ", "gh" to "غ", "ch" to "چ", "zh" to "ژ", "th" to "ث", "dh" to "ذ",
        "aa" to "ا", "ee" to "ی", "oo" to "و", "ou" to "و",
    )

    private val faSingle = mapOf(
        'a' to "ا", 'b' to "ب", 'c' to "ک", 'd' to "د", 'e' to "ی", 'f' to "ف", 'g' to "گ", 'h' to "ه",
        'i' to "ی", 'j' to "ج", 'k' to "ک", 'l' to "ل", 'm' to "م", 'n' to "ن", 'o' to "و", 'p' to "پ",
        'q' to "ق", 'r' to "ر", 's' to "س", 't' to "ت", 'u' to "و", 'v' to "و", 'w' to "و", 'x' to "کس",
        'y' to "ی", 'z' to "ز", '\'' to "",
    )

    private val arDigraphs = listOf(
        "sh" to "ش", "kh" to "خ", "gh" to "غ", "ch" to "تش", "zh" to "ج", "th" to "ث", "dh" to "ذ",
        "aa" to "ا", "ee" to "ي", "oo" to "و", "ou" to "و",
    )

    private val arSingle = mapOf(
        'a' to "ا", 'b' to "ب", 'c' to "ك", 'd' to "د", 'e' to "ي", 'f' to "ف", 'g' to "ج", 'h' to "ه",
        'i' to "ي", 'j' to "ج", 'k' to "ك", 'l' to "ل", 'm' to "م", 'n' to "ن", 'o' to "و", 'p' to "ب",
        'q' to "ق", 'r' to "ر", 's' to "س", 't' to "ت", 'u' to "و", 'v' to "و", 'w' to "و", 'x' to "كس",
        'y' to "ي", 'z' to "ز", '\'' to "",
    )

    fun latinToScript(latinText: String, targetScriptCode: String): String {
        val normalized = collapseSpaces(latinText)
        return when (Languages.normalizeCode(targetScriptCode)) {
            "uk" -> transliterateWithMaps(normalized, ukDigraphs, ukSingle)
            "ru" -> transliterateWithMaps(normalized, ruDigraphs, ruSingle)
            "fa" -> transliterateWithMaps(normalized, faDigraphs, faSingle)
            "ar" -> transliterateWithMaps(normalized, arDigraphs, arSingle)
            "hi" -> latinToDevanagari(normalized)
            else -> normalized
        }
    }

    private val latinToDevaConsonants = listOf(
        "chh" to "छ", "kh" to "ख", "gh" to "घ", "ch" to "च", "jh" to "झ", "th" to "थ", "dh" to "ध",
        "ph" to "फ", "bh" to "भ", "sh" to "श", "ng" to "ङ", "ny" to "ञ", "zh" to "झ",
        "k" to "क", "g" to "ग", "c" to "क", "j" to "ज", "t" to "त", "d" to "द", "n" to "न", "p" to "प",
        "b" to "ब", "m" to "म", "y" to "य", "r" to "र", "l" to "ल", "v" to "व", "w" to "व", "s" to "स",
        "h" to "ह", "f" to "\u095E", "z" to "\u095B", "q" to "\u0958", "x" to "क्स",
    )

    // Latin vowel -> (independent vowel, dependent sign). The inherent "a" has no sign.
    private val latinToDevaVowels = listOf(
        "aa" to ("आ" to "ा"), "ai" to ("ऐ" to "ै"), "au" to ("औ" to "ौ"), "ee" to ("ई" to "ी"),
        "ii" to ("ई" to "ी"), "oo" to ("ऊ" to "ू"), "uu" to ("ऊ" to "ू"),
        "a" to ("अ" to ""), "i" to ("इ" to "ि"), "u" to ("उ" to "ु"), "e" to ("ए" to "े"), "o" to ("ओ" to "ो"),
    )

    private fun latinToDevanagari(text: String): String {
        val lower = text.lowercase(Locale.US)
        val out = StringBuilder()
        var i = 0
        var afterConsonant = false
        while (i < lower.length) {
            val vowel = latinToDevaVowels.firstOrNull { lower.startsWith(it.first, i) }
            if (vowel != null) {
                val (independent, sign) = vowel.second
                out.append(if (afterConsonant) sign else independent)
                afterConsonant = false
                i += vowel.first.length
                continue
            }
            val consonant = latinToDevaConsonants.firstOrNull { lower.startsWith(it.first, i) }
            if (consonant != null) {
                if (afterConsonant) out.append(VIRAMA) // consonant cluster
                out.append(consonant.second)
                afterConsonant = true
                i += consonant.first.length
                continue
            }
            // Word boundary or punctuation: a trailing consonant keeps its (silent) inherent vowel.
            afterConsonant = false
            out.append(if (lower[i] == '\'') "" else text[i].toString())
            i++
        }
        return collapseSpaces(out.toString())
    }

    private fun transliterateWithMaps(
        text: String,
        digraphs: List<Pair<String, String>>,
        single: Map<Char, String>,
    ): String {
        val lower = text.lowercase(Locale.US)
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            val digraph = digraphs.firstOrNull { lower.startsWith(it.first, index) }
            if (digraph != null) {
                out.append(digraph.second)
                index += digraph.first.length
                continue
            }
            out.append(single[lower[index]] ?: text[index])
            index += 1
        }
        return collapseSpaces(out.toString())
    }

    private fun collapseSpaces(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}
