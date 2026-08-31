package com.example.rhythmtracker.game.arcaea

import com.example.rhythmtracker.game.GameAdapter
import com.example.rhythmtracker.game.LightTextDecision
import java.util.Locale

/** Arcaea-specific result-screen interpretation. */
class ArcaeaGameAdapter : GameAdapter {
    override val gameId: String = "arcaea"

    /**
     * Arcaea keeps a small `Result` label in the top navigation while a result screen is live.
     * This is a much cheaper and more stable tripwire than continuously OCRing the score panel.
     * The caller searches for this line dynamically, so no X/Y box is baked into the adapter.
     */
    override fun classifyTripwireText(normalizedText: String): LightTextDecision {
        if (normalizedText.isBlank()) return LightTextDecision(false, emptySet())

        val compact = normalizedText
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() }

        if (compact.length !in 5..10) return LightTextDecision(false, emptySet())

        val exactOrAttached = compact == RESULT_HEADER ||
            (compact.startsWith(RESULT_HEADER) && compact.length <= RESULT_HEADER.length + 4)
        val fuzzy = compact.length in 5..7 &&
            levenshtein(compact, RESULT_HEADER, 1) <= 1

        return if (exactOrAttached || fuzzy) {
            LightTextDecision(true, setOf("RESULT HEADER"))
        } else {
            LightTextDecision(false, emptySet())
        }
    }

    override fun classifyLightText(normalizedText: String): LightTextDecision {
        if (normalizedText.isBlank()) return LightTextDecision(false, emptySet())

        val matched = linkedSetOf<String>()
        ANCHORS.forEach { anchor ->
            if (normalizedText.contains(anchor)) matched += anchor
        }

        val compact = normalizedText
            .uppercase(Locale.US)
            .filter { it.isLetterOrDigit() }

        val fuzzyComplete = looksLikeTrackState(compact, "COMPLETE")
        val fuzzyLost = looksLikeTrackState(compact, "LOST")
        if (fuzzyComplete) {
            matched += "TRACK COMPLETE"
            if (!normalizedText.contains("TRACK COMPLETE")) matched += "TRACK_STATE_FUZZY"
        }
        if (fuzzyLost) {
            matched += "TRACK LOST"
            if (!normalizedText.contains("TRACK LOST")) matched += "TRACK_STATE_FUZZY"
        }

        val judgementCount = JUDGEMENT_ANCHORS.count { it in matched }
        val hasStrongTrackState = fuzzyComplete || fuzzyLost ||
            STRONG_RESULT_HEADERS.any { it in matched }
        val hasMaxRecall = "MAX RECALL" in matched
        val hasScoreLikeNumber = containsArcaeaScore(normalizedText)

        if (hasScoreLikeNumber) matched += "SCORE_NUMBER"

        val resultLike = hasStrongTrackState ||
            judgementCount >= 2 ||
            (judgementCount >= 1 && (hasMaxRecall || hasScoreLikeNumber)) ||
            (hasMaxRecall && hasScoreLikeNumber)

        return LightTextDecision(
            isResultLike = resultLike,
            matchedAnchors = matched
        )
    }

    /**
     * Compare several prefixes after TRACK with the beginning of the expected state. Testing the
     * short prefixes matters because OCR can turn TRACK COMPLETE into TRACK CONP or similar noise.
     */
    private fun looksLikeTrackState(compactText: String, expectedState: String): Boolean {
        var searchFrom = 0
        while (true) {
            val trackAt = compactText.indexOf("TRACK", searchFrom)
            if (trackAt < 0) return false

            val suffix = compactText.substring(trackAt + "TRACK".length)
            val maxComparable = minOf(suffix.length, expectedState.length)
            if (maxComparable >= MIN_FUZZY_STATE_CHARS) {
                for (length in MIN_FUZZY_STATE_CHARS..maxComparable) {
                    val observed = suffix.take(length)
                    val expected = expectedState.take(length)
                    val maxEdits = if (length >= 7) 2 else 1
                    if (levenshtein(observed, expected, maxEdits) <= maxEdits) return true
                }
            }

            searchFrom = trackAt + 1
        }
    }

    /** Bounded Levenshtein distance. Stops early once the row cannot recover below maxDistance. */
    private fun levenshtein(a: String, b: String, maxDistance: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return maxDistance + 1

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                val insertion = current[j - 1] + 1
                val deletion = previous[j] + 1
                current[j] = minOf(substitution, insertion, deletion)
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun containsArcaeaScore(text: String): Boolean {
        return SCORE_CANDIDATE.findAll(text).any { match ->
            match.value.count { it.isDigit() } in 5..8
        }
    }

    companion object {
        private const val RESULT_HEADER = "RESULT"
        private const val MIN_FUZZY_STATE_CHARS = 4

        private val JUDGEMENT_ANCHORS = setOf("PURE", "FAR", "LOST")
        private val STRONG_RESULT_HEADERS = setOf("TRACK COMPLETE", "TRACK LOST")
        private val RESULT_HEADERS = STRONG_RESULT_HEADERS + "MAX RECALL"
        private val ANCHORS = JUDGEMENT_ANCHORS + RESULT_HEADERS

        // Accept separators Arcaea commonly renders between score groups. The digit count is
        // checked separately so timestamps and small counters do not become score signals.
        private val SCORE_CANDIDATE = Regex(
            "(?<!\\d)\\d(?:[\\d'’.,\\s]{3,14})\\d(?!\\d)"
        )
    }
}
