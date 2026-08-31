package com.example.rhythmtracker.identity

import android.graphics.Bitmap

/**
 * 64-bit difference hash over the central display area. It is intentionally coarse so a thin
 * debug outline, compression noise, or tiny animation does not manufacture a new result identity.
 */
object VisualFingerprint {
    fun from(frame: Bitmap): Long {
        if (frame.width < 2 || frame.height < 2) return 0L

        val cropLeft = (frame.width * 0.04f).toInt().coerceAtLeast(0)
        val cropTop = (frame.height * 0.05f).toInt().coerceAtLeast(0)
        val cropRight = (frame.width * 0.96f).toInt().coerceAtMost(frame.width)
        val cropBottom = (frame.height * 0.95f).toInt().coerceAtMost(frame.height)
        val cropWidth = (cropRight - cropLeft).coerceAtLeast(2)
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(2)

        val crop = Bitmap.createBitmap(frame, cropLeft, cropTop, cropWidth, cropHeight)
        val tiny = Bitmap.createScaledBitmap(crop, 9, 8, true)
        if (crop !== frame) crop.recycle()

        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = luminance(tiny.getPixel(x, y))
                val right = luminance(tiny.getPixel(x + 1, y))
                if (left > right) hash = hash or (1L shl bit)
                bit += 1
            }
        }
        tiny.recycle()
        return hash
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
