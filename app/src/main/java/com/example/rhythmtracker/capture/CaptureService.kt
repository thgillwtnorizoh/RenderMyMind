package com.example.rhythmtracker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

class CaptureService : Service() {

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val detector = StabilityGateDetector()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var detectorWidth = 0
    private var detectorHeight = 0
    private var densityDpi = 0

    private var waitingForFrame = false
    private val shuttingDown = AtomicBoolean(false)

    private val sampleRunnable = Runnable { beginSamplePulse() }
    private val sampleTimeoutRunnable = Runnable {
        if (!waitingForFrame) return@Runnable
        waitingForFrame = false
        runCatching { virtualDisplay?.setSurface(null) }
        TrackerRuntime.lastMessage = "Sample pulse timed out; retrying"
        scheduleNextSample(SAMPLE_INTERVAL_MS)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            TrackerRuntime.lastMessage = "Projection stopped by Android/user"
            shutdownProjection(requestProjectionStop = false)
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            resizeDetectorSurface(width, height)
        }
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("rhythm-capture")
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
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
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34 && TrackerRuntime.active) {
            captureHandler.post {
                val (w, h) = currentDisplayBounds()
                resizeDetectorSurface(w, h)
            }
        }
    }

    override fun onDestroy() {
        shutdownProjection(requestProjectionStop = true)
        analysisExecutor.shutdownNow()
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
        mediaProjection = projection
        projection.registerCallback(projectionCallback, captureHandler)

        densityDpi = resources.configuration.densityDpi
        val (screenWidth, screenHeight) = currentDisplayBounds()
        val (w, h) = detectorDimensions(screenWidth, screenHeight)
        createOrReplaceImageReader(w, h)

        virtualDisplay = projection.createVirtualDisplay(
            "RhythmTrackerDetector",
            detectorWidth,
            detectorHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
            captureHandler
        )

        if (virtualDisplay == null) {
            TrackerRuntime.lastMessage = "createVirtualDisplay() returned null"
            stopSelf()
            return
        }

        shuttingDown.set(false)
        TrackerRuntime.active = true
        TrackerRuntime.lastMessage = "Tracking active; detector sampling once per second"
        scheduleNextSample(150L)
    }

    private fun beginSamplePulse() {
        if (!TrackerRuntime.active || shuttingDown.get() || waitingForFrame) return
        val reader = imageReader ?: return
        val display = virtualDisplay ?: return

        waitingForFrame = true
        display.setSurface(reader.surface)
        captureHandler.removeCallbacks(sampleTimeoutRunnable)
        captureHandler.postDelayed(sampleTimeoutRunnable, SAMPLE_TIMEOUT_MS)
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return

        if (!waitingForFrame || shuttingDown.get()) {
            image.close()
            return
        }

        waitingForFrame = false
        captureHandler.removeCallbacks(sampleTimeoutRunnable)
        runCatching { virtualDisplay?.setSurface(null) }

        val bitmap = try {
            imageToBitmap(image)
        } finally {
            image.close()
        }

        TrackerRuntime.sampledFrames += 1
        TrackerRuntime.lastSampleAtMs = System.currentTimeMillis()

        analysisExecutor.execute {
            try {
                val detection = detector.inspect(bitmap)
                if (detection.stable) {
                    TrackerRuntime.stableCandidates += 1
                    TrackerRuntime.lastMessage =
                        "Stable-screen candidate ${(detection.confidence * 100).roundToInt()}% " +
                            "(generic gate only; NOT saved as a score)"
                } else {
                    TrackerRuntime.lastMessage =
                        "Sample OK; visual delta=${detection.hammingDistance}/64"
                }
            } finally {
                bitmap.recycle()
            }
        }

        scheduleNextSample(SAMPLE_INTERVAL_MS)
    }

    private fun scheduleNextSample(delayMs: Long) {
        captureHandler.removeCallbacks(sampleRunnable)
        if (TrackerRuntime.active && !shuttingDown.get()) {
            captureHandler.postDelayed(sampleRunnable, delayMs)
        }
    }

    private fun resizeDetectorSurface(sourceWidth: Int, sourceHeight: Int) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || shuttingDown.get()) return
        val (newWidth, newHeight) = detectorDimensions(sourceWidth, sourceHeight)
        if (newWidth == detectorWidth && newHeight == detectorHeight) return

        waitingForFrame = false
        captureHandler.removeCallbacks(sampleTimeoutRunnable)
        runCatching { virtualDisplay?.setSurface(null) }

        createOrReplaceImageReader(newWidth, newHeight)
        virtualDisplay?.resize(detectorWidth, detectorHeight, densityDpi)

        TrackerRuntime.lastMessage = "Capture resized to ${detectorWidth}x${detectorHeight}"
        scheduleNextSample(100L)
    }

    private fun createOrReplaceImageReader(width: Int, height: Int) {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()

        detectorWidth = width
        detectorHeight = height
        TrackerRuntime.captureSize = "${width}x${height} detector"

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener(::onImageAvailable, captureHandler)
        }
    }

    private fun detectorDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val longest = max(sourceWidth, sourceHeight).coerceAtLeast(1)
        val scale = (DETECTOR_LONG_EDGE_PX.toFloat() / longest.toFloat()).coerceAtMost(1f)
        val w = (sourceWidth * scale).roundToInt().coerceAtLeast(2)
        val h = (sourceHeight * scale).roundToInt().coerceAtLeast(2)
        return w to h
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

    private fun shutdownProjection(requestProjectionStop: Boolean) {
        if (!shuttingDown.compareAndSet(false, true)) return

        TrackerRuntime.active = false
        waitingForFrame = false

        if (::captureHandler.isInitialized) {
            captureHandler.removeCallbacks(sampleRunnable)
            captureHandler.removeCallbacks(sampleTimeoutRunnable)
        }

        runCatching { virtualDisplay?.setSurface(null) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        runCatching { imageReader?.close() }
        imageReader = null

        val projection = mediaProjection
        mediaProjection = null
        if (projection != null) {
            runCatching { projection.unregisterCallback(projectionCallback) }
            if (requestProjectionStop) runCatching { projection.stop() }
        }

        TrackerRuntime.captureSize = "-"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Rhythm tracking session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while screen-result tracking is active"
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
            .setContentTitle("Rhythm tracker active")
            .setContentText("Low-rate result-screen detector is sampling")
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
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"

        private const val CHANNEL_ID = "rhythm_tracking"
        private const val NOTIFICATION_ID = 7101

        private const val DETECTOR_LONG_EDGE_PX = 640
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val SAMPLE_TIMEOUT_MS = 350L
    }
}
