package com.example.rhythmtracker.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.rhythmtracker.TrackerRuntime
import com.example.rhythmtracker.vision.DebugRegion
import com.example.rhythmtracker.vision.VisionStage
import java.util.concurrent.atomic.AtomicReference

data class DebugSnapshot(
    val visible: Boolean,
    val stage: VisionStage,
    val stateLabel: String,
    val regions: List<DebugRegion>
)

object DebugBus {
    private val latest = AtomicReference(
        DebugSnapshot(false, VisionStage.LIGHT, "IDLE", emptyList())
    )

    fun publish(snapshot: DebugSnapshot) {
        latest.set(snapshot)
    }

    fun clear() {
        latest.set(DebugSnapshot(false, VisionStage.LIGHT, "IDLE", emptyList()))
    }

    fun snapshot(): DebugSnapshot = latest.get()
}

/**
 * Visualization only. No OCR values, scores, titles, or result keywords are painted onto the
 * captured display. The overlay is never consulted by detection or state logic.
 */
object TrackingDebugOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var view: DebugView? = null
    private var requested = false
    private var attached = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!requested) return
            val context = appContext ?: return
            if (!Settings.canDrawOverlays(context)) {
                stop()
                return
            }
            if (!TrackerRuntime.active) {
                mainHandler.postDelayed(this, REFRESH_MS)
                return
            }
            ensureAttached(context)
            view?.snapshot = DebugBus.snapshot()
            view?.invalidate()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    fun start(context: Context): Boolean {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return false
        appContext = app
        windowManager = app.getSystemService(WindowManager::class.java)
        requested = true
        mainHandler.removeCallbacks(refresh)
        mainHandler.post(refresh)
        return true
    }

    fun stop() {
        requested = false
        mainHandler.removeCallbacks(refresh)
        val manager = windowManager
        val current = view
        if (attached && manager != null && current != null) {
            runCatching { manager.removeViewImmediate(current) }
        }
        attached = false
        view = null
        windowManager = null
        appContext = null
    }

    private fun ensureAttached(context: Context) {
        if (attached) return
        val manager = windowManager ?: return
        val newView = DebugView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching {
            manager.addView(newView, params)
            view = newView
            attached = true
        }
    }

    private const val REFRESH_MS = 120L
}

private class DebugView(context: Context) : View(context) {
    var snapshot: DebugSnapshot = DebugBus.snapshot()
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!snapshot.visible || width <= 0 || height <= 0) return
        paint.color = if (snapshot.stage == VisionStage.NATIVE) {
            Color.rgb(190, 120, 255)
        } else {
            Color.rgb(88, 230, 150)
        }
        snapshot.regions.forEach { region ->
            val box = RectF(
                width * region.bounds.left,
                height * region.bounds.top,
                width * region.bounds.right,
                height * region.bounds.bottom
            )
            if (box.width() > 1f && box.height() > 1f) canvas.drawRect(box, paint)
        }
    }
}
