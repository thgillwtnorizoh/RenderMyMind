package com.example.rhythmtracker.game.arcaea

import com.example.rhythmtracker.game.GameAdapter
import com.example.rhythmtracker.game.LightTextDecision

/**
 * First Arcaea-specific result gate.
 *
 * PURE / FAR / LOST are intentionally the strongest anchors because they occur together on
 * Arcaea result screens and are much less generic than words such as RESULT or CLEAR.
 * This is still a tuning baseline until we have a representative screenshot corpus.
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
        val hasResultHeader = RESULT_HEADERS.any { it in matched }
        val hasScoreLikeNumber = SCORE_LIKE_NUMBER.containsMatchIn(normalizedText)

        // Two judgement labels are already a strong Arcaea signature. One judgement label
        // needs another independent result-screen signal to wake native-resolution capture.
        val resultLike = judgementCount >= 2 ||
            (judgementCount >= 1 && (hasResultHeader || hasScoreLikeNumber))

        if (hasScoreLikeNumber) matched += "SCORE_NUMBER"

        return LightTextDecision(
            isResultLike = resultLike,
            matchedAnchors = matched
        )
    }

    companion object {
        private val JUDGEMENT_ANCHORS = setOf("PURE", "FAR", "LOST")
        private val RESULT_HEADERS = setOf("TRACK COMPLETE", "TRACK LOST", "MAX RECALL")
        private val ANCHORS = JUDGEMENT_ANCHORS + RESULT_HEADERS

        // This is only an extra corroborating signal, never enough by itself to declare a result.
        private val SCORE_LIKE_NUMBER = Regex("(?<!\\d)\\d{7,8}(?!\\d)")
    }
}
