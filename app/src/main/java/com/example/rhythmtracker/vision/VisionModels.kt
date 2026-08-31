package com.example.rhythmtracker.vision

import android.graphics.Rect

enum class VisionStage {
    LIGHT,
    NATIVE
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    companion object {
        fun from(bounds: Rect, frameWidth: Int, frameHeight: Int): NormalizedRect {
            val width = frameWidth.coerceAtLeast(1).toFloat()
            val height = frameHeight.coerceAtLeast(1).toFloat()
            return NormalizedRect(
                left = (bounds.left / width).coerceIn(0f, 1f),
                top = (bounds.top / height).coerceIn(0f, 1f),
                right = (bounds.right / width).coerceIn(0f, 1f),
                bottom = (bounds.bottom / height).coerceIn(0f, 1f)
            )
        }
    }
}

data class VisionLine(
    val text: String,
    val bounds: Rect,
    val frameWidth: Int,
    val frameHeight: Int,
    val source: String
) {
    val normalizedBounds: NormalizedRect
        get() = NormalizedRect.from(bounds, frameWidth, frameHeight)
}

data class DebugRegion(
    val key: String,
    val bounds: NormalizedRect,
    val stage: VisionStage
)
