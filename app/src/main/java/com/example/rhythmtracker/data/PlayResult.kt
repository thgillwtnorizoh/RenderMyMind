package com.example.rhythmtracker.data

import java.util.UUID

/**
 * Deliberately generic. Game-specific judgements/calculated rating can be added later
 * without coupling the screen-capture service to one rhythm game.
 */
data class PlayResult(
    val id: String,
    val capturedAtMs: Long,
    val gameId: String,
    val songId: String?,
    val difficulty: String?,
    val score: Long?,
    val confidence: Float,
    val source: String
) {
    companion object {
        fun manualTest() = PlayResult(
            id = UUID.randomUUID().toString(),
            capturedAtMs = System.currentTimeMillis(),
            gameId = "prototype",
            songId = null,
            difficulty = null,
            score = null,
            confidence = 1f,
            source = "manual-test"
        )
    }
}
