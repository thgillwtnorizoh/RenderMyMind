package com.example.rhythmtracker.capture

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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** Latest OCR decision published by LightResultOcrGate for the optional visual debugger. */
data class VisionDebugSnapshot(
    val updatedAtMs: Long,
    val resultLike: Boolean,
    val matchedAnchors: Set<String>,
    val textPreview: String,
    val error: String?
)

object VisionDebugState {
    private val latest = AtomicReference(
        VisionDebugSnapshot(
            updatedAtMs = 0L,
            resultLike = false,
            matchedAnchors = emptySet(),
            textPreview = "",
            error = null
        )
    )

    fun update(result: LightOcrResult) {
        latest.set(
            VisionDebugSnapshot(
                updatedAtMs = System.currentTimeMillis(),
                resultLike = result.isResultLike,
                matchedAnchors = result.matchedKeywords.toSet(),
                textPreview = result.textPreview,
                error = result.error
            )
        )
    }

    fun snapshot(): VisionDebugSnapshot = latest.get()
}

/**
 * Optional TYPE_APPLICATION_OVERLAY debugger.
 *
 * The window is transparent and completely non-touchable. It draws the exact normalized ROI
 * used by LightResultOcrGate, then shows the latest Arcaea judgement-anchor interpretation just
 * above that rectangle. It automatically removes itself after the tracking session stops.
 */
object VisionDebugOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var debugView: VisionDebugView? = null
    private var attached = false
    private var requested = false
    private var seenActiveSession = false
    private var requestedAtMs = 0L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!requested) return

            val context = appContext ?: return
            if (!Settings.canDrawOverlays(context)) {
                stop()
                return
            }

            if (TrackerRuntime.active) {
                seenActiveSession = true
                ensureAttached(context)
                debugView?.snapshot = VisionDebugState.snapshot()
                debugView?.invalidate()
                mainHandler.postDelayed(this, REFRESH_MS)
                return
            }

            // Give CaptureService a moment to transition from startForegroundService() to active.
            if (!seenActiveSession && System.currentTimeMillis() - requestedAtMs < START_GRACE_MS) {
                mainHandler.postDelayed(this, REFRESH_MS)
                return
            }

            stop()
        }
    }

    fun start(context: Context): Boolean {
        val applicationContext = context.applicationContext
        if (!Settings.canDrawOverlays(applicationContext)) return false

        appContext = applicationContext
        windowManager = applicationContext.getSystemService(WindowManager::class.java)
        requested = true
        seenActiveSession = TrackerRuntime.active
        requestedAtMs = System.currentTimeMillis()
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
        return true
    }

    fun stop() {
        requested = false
        seenActiveSession = false
        mainHandler.removeCallbacks(refreshRunnable)
        detach()
        appContext = null
        windowManager = null
    }

    private fun ensureAttached(context: Context) {
        if (attached) return
        val manager = windowManager ?: return
        val view = VisionDebugView(context)

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
            manager.addView(view, params)
            debugView = view
            attached = true
        }.onFailure {
            debugView = null
            attached = false
        }
    }

    private fun detach() {
        val view = debugView
        val manager = windowManager
        if (view != null && manager != null && attached) {
            runCatching { manager.removeViewImmediate(view) }
        }
        debugView = null
        attached = false
    }

    private const val REFRESH_MS = 180L
    private const val START_GRACE_MS = 8_000L
}

private class VisionDebugView(context: Context) : View(context) {
    var snapshot: VisionDebugSnapshot = VisionDebugState.snapshot()

    private val density = resources.displayMetrics.density
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 16, 18, 22)
    }
    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(205, 211, 220)
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val roi = RectF(
            width * OcrProbeRegion.LEFT,
            height * OcrProbeRegion.TOP,
            width * OcrProbeRegion.RIGHT,
            height * OcrProbeRegion.BOTTOM
        )

        borderPaint.color = if (snapshot.resultLike) {
            Color.rgb(108, 232, 155)
        } else {
            Color.rgb(106, 194, 255)
        }
        canvas.drawRect(roi, borderPaint)

        val line1 = judgementLine(snapshot)
        val line2 = detailLine(snapshot)
        drawLabel(canvas, roi, line1, line2)
    }

    private fun judgementLine(snapshot: VisionDebugSnapshot): String {
        fun marker(anchor: String): String = if (anchor in snapshot.matchedAnchors) "✓" else "·"
        val gate = when {
            snapshot.error != null -> "OCR ERROR"
            snapshot.resultLike -> "RESULT-LIKE"
            snapshot.updatedAtMs == 0L -> "WAITING"
            System.currentTimeMillis() - snapshot.updatedAtMs > STALE_AFTER_MS -> "STALE"
            else -> "SCANNING"
        }
        return "PURE ${marker("PURE")}  FAR ${marker("FAR")}  LOST ${marker("LOST")}  |  $gate"
    }

    private fun detailLine(snapshot: VisionDebugSnapshot): String {
        snapshot.error?.let { return it.take(MAX_DETAIL_CHARS) }

        val extras = snapshot.matchedAnchors
            .filterNot { it == "PURE" || it == "FAR" || it == "LOST" }
            .joinToString(" ")

        val preview = snapshot.textPreview
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        return when {
            extras.isNotBlank() && preview.isNotBlank() -> "$extras | OCR: ${preview.take(MAX_PREVIEW_CHARS)}"
            extras.isNotBlank() -> extras
            preview.isNotBlank() -> "OCR: ${preview.take(MAX_PREVIEW_CHARS)}"
            else -> "OCR: (no text)"
        }.take(MAX_DETAIL_CHARS)
    }

    private fun drawLabel(canvas: Canvas, roi: RectF, line1: String, line2: String) {
        val paddingX = 7f * density
        val paddingY = 5f * density
        val gap = 2f * density
        val line1Height = primaryTextPaint.fontMetrics.run { bottom - top }
        val line2Height = secondaryTextPaint.fontMetrics.run { bottom - top }
        val textWidth = max(
            primaryTextPaint.measureText(line1),
            secondaryTextPaint.measureText(line2)
        )
        val boxWidth = textWidth + paddingX * 2
        val boxHeight = line1Height + line2Height + gap + paddingY * 2

        val left = roi.left.coerceIn(0f, max(0f, width - boxWidth))
        val preferredTop = roi.top - boxHeight - 4f * density
        val top = preferredTop.coerceAtLeast(0f)
        val box = RectF(left, top, left + boxWidth, top + boxHeight)
        canvas.drawRoundRect(box, 6f * density, 6f * density, labelBackgroundPaint)

        val line1Y = top + paddingY - primaryTextPaint.fontMetrics.top
        val line2Y = line1Y + line1Height + gap
        canvas.drawText(line1, left + paddingX, line1Y, primaryTextPaint)
        canvas.drawText(line2, left + paddingX, line2Y, secondaryTextPaint)
    }

    companion object {
        private const val STALE_AFTER_MS = 2_500L
        private const val MAX_PREVIEW_CHARS = 72
        private const val MAX_DETAIL_CHARS = 110
    }
}
