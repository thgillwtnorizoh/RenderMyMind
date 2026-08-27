package com.example.rhythmtracker.capture

import android.graphics.Bitmap

/**
 * Cheap pre-OCR stage.
 *
 * This prototype intentionally detects only "the screen became visually stable".
 * It does NOT claim that a stable screen is a rhythm-game result screen.
 * A future game adapter should run after this gate and confirm known UI regions.
 */
class StabilityGateDetector {

    data class Detection(
        val stable: Boolean,
        val confidence: Float,
        val hammingDistance: Int
    )

    private var previousHash: Long? = null
    private var stableRun = 0

    fun inspect(bitmap: Bitmap): Detection {
        val hash = averageHash(bitmap)
        val previous = previousHash
        previousHash = hash

        if (previous == null) {
            stableRun = 0
            return Detection(false, 0f, 64)
        }

        val distance = java.lang.Long.bitCount(previous xor hash)
        stableRun = if (distance <= MAX_HASH_DISTANCE) stableRun + 1 else 0

        val stable = stableRun >= REQUIRED_STABLE_SAMPLES
        val confidence = if (stable) {
            (1f - distance / 64f).coerceIn(0f, 1f)
        } else {
            0f
        }

        return Detection(stable, confidence, distance)
    }

    private fun averageHash(source: Bitmap): Long {
        val tiny = Bitmap.createScaledBitmap(source, 8, 8, false)
        val pixels = IntArray(64)
        tiny.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        if (tiny !== source) tiny.recycle()

        val lum = IntArray(64)
        var sum = 0L
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val y = (r * 299 + g * 587 + b * 114) / 1000
            lum[i] = y
            sum += y
        }
        val avg = (sum / 64L).toInt()

        var hash = 0L
        for (i in lum.indices) {
            if (lum[i] >= avg) hash = hash or (1L shl i)
        }
        return hash
    }

    companion object {
        private const val MAX_HASH_DISTANCE = 3
        private const val REQUIRED_STABLE_SAMPLES = 2
    }
}
