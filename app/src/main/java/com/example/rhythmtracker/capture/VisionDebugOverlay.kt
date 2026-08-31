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

/** Latest OCR decision published by the two-stage OCR pipeline. */
data class VisionDebugSnapshot(
    val updatedAtMs: Long,
    val resultLike: Boolean,
    val matchedAnchors: Set<String>,
    val textPreview: String,
    val regionReadings: List<OcrRegionReading>,
    val resultIdentity: String?,
    val pass: OcrPass,
    val error: String?
)

object VisionDebugState {
    private const val LIGHT_MISSES_TO_CLEAR = 2

    private val latest = AtomicReference(
        VisionDebugSnapshot(
            updatedAtMs = 0L,
            resultLike = false,
            matchedAnchors = emptySet(),
            textPreview = "",
            regionReadings = emptyList(),
            resultIdentity = null,
            pass = OcrPass.LIGHT,
            error = null
        )
    )

    private var lightMissStreak = 0

    /**
     * OCR is probabilistic. A single missed probe must not make a confirmed result overlay blink
     * out for ~900 ms and then pop back in. Two consecutive LIGHT misses are required to declare
     * that the result screen has actually gone away.
     *
     * Once the native pass has produced useful geometry, successful LIGHT probes for the SAME
     * result only prove that it is still present. A changed score identity is different: publish
     * that LIGHT result immediately so old native text cannot haunt the next sample while the new
     * full-resolution capture is starting.
     */
    @Synchronized
    fun update(result: LightOcrResult) {
        val now = System.currentTimeMillis()
        val previous = latest.get()

        if (result.pass == OcrPass.LIGHT) {
            if (result.isResultLike) {
                lightMissStreak = 0
                val sameIdentity = previous.resultIdentity != null &&
                    result.resultIdentity != null &&
                    previous.resultIdentity == result.resultIdentity
                val identityUnknown = result.resultIdentity == null

                if (previous.resultLike && previous.pass == OcrPass.NATIVE &&
                    (sameIdentity || identityUnknown)
                ) {
                    return
                }
            } else if (previous.resultLike) {
                lightMissStreak += 1
                if (lightMissStreak < LIGHT_MISSES_TO_CLEAR) {
                    return
                }
            } else {
                lightMissStreak = 0
            }
        } else {
            if (result.isResultLike) {
                lightMissStreak = 0
            } else if (previous.resultLike) {
                // Presence is owned by LIGHT. A weak native parse must not erase a result that
                // the lightweight detector just confirmed.
                return
            }
        }

        latest.set(
            VisionDebugSnapshot(
                updatedAtMs = now,
                resultLike = result.isResultLike,
                matchedAnchors = result.matchedKeywords.toSet(),
                textPreview = result.textPreview,
                regionReadings = result.regionReadings,
                resultIdentity = result.resultIdentity,
                pass = result.pass,
                error = result.error
            )
        )
    }

    fun snapshot(): VisionDebugSnapshot = latest.get()
}

