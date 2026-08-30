package com.example.rhythmtracker.game

/**
 * Game-specific interpretation for the cheap OCR sentinel.
 *
 * CaptureService stays game-agnostic: it owns MediaProjection and timing, while adapters
 * decide whether OCR text looks like a result screen for one game.
 */
interface GameAdapter {
    val gameId: String

    fun classifyLightText(normalizedText: String): LightTextDecision
}

data class LightTextDecision(
    val isResultLike: Boolean,
    val matchedAnchors: Set<String>
)
