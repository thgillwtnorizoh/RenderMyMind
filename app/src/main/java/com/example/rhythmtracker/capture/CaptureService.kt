package com.example.rhythmtracker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import com.example.rhythmtracker.MainActivity
import com.example.rhythmtracker.R
import com.example.rhythmtracker.TrackerRuntime
import com.example.rhythmtracker.data.DiagnosticStore
import com.example.rhythmtracker.data.ResultCaptureStore
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class CaptureService : Service() {

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var captureStore: ResultCaptureStore
    private lateinit var diagnosticStore: DiagnosticStore

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val diagnosticExecutor = Executors.newSingleThreadExecutor()
    private val ocrGate = LightResultOcrGate()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var probeReader: ImageReader? = null
    private var burstReader: ImageReader? = null

    private var sourceWidth = 0
    private var sourceHeight = 0
    private var probeWidth = 0
    private var probeHeight = 0
    private var densityDpi = 0

    private var probeInFlight = false
    private var probeStartedAtNs = 0L
    private var burstInProgress = false
    private var burstFramesSeen = 0
    private var resultArmed = true
    private var consecutiveMisses = 0
    private var ownsSession = false
    private var lastSlowOcrLoggedAtMs = 0L

    private val shuttingDown = AtomicBoolean(false)

    private val probeRunnable = Runnable { pullProbeFrame() }

    private val diagnosticRunnable = object : Runnable {
        override fun run() {
            if (!TrackerRuntime.active || shuttingDown.get() || !ownsSession) return
            queueDiagnosticsSnapshot()
            captureHandler.postDelayed(this, DIAGNOSTIC_SNAPSHOT_INTERVAL_MS)
        }
    }

    private val burstTimeoutRunnable = Runnable {
        if (!burstInProgress || shuttingDown.get()) return@Runnable
        queueDiagnosticsSnapshot(
            event = "native_capture_timeout",
            extra = JSONObject().put("framesSeen", burstFramesSeen)
        )
        restoreProbeSurface("Native result capture timed out; returned to OCR probe")
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            TrackerRuntime.lastMessage = "Projection stopped by Android/user"
            shutdownProjection(requestProjectionStop = false)
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            handleSourceResize(width, height)
        }
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("rendermymind-capture")
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
        captureStore = ResultCaptureStore(this)
        diagnosticStore = DiagnosticStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (TrackerRuntime.active) return START_NOT_STICKY

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                startProjectionFromIntent(intent)
            }

            ACTION_STOP -> {
                TrackerRuntime.lastMessage = "Stopping tracking…"
                stopSelf()
            }

            ACTION_FLUSH_DIAGNOSTICS -> {
                if (ownsSession) saveDiagnosticsNow("manual_flush", appendEvent = true)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34 && TrackerRuntime.active) {
            captureHandler.post {
                val (width, height) = currentDisplayBounds()
                handleSourceResize(width, height)
            }
        }
    }

    override fun onDestroy() {
        shutdownProjection(requestProjectionStop = true)
        ocrGate.close()
        analysisExecutor.shutdownNow()
        diagnosticExecutor.shutdownNow()
        if (::captureThread.isInitialized) captureThread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startProjectionFromIntent(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            TrackerRuntime.lastMessage = "Missing MediaProjection permission token"
            stopSelf()
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            TrackerRuntime.lastMessage = "Android returned no MediaProjection instance"
            stopSelf()
            return
        }

        mediaProjection = projection
        projection.registerCallback(projectionCallback, captureHandler)

        densityDpi = resources.configuration.densityDpi
        val (width, height) = currentDisplayBounds()
        sourceWidth = width
        sourceHeight = height

        createProbeReader(width, height)
        val reader = probeReader
        if (reader == null) {
            TrackerRuntime.lastMessage = "Could not create OCR probe ImageReader"
            stopSelf()
            return
        }

        virtualDisplay = projection.createVirtualDisplay(
            "RenderMyMindProbe",
            probeWidth,
            probeHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )

        if (virtualDisplay == null) {
            TrackerRuntime.lastMessage = "createVirtualDisplay() returned null"
            stopSelf()
            return
        }

        shuttingDown.set(false)
        probeInFlight = false
        probeStartedAtNs = 0L
        burstInProgress = false
        resultArmed = true
        consecutiveMisses = 0
        lastSlowOcrLoggedAtMs = 0L

        val startedAtMs = System.currentTimeMillis()
        TrackerRuntime.resetForNewSession(
            id = UUID.randomUUID().toString(),
            startedAtMs = startedAtMs
        )
        ownsSession = true
        TrackerRuntime.active = true
        TrackerRuntime.captureSize = "${probeWidth}x${probeHeight} persistent OCR probe"
        TrackerRuntime.lastMessage =
            "v0.2.1 active; persistent low-res surface + light OCR every ${PROBE_INTERVAL_MS}ms"

        saveDiagnosticsNow("session_start", appendEvent = true)
        captureHandler.removeCallbacks(diagnosticRunnable)
        captureHandler.postDelayed(diagnosticRunnable, DIAGNOSTIC_SNAPSHOT_INTERVAL_MS)
        scheduleNextProbe(250L)
    }

    /**
     * Pulls the newest queued low-resolution frame without changing the VirtualDisplay surface.
     * The surface stays attached for the entire gameplay session.
     */
    private fun pullProbeFrame() {
        if (!TrackerRuntime.active || shuttingDown.get() || burstInProgress || probeInFlight) return
        val reader = probeReader ?: return

        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image == null) {
            scheduleNextProbe(PROBE_EMPTY_RETRY_MS)
            return
        }

        val bitmap = try {
            imageToBitmap(image)
        } finally {
            image.close()
        }

        TrackerRuntime.sampledFrames += 1
        TrackerRuntime.ocrProbes += 1
        TrackerRuntime.lastSampleAtMs = System.currentTimeMillis()
        probeInFlight = true
        probeStartedAtNs = SystemClock.elapsedRealtimeNanos()

        try {
            // inspect() immediately copies only the small OCR ROI, so the full probe bitmap can
            // be recycled as soon as the asynchronous ML Kit task has been queued.
            ocrGate.inspect(bitmap, analysisExecutor) { result ->
                captureHandler.post {
                    handleOcrResult(result)
                }
            }
        } catch (error: Throwable) {
            captureHandler.post {
                probeInFlight = false
                probeStartedAtNs = 0L
                TrackerRuntime.lastMessage =
                    "OCR probe setup failed: ${error.message ?: error.javaClass.simpleName}"
                queueDiagnosticsSnapshot(
                    event = "ocr_setup_error",
                    extra = JSONObject().put("message", TrackerRuntime.lastMessage)
                )
                scheduleNextProbe(PROBE_INTERVAL_MS)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun handleOcrResult(result: LightOcrResult) {
        if (shuttingDown.get()) return
        probeInFlight = false

        val startedAtNs = probeStartedAtNs
        probeStartedAtNs = 0L
        if (startedAtNs != 0L) {
            val durationMs = ((SystemClock.elapsedRealtimeNanos() - startedAtNs) / 1_000_000L)
                .coerceAtLeast(0L)
            TrackerRuntime.lastOcrDurationMs = durationMs
            TrackerRuntime.totalOcrDurationMs += durationMs
            if (durationMs > TrackerRuntime.maxOcrDurationMs) {
                TrackerRuntime.maxOcrDurationMs = durationMs
            }

            val now = System.currentTimeMillis()
            if (durationMs >= SLOW_OCR_LOG_THRESHOLD_MS &&
                now - lastSlowOcrLoggedAtMs >= SLOW_OCR_LOG_COOLDOWN_MS
            ) {
                lastSlowOcrLoggedAtMs = now
                queueDiagnosticsSnapshot(
                    event = "slow_ocr",
                    extra = JSONObject().put("durationMs", durationMs)
                )
            }
        }

        if (result.textPreview.isNotBlank()) {
            TrackerRuntime.lastOcrText = result.textPreview
        }

        if (result.error != null) {
            TrackerRuntime.lastMessage = "Light OCR error: ${result.error}"
            queueDiagnosticsSnapshot(
                event = "ocr_error",
                extra = JSONObject().put("message", result.error)
            )
            scheduleNextProbe(PROBE_INTERVAL_MS)
            return
        }

        if (result.isResultLike) {
            TrackerRuntime.ocrHits += 1
            consecutiveMisses = 0
            val words = result.matchedKeywords.joinToString(", ")

            if (resultArmed) {
                resultArmed = false
                TrackerRuntime.lastMessage = "Result-like OCR hit [$words]; capturing native frame"
                queueDiagnosticsSnapshot(
                    event = "ocr_result_hit",
                    extra = JSONObject().put("keywords", words)
                )
                beginNativeResultCapture()
                return
            }

            TrackerRuntime.lastMessage = "Result screen still present [$words]; duplicate suppressed"
        } else if (!resultArmed) {
            consecutiveMisses += 1
            if (consecutiveMisses >= REARM_AFTER_MISSES) {
                resultArmed = true
                consecutiveMisses = 0
                TrackerRuntime.lastMessage = "Result gate re-armed after screen changed"
            }
        } else {
            TrackerRuntime.lastMessage = "OCR probe OK; no result signature"
        }

        scheduleNextProbe(PROBE_INTERVAL_MS)
    }

    /** Expensive surface transition happens only after the song is already over. */
    private fun beginNativeResultCapture() {
        if (burstInProgress || shuttingDown.get()) return
        val display = virtualDisplay ?: return
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        burstInProgress = true
        burstFramesSeen = 0
        captureHandler.removeCallbacks(probeRunnable)
        captureHandler.removeCallbacks(burstTimeoutRunnable)

        runCatching { display.setSurface(null) }
        closeBurstReader()

        val reader = ImageReader.newInstance(
            sourceWidth,
            sourceHeight,
            PixelFormat.RGBA_8888,
            2
        )
        reader.setOnImageAvailableListener(::onBurstImageAvailable, captureHandler)
        burstReader = reader

        display.resize(sourceWidth, sourceHeight, densityDpi)
        display.setSurface(reader.surface)
        TrackerRuntime.captureSize = "${sourceWidth}x${sourceHeight} native result capture"

        captureHandler.postDelayed(burstTimeoutRunnable, BURST_TIMEOUT_MS)
    }

    private fun onBurstImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return

        if (!burstInProgress || shuttingDown.get()) {
            image.close()
            return
        }

        burstFramesSeen += 1

        // Drop the first post-resize frame. The second is less likely to contain a transition
        // artifact from switching the VirtualDisplay to native resolution.
        if (burstFramesSeen < BURST_FRAME_TARGET) {
            image.close()
            return
        }

        val bitmap = try {
            imageToBitmap(image)
        } finally {
            image.close()
        }

        val capturedAtMs = System.currentTimeMillis()
        restoreProbeSurface("Native result frame captured; saving PNG")

        analysisExecutor.execute {
            try {
                val file = captureStore.save(bitmap, capturedAtMs)
                TrackerRuntime.capturedScreens += 1
                TrackerRuntime.lastCapturePath = file.absolutePath
                TrackerRuntime.lastMessage = "Saved result screen: ${file.name}"
                queueDiagnosticsSnapshot(
                    event = "native_capture_saved",
                    extra = JSONObject()
                        .put("file", file.name)
                        .put("capturedAtMs", capturedAtMs)
                )
            } catch (error: Throwable) {
                TrackerRuntime.lastMessage =
                    "Result screenshot save failed: ${error.message ?: error.javaClass.simpleName}"
                queueDiagnosticsSnapshot(
                    event = "native_capture_save_error",
                    extra = JSONObject().put("message", TrackerRuntime.lastMessage)
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun restoreProbeSurface(message: String) {
        captureHandler.removeCallbacks(burstTimeoutRunnable)
        val display = virtualDisplay

        if (display != null) {
            runCatching { display.setSurface(null) }
        }

        closeBurstReader()

        val reader = probeReader
        if (display != null && reader != null && !shuttingDown.get()) {
            // Discard anything left from before the native-result burst.
            runCatching { reader.acquireLatestImage()?.close() }
            display.resize(probeWidth, probeHeight, densityDpi)
            display.setSurface(reader.surface)
        }

        burstInProgress = false
        burstFramesSeen = 0
        TrackerRuntime.captureSize = "${probeWidth}x${probeHeight} persistent OCR probe"
        TrackerRuntime.lastMessage = message
        scheduleNextProbe(350L)
    }

    private fun scheduleNextProbe(delayMs: Long) {
        captureHandler.removeCallbacks(probeRunnable)
        if (TrackerRuntime.active && !shuttingDown.get() && !burstInProgress) {
            captureHandler.postDelayed(probeRunnable, delayMs)
        }
    }

    private fun handleSourceResize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return

        sourceWidth = width
        sourceHeight = height

        // If a result capture is in progress, the updated dimensions will be used after it
        // returns to probe mode. Avoid tearing down the active native capture midway through.
        if (burstInProgress) return

        val (newProbeWidth, newProbeHeight) = probeDimensions(width, height)
        if (newProbeWidth == probeWidth && newProbeHeight == probeHeight) return

        rebuildProbeReader(width, height)
        queueDiagnosticsSnapshot(
            event = "probe_resized",
            extra = JSONObject()
                .put("sourceWidth", width)
                .put("sourceHeight", height)
                .put("probeWidth", probeWidth)
                .put("probeHeight", probeHeight)
        )
    }

    private fun rebuildProbeReader(sourceW: Int, sourceH: Int) {
        val display = virtualDisplay
        if (display != null) runCatching { display.setSurface(null) }

        probeReader?.close()
        probeReader = null
        createProbeReader(sourceW, sourceH)

        val reader = probeReader
        if (display != null && reader != null) {
            display.resize(probeWidth, probeHeight, densityDpi)
            display.setSurface(reader.surface)
        }

        TrackerRuntime.captureSize = "${probeWidth}x${probeHeight} persistent OCR probe"
        TrackerRuntime.lastMessage = "Probe resized after captured-content size changed"
        scheduleNextProbe(250L)
    }

    private fun createProbeReader(sourceW: Int, sourceH: Int) {
        val (width, height) = probeDimensions(sourceW, sourceH)
        probeWidth = width
        probeHeight = height

        // No image listener here on purpose. We pull acquireLatestImage() only on the probe
        // cadence instead of waking app code at display refresh rate.
        probeReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )
    }

    private fun closeBurstReader() {
        burstReader?.setOnImageAvailableListener(null, null)
        runCatching { burstReader?.close() }
        burstReader = null
    }

    private fun probeDimensions(sourceW: Int, sourceH: Int): Pair<Int, Int> {
        val longest = max(sourceW, sourceH).coerceAtLeast(1)
        val scale = (PROBE_LONG_EDGE_PX.toFloat() / longest.toFloat()).coerceAtMost(1f)
        val width = (sourceW * scale).roundToInt().coerceAtLeast(2)
        val height = (sourceH * scale).roundToInt().coerceAtLeast(2)
        return width to height
    }

    private fun currentDisplayBounds(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        if (paddedWidth == image.width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun queueDiagnosticsSnapshot(event: String? = null, extra: JSONObject? = null) {
        if (!ownsSession) return
        val snapshot = diagnosticStore.snapshotFromRuntime()
        runCatching {
            diagnosticExecutor.execute {
                diagnosticStore.saveSnapshot(snapshot)
                if (event != null) diagnosticStore.appendEvent(event, snapshot, extra)
            }
        }
    }

    private fun saveDiagnosticsNow(event: String? = null, appendEvent: Boolean = false) {
        if (!ownsSession) return
        val snapshot = diagnosticStore.snapshotFromRuntime()
        diagnosticStore.saveSnapshot(snapshot)
        if (appendEvent && event != null) {
            diagnosticStore.appendEvent(event, snapshot)
        }
    }

    private fun shutdownProjection(requestProjectionStop: Boolean) {
        if (!shuttingDown.compareAndSet(false, true)) return

        TrackerRuntime.active = false
        probeInFlight = false
        probeStartedAtNs = 0L
        burstInProgress = false

        if (::captureHandler.isInitialized) {
            captureHandler.removeCallbacks(probeRunnable)
            captureHandler.removeCallbacks(burstTimeoutRunnable)
            captureHandler.removeCallbacks(diagnosticRunnable)
        }

        runCatching { virtualDisplay?.setSurface(null) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        probeReader?.close()
        probeReader = null
        closeBurstReader()

        val projection = mediaProjection
        mediaProjection = null
        if (projection != null) {
            runCatching { projection.unregisterCallback(projectionCallback) }
            if (requestProjectionStop) runCatching { projection.stop() }
        }

        TrackerRuntime.captureSize = "-"
        if (ownsSession) {
            saveDiagnosticsNow("session_stop", appendEvent = true)
            ownsSession = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "RenderMyMind tracking session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while lightweight result-screen tracking is active"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle("RenderMyMind Alpha active")
            .setContentText("Persistent low-res OCR probe is watching for result screens")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .addAction(Notification.Action.Builder(null, "STOP", stopIntent).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.rhythmtracker.action.START"
        const val ACTION_STOP = "com.example.rhythmtracker.action.STOP"
        const val ACTION_FLUSH_DIAGNOSTICS = "com.example.rhythmtracker.action.FLUSH_DIAGNOSTICS"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"

        private const val CHANNEL_ID = "rhythm_tracking"
        private const val NOTIFICATION_ID = 7101

        private const val PROBE_LONG_EDGE_PX = 480
        private const val PROBE_INTERVAL_MS = 900L
        private const val PROBE_EMPTY_RETRY_MS = 120L
        private const val BURST_TIMEOUT_MS = 1_500L
        private const val BURST_FRAME_TARGET = 2
        private const val REARM_AFTER_MISSES = 2

        private const val DIAGNOSTIC_SNAPSHOT_INTERVAL_MS = 15_000L
        private const val SLOW_OCR_LOG_THRESHOLD_MS = 200L
        private const val SLOW_OCR_LOG_COOLDOWN_MS = 30_000L
    }
}
