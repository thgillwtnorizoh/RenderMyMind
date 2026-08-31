package com.example.rhythmtracker.game.arcaea

import com.example.rhythmtracker.vision.VisionLine

/**
 * Broad, resolution-independent geometry for Arcaea's result screen.
 *
 * Arcaea has kept the result layout remarkably stable across aspect ratios. We therefore use
 * viewport-relative bands to reject unrelated UI, while keeping OCR at the native captured
 * resolution. No bitmap is resized here. Width-scaled minimum text sizes prevent tiny unrelated
 * labels from becoming anchors on high-resolution displays.
 */
object ArcaeaResultLayout {
    private const val REFERENCE_WIDTH = 1536f

    fun scaleFor(frameWidth: Int): Float = frameWidth.coerceAtLeast(1) / REFERENCE_WIDTH

    fun isHeaderBand(line: VisionLine): Boolean {
        val w = line.frameWidth.toFloat().coerceAtLeast(1f)
        val h = line.frameHeight.toFloat().coerceAtLeast(1f)
        val cx = line.bounds.centerX()
        val cy = line.bounds.centerY()
        return cx <= w * 0.22f &&
            cy <= h * 0.12f &&
            line.bounds.height() >= widthScaledPixels(line.frameWidth, 9f)
    }

    fun isTitleBand(line: VisionLine): Boolean {
        val w = line.frameWidth.toFloat().coerceAtLeast(1f)
        val h = line.frameHeight.toFloat().coerceAtLeast(1f)
        val cx = line.bounds.centerX()
        val cy = line.bounds.centerY()
        return cx in (w * 0.16f)..(w * 0.84f) &&
            cy in (h * 0.055f)..(h * 0.31f) &&
            line.bounds.height() >= widthScaledPixels(line.frameWidth, 8f)
    }

    fun isTrackBand(line: VisionLine): Boolean {
        val w = line.frameWidth.toFloat().coerceAtLeast(1f)
        val h = line.frameHeight.toFloat().coerceAtLeast(1f)
        val cx = line.bounds.centerX()
        val cy = line.bounds.centerY()
        return cx in (w * 0.20f)..(w * 0.82f) &&
            cy in (h * 0.16f)..(h * 0.46f) &&
            line.bounds.height() >= widthScaledPixels(line.frameWidth, 12f)
    }

    fun isScoreBand(line: VisionLine): Boolean {
        val w = line.frameWidth.toFloat().coerceAtLeast(1f)
        val h = line.frameHeight.toFloat().coerceAtLeast(1f)
        val cx = line.bounds.centerX()
        val cy = line.bounds.centerY()
        return cx in (w * 0.29f)..(w * 0.76f) &&
            cy in (h * 0.27f)..(h * 0.62f) &&
            line.bounds.height() >= widthScaledPixels(line.frameWidth, 12f)
    }

    fun isJudgementBand(line: VisionLine): Boolean {
        val w = line.frameWidth.toFloat().coerceAtLeast(1f)
        val h = line.frameHeight.toFloat().coerceAtLeast(1f)
        val cx = line.bounds.centerX()
        val cy = line.bounds.centerY()
        return cx in (w * 0.34f)..(w * 0.66f) &&
            cy in (h * 0.47f)..(h * 0.90f) &&
            line.bounds.height() >= widthScaledPixels(line.frameWidth, 7f)
    }

    private fun widthScaledPixels(frameWidth: Int, referencePixels: Float): Int =
        (referencePixels * scaleFor(frameWidth)).coerceAtLeast(3f).toInt()
}
