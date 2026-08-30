package com.example.rhythmtracker.game.arcaea

import com.example.rhythmtracker.game.GameAdapter
import com.example.rhythmtracker.game.LightTextDecision

/**
 * Arcaea-specific result gate.
 *
 * TRACK COMPLETE / TRACK LOST are strong enough by themselves because this adapter only runs
 * while tracking Arcaea. PURE / FAR / LOST remain useful corroborating anchors, and Arcaea's
 * punctuated score format (for example 09'430'816) is normalized before score detection.
 */
class ArcaeaGameAdapter : GameAdapter {
    override val gameId: String = "arcaea"

    override fun classifyLightText(normalizedText: String): LightTextDecision {
        if (normalizedText.isBlank()) return LightTextDecision(false, emptySet())

        val matched = linkedSetOf<String>()
        ANCHORS.forEach { anchor ->
            if (normalizedText.contains(anchor)) matched += anchor
        }

        val judgementCount = JUDGEMENT_ANCHORS.count { it in matched }
        val hasStrongTrackState = STRONG_RESULT_HEADERS.any { it in matched }
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

    private fun containsArcaeaScore(text: String): Boolean {
        return SCORE_CANDIDATE.findAll(text).any { match ->
            match.value.count { it.isDigit() } in 7..8
        }
    }

    companion object {
        private val JUDGEMENT_ANCHORS = setOf("PURE", "FAR", "LOST")
        private val STRONG_RESULT_HEADERS = setOf("TRACK COMPLETE", "TRACK LOST")
        private val RESULT_HEADERS = STRONG_RESULT_HEADERS + "MAX RECALL"
        private val ANCHORS = JUDGEMENT_ANCHORS + RESULT_HEADERS

        // Accept separators Arcaea commonly renders between score groups. The digit count is
        // checked separately so timestamps and small counters do not become score signals.
        private val SCORE_CANDIDATE = Regex(
            "(?<!\\d)\\d(?:[\\d'’.,\\s]{5,14})\\d(?!\\d)"
        )
    }
}