/** Optional TYPE_APPLICATION_OVERLAY debugger. */
object VisionDebugOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var debugView: VisionDebugView? = null
    private var attached = false
    private var requested = false
    private var seenActiveSession = false
    private var requestedAtMs = 0L

    @Volatile
    private var temporarilyHidden = false

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
                debugView?.visibility = if (temporarilyHidden) View.INVISIBLE else View.VISIBLE
                debugView?.snapshot = VisionDebugState.snapshot()
                debugView?.invalidate()
                mainHandler.postDelayed(this, REFRESH_MS)
                return
            }

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
        temporarilyHidden = false
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
        return true
    }

    fun setTemporarilyHidden(hidden: Boolean) {
        temporarilyHidden = hidden
        mainHandler.post {
            debugView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        }
    }

    fun stop() {
        requested = false
        seenActiveSession = false
        temporarilyHidden = false
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Crucial for OCR debugging: the user can see this overlay, but MediaProjection
                // must not. Otherwise our own labels cover Arcaea's Result/track/title text and
                // the detector starts OCRing itself.
                WindowManager.LayoutParams.FLAG_SECURE,
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
        color = Color.argb(215, 16, 18, 22)
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 10.5f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 11.5f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // Debug geometry is intentionally invisible during gameplay and menus. Confirmed result
        // presence is debounced in VisionDebugState so OCR jitter cannot make the boxes flicker.
        if (!snapshot.resultLike) return

        drawGateStatus(canvas)
        snapshot.regionReadings.forEachIndexed { index, reading ->
            val roi = RectF(
                width * reading.left.coerceIn(0f, 1f),
                height * reading.top.coerceIn(0f, 1f),
                width * reading.right.coerceIn(0f, 1f),
                height * reading.bottom.coerceIn(0f, 1f)
            )
            if (roi.width() <= 1f || roi.height() <= 1f) return@forEachIndexed

            borderPaint.color = when {
                snapshot.pass == OcrPass.NATIVE -> Color.rgb(179, 122, 255)
                index == 0 -> Color.rgb(103, 230, 151)
                else -> Color.rgb(255, 200, 92)
            }
            canvas.drawRect(roi, borderPaint)
            drawRegionReadout(canvas, reading, roi)
        }
    }

    private fun drawGateStatus(canvas: Canvas) {
        val anchors = snapshot.matchedAnchors.joinToString(", ").ifBlank { "RESULT" }
        val identity = snapshot.resultIdentity?.removePrefix("score:")?.let { " | score-id: $it" }.orEmpty()
        val text = "VISION ${snapshot.pass}: RESULT LIVE  |  anchors: $anchors$identity"
        val padding = 6f * density
        val maxWidth = width * 0.38f - padding * 2
        val fitted = fitText(text, statusTextPaint, maxWidth)
        val h = statusTextPaint.fontMetrics.run { bottom - top } + padding * 2
        val box = RectF(padding, padding, width * 0.38f, padding + h)
        canvas.drawRoundRect(box, 5f * density, 5f * density, labelBackgroundPaint)
        val baseline = box.top + padding - statusTextPaint.fontMetrics.top
        canvas.drawText(fitted, box.left + padding, baseline, statusTextPaint)
    }

    private fun drawRegionReadout(
        canvas: Canvas,
        reading: OcrRegionReading,
        roi: RectF
    ) {
        val outerPadding = 6f * density
        val innerPadding = 5f * density
        val lineHeight = labelTextPaint.fontMetrics.run { bottom - top }
        val boxHeight = lineHeight * 2 + innerPadding * 2

        // Prefer the left of the detected geometry. If there is no room, pin the readout inside
        // the screen instead of letting a dynamic title rectangle push it off-canvas.
        val maxPanelWidth = width * 0.36f
        val preferredRight = roi.left - outerPadding
        val panelWidth = minOf(maxPanelWidth, preferredRight - outerPadding).coerceAtLeast(80f * density)
        val left = outerPadding
        val right = (left + panelWidth).coerceAtMost(width - outerPadding)
        val top = roi.top.coerceIn(
            outerPadding,
            (height - boxHeight - outerPadding).coerceAtLeast(outerPadding)
        )
        val box = RectF(left, top, right, top + boxHeight)
        canvas.drawRoundRect(box, 5f * density, 5f * density, labelBackgroundPaint)

        val maxTextWidth = (box.width() - innerPadding * 2).coerceAtLeast(1f)
        val line1 = fitText("${reading.label}:", labelTextPaint, maxTextWidth)
        val line2 = fitText(reading.text.ifBlank { "(no text)" }, labelTextPaint, maxTextWidth)
        val firstBaseline = box.top + innerPadding - labelTextPaint.fontMetrics.top
        val secondBaseline = firstBaseline + lineHeight
        canvas.drawText(line1, box.left + innerPadding, firstBaseline, labelTextPaint)
        canvas.drawText(line2, box.left + innerPadding, secondBaseline, labelTextPaint)
    }

    private fun fitText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        if (maxWidth <= paint.measureText("…")) return ""

        val available = (maxWidth - paint.measureText("…")).coerceAtLeast(0f)
        val count = paint.breakText(text, true, available, null).coerceAtLeast(0)
        return text.take(count).trimEnd() + "…"
    }
}
