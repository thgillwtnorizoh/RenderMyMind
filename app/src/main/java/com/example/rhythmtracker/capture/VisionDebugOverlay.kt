package com.example.rhythmtracker.capture

import android.content.Context
import com.example.rhythmtracker.debug.TrackingDebugOverlay

/** Compatibility surface for MainActivity. The legacy overlay implementation no longer exists. */
object VisionDebugOverlay {
    fun start(context: Context): Boolean = TrackingDebugOverlay.start(context)

    fun stop() {
        TrackingDebugOverlay.stop()
    }

    fun setTemporarilyHidden(hidden: Boolean) {
        // Retained only for source compatibility. V2 never hides the overlay to influence OCR.
    }
}
