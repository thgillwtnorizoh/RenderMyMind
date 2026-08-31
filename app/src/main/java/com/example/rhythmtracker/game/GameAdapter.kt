package com.example.rhythmtracker.game

/**
 * Game-specific interpretation for the OCR pipeline.
 *
 * CaptureService stays game-agnostic: it owns MediaProjection and timing, while adapters decide
 * whether OCR text belongs to a result screen for one game.
 */
interface GameAdapter {
    val gameId: String

    /**
     * Classifies richer result text after a result candidate has already been found.
     */
    fun classifyLightText(normalizedText: String): LightTextDecision

    /**
     * Classifies one short OCR line as the cheap always-on result tripwire.
     *
     * The default keeps older adapters source-compatible. Games with a stable result header should
     * override this so the persistent probe can watch one tiny semantic anchor instead of parsing
     * score/judgement text during gameplay.
     */
    fun classifyTripwireText(normalizedText: String): LightTextDecision =
        classifyLightText(normalizedText)
}

data class LightTextDecision(
    val isResultLike: Boolean,
    val matchedAnchors: Set<String>
)
