package com.example.rhythmtracker.capture

/**
 * Semantic OCR fields shown by the debug overlay.
 *
 * These no longer contain fixed screen coordinates. Each OCR pass publishes the bounds it actually
 * detected, which lets long titles and different result layouts grow naturally instead of being
 * clipped by a baked-in rectangle.
 */
data class OcrProbeRegionSpec(
    val key: String,
    val label: String
)

data class NormalizedOcrRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun clamped(): NormalizedOcrRect = NormalizedOcrRect(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = right.coerceIn(0f, 1f),
        bottom = bottom.coerceIn(0f, 1f)
    )
}

data class OcrRegionReading(
    val key: String,
    val label: String,
    val text: String,
    val bounds: NormalizedOcrRect? = null
)

object OcrProbeRegion {
    val RESULT_HEADER = OcrProbeRegionSpec("result_header", "RESULT HEADER")
    val TITLE = OcrProbeRegionSpec("title", "TITLE")
    val TRACK_STATE = OcrProbeRegionSpec("track_state", "TRACK STATE")
    val SCORE = OcrProbeRegionSpec("score", "SCORE")
    val JUDGEMENTS = OcrProbeRegionSpec("judgements", "JUDGEMENTS")

    val ALL: List<OcrProbeRegionSpec> = listOf(
        RESULT_HEADER,
        TITLE,
        TRACK_STATE,
        SCORE,
        JUDGEMENTS
    )
}
