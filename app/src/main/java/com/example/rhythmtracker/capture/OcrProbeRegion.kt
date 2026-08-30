package com.example.rhythmtracker.capture

/**
 * One source of truth for the normalized low-cost OCR probe region.
 * Coordinates are fractions of the captured display, in [0, 1].
 */
object OcrProbeRegion {
    const val LEFT = 0.15f
    const val TOP = 0.08f
    const val RIGHT = 0.85f
    const val BOTTOM = 0.62f
}
