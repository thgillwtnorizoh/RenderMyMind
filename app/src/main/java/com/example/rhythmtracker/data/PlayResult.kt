package com.example.rhythmtracker.data

import java.util.UUID

/** Canonical persisted result produced only after the v2 state machine accepts a distinct play. */
data class PlayResult(
    val id: String,
    val capturedAtMs: Long,
    val gameId: String,
    val songId: String?,
    val difficulty: String?,
    val title: String?,
    val artist: String?,
    val trackState: String?,
    val score: Long?,
    val pure: Int?,
    val far: Int?,
    val lost: Int?,
    val confidence: Float,
    val screenshotPath: String?,
    val rawOcr: String?,
    val source: String
) {
    companion object {
        fun manualTest() = PlayResult(
            id = UUID.randomUUID().toString(),
            capturedAtMs = System.currentTimeMillis(),
            gameId = "prototype",
            songId = null,
            difficulty = null,
            title = null,
            artist = null,
            trackState = null,
            score = null,
            pure = null,
            far = null,
            lost = null,
            confidence = 1f,
            screenshotPath = null,
            rawOcr = null,
            source = "manual-test"
        )
    }
}
