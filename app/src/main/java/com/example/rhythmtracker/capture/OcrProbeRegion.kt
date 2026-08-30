package com.example.rhythmtracker.capture

/**
 * Normalized low-cost OCR regions for Arcaea's landscape result screen.
 *
 * Keeping these regions separate gives the vision debugger something meaningful to draw and,
 * more importantly, lets the OCR composite preserve useful pixel density instead of shrinking
 * the entire result screen into one broad crop.
 */
data class OcrProbeRegionSpec(
    val key: String,
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

object OcrProbeRegion {
    val TITLE = OcrProbeRegionSpec(
        key = "title",
        label = "TITLE",
        left = 0.29f,
        top = 0.045f,
        right = 0.73f,
        bottom = 0.23f
    )

    val TRACK_STATE = OcrProbeRegionSpec(
        key = "track_state",
        label = "TRACK STATE",
        left = 0.30f,
        top = 0.22f,
        right = 0.72f,
        bottom = 0.36f
    )

    val SCORE = OcrProbeRegionSpec(
        key = "score",
        label = "SCORE",
        left = 0.36f,
        top = 0.36f,
        right = 0.72f,
        bottom = 0.55f
    )

    val JUDGEMENTS = OcrProbeRegionSpec(
        key = "judgements",
        label = "JUDGEMENTS",
        left = 0.38f,
        top = 0.68f,
        right = 0.65f,
        bottom = 0.89f
    )

    val ALL: List<OcrProbeRegionSpec> = listOf(
        TITLE,
        TRACK_STATE,
        SCORE,
        JUDGEMENTS
    )
}
