package com.kevin.babeltrout

/**
 * The user's own languages: which push-to-talk buttons exist, in what order, and which languages the
 * spinners and asset downloads cover. Stored as a comma-separated code list; pure Kotlin so the rules
 * are unit-tested.
 *
 * Rules: English (the translation pivot) always stays, and at least [MIN_SIZE] languages remain so
 * conversation mode still has a pair.
 */
class LanguageSelection private constructor(val codes: List<String>) {

    val options: List<LanguageOption> get() = codes.mapNotNull { Languages.option(it) }

    /** Catalog languages the user can still add, alphabetical. */
    val addable: List<LanguageOption> get() = Languages.catalog.filter { it.code !in codes }.sortedBy { it.label }

    fun canRemove(code: String): Boolean {
        val normalized = Languages.normalizeCode(code)
        return normalized in codes && normalized != Languages.PIVOT_CODE && codes.size > MIN_SIZE
    }

    fun remove(code: String): LanguageSelection {
        require(canRemove(code)) { "Can't remove $code" }
        return LanguageSelection(codes - Languages.normalizeCode(code))
    }

    /** Appends [code] as the last button. */
    fun add(code: String): LanguageSelection {
        val normalized = Languages.normalizeCode(code)
        require(normalized in Languages.codes) { "Unknown language $code" }
        return if (normalized in codes) this else LanguageSelection(codes + normalized)
    }

    fun serialize(): String = codes.joinToString(",")

    companion object {
        const val MIN_SIZE = 2

        val defaults = LanguageSelection(Languages.defaultCodes)

        /** Reads a stored list, dropping unknown or duplicate codes; null or unusable input gives [defaults]. */
        fun parse(stored: String?): LanguageSelection {
            if (stored.isNullOrBlank()) return defaults
            val codes = stored.split(",")
                .map { Languages.normalizeCode(it.trim()) }
                .filter { it in Languages.codes }
                .distinct()
                .toMutableList()
            if (Languages.PIVOT_CODE !in codes) codes.add(0, Languages.PIVOT_CODE)
            return if (codes.size >= MIN_SIZE) LanguageSelection(codes) else defaults
        }
    }
}
