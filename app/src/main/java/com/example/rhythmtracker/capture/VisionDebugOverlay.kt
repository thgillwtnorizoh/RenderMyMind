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
                if (lightMissStreak < LIGHT_MISSES_TO_CLEAR) return
            } else {
                lightMissStreak = 0
            }
        } else {
            if (result.isResultLike) {
                lightMissStreak = 0
            } else if (previous.resultLike) {
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

        // Do NOT use FLAG_SECURE on this full-screen transparent window. On several Android/MIUI
        // builds MediaProjection redacts the whole secure window rectangle, including the app
        // underneath it. Because our window is MATCH_PARENT that can turn the OCR feed into a
        // nearly blank screen. Instead the overlay itself is deliberately capture-safe: thin
        // borders plus tiny one-letter tags, no OCR values, no result keywords, no score digits.
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
    private val tagBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(155, 16, 18, 22)
    }
    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 9f * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.MONOSPACE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || !snapshot.resultLike) return

        // Tiny status chip only. "V:L" / "V:N" is intentionally useless to the OCR detector.
        drawPassChip(canvas)

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
            drawRegionTag(canvas, reading, roi)
        }
    }

    private fun drawPassChip(canvas: Canvas) {
        val text = if (snapshot.pass == OcrPass.NATIVE) "V:N" else "V:L"
        val pad = 3f * density
        val textWidth = tagTextPaint.measureText(text)
        val h = tagTextPaint.fontMetrics.run { bottom - top } + pad * 2
        val right = width - 6f * density
        val box = RectF(right - textWidth - pad * 2, 6f * density, right, 6f * density + h)
        canvas.drawRoundRect(box, 3f * density, 3f * density, tagBackgroundPaint)
        val baseline = box.top + pad - tagTextPaint.fontMetrics.top
        canvas.drawText(text, box.left + pad, baseline, tagTextPaint)
    }

    private fun drawRegionTag(canvas: Canvas, reading: OcrRegionReading, roi: RectF) {
        val tag = when (reading.key) {
            "result_tripwire", "result_fallback" -> "A"
            "title" -> "T"
            "track_state" -> "K"
            "score" -> "N"
            "judgements" -> "J"
            else -> "D"
        }
        val pad = 2f * density
        val textWidth = tagTextPaint.measureText(tag)
        val h = tagTextPaint.fontMetrics.run { bottom - top } + pad * 2
        val w = textWidth + pad * 2

        // Place the tiny tag just inside the box corner. It never contains recognised text or
        // numeric OCR values, so if MediaProjection sees it it cannot become a false tripwire.
        val left = roi.left.coerceIn(0f, (width - w).coerceAtLeast(0f))
        val top = roi.top.coerceIn(0f, (height - h).coerceAtLeast(0f))
        val box = RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(box, 2f * density, 2f * density, tagBackgroundPaint)
        val baseline = box.top + pad - tagTextPaint.fontMetrics.top
        canvas.drawText(tag, box.left + pad, baseline, tagTextPaint)
    }
}
