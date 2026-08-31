package com.example.rhythmtracker.game.arcaea

import java.util.Locale

/**
 * Pure helpers for interpreting the chart marker that Arcaea renders beside MAX RECALL.
 *
 * This deliberately models what is visible on the result screen, not what we think the chart
 * should be. Hidden charts are valid screen states: `?` / `???` means hidden, not OCR failure.
 */
object ArcaeaChartMarker {
    data class DifficultyToken(
        val difficulty: String,
        val displayName: String
    )

    fun parseDifficulty(raw: String): DifficultyToken? {
        val upper = raw.uppercase(Locale.ROOT)
        val compact = upper.filter { it.isLetterOrDigit() }

        return when {
            "INSCRIBED" in compact || compact == "INS" -> DifficultyToken("INS", "INSCRIBED")
            "ETERNAL" in compact || compact == "ETR" -> DifficultyToken("ETR", "ETERNAL")
            "BEYOND" in compact || compact == "BYD" -> DifficultyToken("BYD", "BEYOND")
            "FUTURE" in compact || compact == "FTR" -> DifficultyToken("FTR", "FUTURE")
            "PRESENT" in compact || compact == "PRS" -> DifficultyToken("PRS", "PRESENT")
            "PAST" in compact || compact == "PST" -> DifficultyToken("PST", "PAST")
            else -> null
        }
    }

    /** A difficulty label is considered intentionally hidden only when it is essentially `???`. */
    fun isHiddenDifficulty(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.count { it == '?' || it == '？' } < 2) return false
        return trimmed.all { char ->
            char == '?' || char == '？' || char.isWhitespace() || char in "-_:[]()"
        }
    }

    /**
     * Parse the displayed chart level. Arcaea levels are small one/two digit values with an
     * optional plus. A single question mark is also a valid hidden-level marker.
     */
    fun parseLevel(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed == "?" || trimmed == "？") return "?"

        val match = LEVEL.find(trimmed) ?: return null
        val number = match.groupValues[1].toIntOrNull() ?: return null
        if (number !in 1..12) return null
        return number.toString() + match.groupValues[2]
    }

    fun visibilityLabel(hiddenOnScreen: Boolean): String =
        if (hiddenOnScreen) "HIDDEN ON RESULT SCREEN" else "DISPLAYED"

    private val LEVEL = Regex("(?<!\\d)(\\d{1,2})(\\+?)(?!\\d)")
}
