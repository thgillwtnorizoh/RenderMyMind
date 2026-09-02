package com.example.rhythmtracker.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import com.example.rhythmtracker.data.FileResultStore
import com.example.rhythmtracker.data.PlayResult
import com.example.rhythmtracker.data.ResultCaptureStore
import com.example.rhythmtracker.debug.DebugBus
import com.example.rhythmtracker.debug.DebugSnapshot
import com.example.rhythmtracker.detection.ArcaeaResultDetector
import com.example.rhythmtracker.game.arcaea.ArcaeaChartIndex
import com.example.rhythmtracker.game.arcaea.ArcaeaDatabaseStore
import com.example.rhythmtracker.identity.ResultIdentity
import com.example.rhythmtracker.identity.VisualFingerprint
import com.example.rhythmtracker.parser.ArcaeaJudgementReconciler
import com.example.rhythmtracker.parser.ArcaeaResultParser
import com.example.rhythmtracker.state.ResultStateMachine
import com.example.rhythmtracker.vision.MlKitOcrEngine
import com.example.rhythmtracker.vision.VisionStage
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2 orchestration service.
 *
 * MediaProjection, OCR, detection, identity, state, parsing, debug and persistence are separate
 * components. This service only moves data between them and owns Android lifecycle/foreground work.
 */
class CaptureService : Service() {
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var frameSource: ProjectionFrameSource
    private lateinit var captureStore: ResultCaptureStore
    private lateinit var resultStore: FileResultStore
    private lateinit var diagnosticStore: DiagnosticStore

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val ocrEngine = MlKitOcrEngine()
    private val detector = ArcaeaResultDetector()
    private val parser = ArcaeaResultParser(detector)
    private val stateMachine = ResultStateMachine()

    private var mediaProjection: MediaProjection? = null
    private var chartIndex: ArcaeaChartIndex? = null
    private var probeInFlight = false
    private var probeStartedAtNs = 0L
    private var ownsSession = false
    private var captureGeneration = 0L
    private var latestCaptureGeneration = 0L
    private val shuttingDown = AtomicBoolean(false)

    private val probeRunnable = Runnable { pullProbe() }

    private val diagnosticRunnable = object : Runnable {
        override fun run() {
            if (!TrackerRuntime.active || shuttingDown.get() || !ownsSession) return
            queueDiagnosticsSnapshot()
            captureHandler.postDelayed(this, DIAGNOSTIC_INTERVAL_MS)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            TrackerRuntime.lastMessage = "Projection stopped by Android/user"
            shutdownProjection(requestProjectionStop = false)
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            captureHandler.post {
                if (!shuttingDown.get()) {
                    frameSource.updateSourceSize(width, height)
                    TrackerRuntime.captureSize = frameSource.probeSizeLabel()
                    queueDiagnosticsSnapshot(
                        event = "source_resized",
                        extra = JSONObject().put("width", width).put("height", height)
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("rendermymind-v2-capture")
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
        frameSource = ProjectionFrameSource(captureHandler)
        captureStore = ResultCaptureStore(this)
        resultStore = FileResultStore(this)
        diagnosticStore = DiagnosticStore(this)
        chartIndex = runCatching { ArcaeaDatabaseStore(this).load() }.getOrNull()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!TrackerRuntime.active) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                    startProjection(intent)
                }
            }
            ACTION_STOP -> stopSelf()
            ACTION_FLUSH_DIAGNOSTICS -> if (ownsSession) saveDiagnosticsNow("manual_flush")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34 && TrackerRuntime.active) {
            val (width, height) = currentDisplayBounds()
            captureHandler.post { frameSource.updateSourceSize(width, height) }
        }
    }

    override fun onDestroy() {
        shutdownProjection(requestProjectionStop = true)
        ocrEngine.close()
        analysisExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        if (::captureThread.isInitialized) captureThread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startProjection(intent: Intent) {
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

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            TrackerRuntime.lastMessage = "Android returned no MediaProjection instance"
            stopSelf()
            return
        }

        mediaProjection = projection
        projection.registerCallback(projectionCallback, captureHandler)
        val (width, height) = currentDisplayBounds()

        runCatching {
            frameSource.start(
                mediaProjection = projection,
                width = width,
                height = height,
                dpi = resources.configuration.densityDpi
            )
        }.onFailure { error ->
            TrackerRuntime.lastMessage = "Projection setup failed: ${error.message ?: error.javaClass.simpleName}"
            stopSelf()
            return
        }

        shuttingDown.set(false)
        probeInFlight = false
        probeStartedAtNs = 0L
        captureGeneration = 0L
        latestCaptureGeneration = 0L
        stateMachine.reset()
        DebugBus.clear()

        val now = System.currentTimeMillis()
        TrackerRuntime.resetForNewSession(UUID.randomUUID().toString(), now)
        TrackerRuntime.active = true
        TrackerRuntime.captureSize = frameSource.probeSizeLabel()
        TrackerRuntime.lastMessage = "v2 pipeline active"
        ownsSession = true

        saveDiagnosticsNow("session_start")
        captureHandler.removeCallbacks(diagnosticRunnable)
        captureHandler.postDelayed(diagnosticRunnable, DIAGNOSTIC_INTERVAL_MS)
        scheduleProbe(250L)
    }

    private fun pullProbe() {
        if (!TrackerRuntime.active || shuttingDown.get() || probeInFlight || frameSource.isNativeCaptureActive()) {
            return
        }

        val bitmap = frameSource.acquireLatestProbe()
        if (bitmap == null) {
            scheduleProbe(PROBE_EMPTY_RETRY_MS)
            return
        }

        val fingerprint = VisualFingerprint.from(bitmap)
        TrackerRuntime.sampledFrames += 1
        TrackerRuntime.ocrProbes += 1
        TrackerRuntime.lastSampleAtMs = System.currentTimeMillis()
        probeInFlight = true
        probeStartedAtNs = SystemClock.elapsedRealtimeNanos()

        ocrEngine.recognizeLight(bitmap, analysisExecutor) { result ->
            bitmap.recycle()
            captureHandler.post { handleLightOcr(result, fingerprint) }
        }
    }

    private fun handleLightOcr(result: Result<List<com.example.rhythmtracker.vision.VisionLine>>, fingerprint: Long) {
        if (shuttingDown.get()) return
        probeInFlight = false
        recordOcrDuration()

        result.onFailure { error ->
            TrackerRuntime.lastMessage = "Light OCR failed: ${error.message ?: error.javaClass.simpleName}"
            queueDiagnosticsSnapshot(
                event = "light_ocr_error",
                extra = JSONObject().put("message", TrackerRuntime.lastMessage)
            )
            scheduleProbe(stateMachine.recommendedProbeDelayMs())
            return
        }

        val lines = result.getOrThrow()
        val detection = detector.detect(lines, fingerprint)
        TrackerRuntime.lastOcrText = detection.preview.ifBlank { "-" }
        if (detection.signal.present) TrackerRuntime.ocrHits += 1

        val update = stateMachine.observe(detection.signal)
        DebugBus.publish(
            DebugSnapshot(
                visible = update.resultVisible,
                stage = VisionStage.LIGHT,
                stateLabel = update.stateLabel,
                regions = if (update.resultVisible) detection.regions else emptyList()
            )
        )

        if (update.captureRequested) {
            val words = detection.signal.anchors.joinToString(", ")
            TrackerRuntime.lastMessage = "Accepted distinct result [$words]; native capture"
            queueDiagnosticsSnapshot(
                event = "result_accepted",
                extra = JSONObject()
                    .put("anchors", words)
                    .put("state", update.stateLabel)
                    .put("strength", detection.signal.strength.toDouble())
            )
            beginNativeCapture(update.acceptedIdentity)
            return
        }

        TrackerRuntime.lastMessage = when {
            detection.signal.present -> "Result present; state=${update.stateLabel}"
            update.resultVisible -> "Holding live result through transient OCR miss"
            else -> "No result screen"
        }
        scheduleProbe(stateMachine.recommendedProbeDelayMs())
    }

    private fun beginNativeCapture(identity: ResultIdentity?) {
        captureGeneration += 1
        val generation = captureGeneration
        latestCaptureGeneration = generation
        TrackerRuntime.captureSize = frameSource.nativeSizeLabel()

        val started = frameSource.captureNative { frameResult ->
            captureHandler.post {
                TrackerRuntime.captureSize = frameSource.probeSizeLabel()
                frameResult.onSuccess { bitmap ->
                    handleNativeFrame(bitmap, generation, identity)
                }.onFailure { error ->
                    TrackerRuntime.lastMessage = "Native capture failed: ${error.message ?: error.javaClass.simpleName}"
                    queueDiagnosticsSnapshot(
                        event = "native_capture_error",
                        extra = JSONObject().put("message", TrackerRuntime.lastMessage)
                    )
                }
                scheduleProbe(220L)
            }
        }

        if (!started) {
            TrackerRuntime.captureSize = frameSource.probeSizeLabel()
            TrackerRuntime.lastMessage = "Native capture request rejected because source was busy"
            scheduleProbe(stateMachine.recommendedProbeDelayMs())
        }
    }

    private fun handleNativeFrame(bitmap: android.graphics.Bitmap, generation: Long, identity: ResultIdentity?) {
        val capturedAtMs = System.currentTimeMillis()
        TrackerRuntime.capturedScreens += 1

        ioExecutor.execute {
            val fileResult = runCatching { captureStore.save(bitmap, capturedAtMs) }
            fileResult.onSuccess { file ->
                TrackerRuntime.lastCapturePath = file.absolutePath
                queueDiagnosticsSnapshot(
                    event = "native_capture_saved",
                    extra = JSONObject().put("file", file.name).put("generation", generation)
                )
            }.onFailure { error ->
                TrackerRuntime.lastMessage = "Screenshot save failed: ${error.message ?: error.javaClass.simpleName}"
            }

            val screenshotPath = fileResult.getOrNull()?.absolutePath
            ocrEngine.recognizeNative(bitmap, analysisExecutor) { ocrResult ->
                handleNativeOcr(ocrResult, capturedAtMs, screenshotPath, generation, identity)
            }
            bitmap.recycle()
        }
    }

    private fun handleNativeOcr(
        result: Result<List<com.example.rhythmtracker.vision.VisionLine>>,
        capturedAtMs: Long,
        screenshotPath: String?,
        generation: Long,
        identity: ResultIdentity?
    ) {
        result.onFailure { error ->
            TrackerRuntime.lastMessage = "Native OCR failed: ${error.message ?: error.javaClass.simpleName}"
            queueDiagnosticsSnapshot(
                event = "native_ocr_error",
                extra = JSONObject().put("message", TrackerRuntime.lastMessage)
            )
            return
        }

        val lines = result.getOrThrow()
        val parsed = parser.parse(lines)
        val resolution = parsed.title?.let { title ->
            chartIndex?.resolveResultTitle(
                rawTitle = title,
                displayedDifficulty = parsed.displayedDifficulty,
                hiddenOnScreen = parsed.chartHiddenOnScreen
            )
        }
        val judgements = ArcaeaJudgementReconciler.reconcile(
            lines = lines,
            initialPure = parsed.pure,
            initialFar = parsed.far,
            initialLost = parsed.lost,
            noteCount = resolution?.chart?.notes
        )
        val confidence = judgements.adjustConfidence(parsed.confidence)

        val playResult = PlayResult(
            id = UUID.randomUUID().toString(),
            capturedAtMs = capturedAtMs,
            gameId = "arcaea",
            songId = resolution?.song?.id,
            difficulty = resolution?.chart?.difficulty,
            title = parsed.title,
            artist = parsed.artist,
            trackState = parsed.trackState,
            score = parsed.score ?: identity?.score,
            pure = judgements.pure,
            far = judgements.far,
            lost = judgements.lost,
            confidence = confidence,
            screenshotPath = screenshotPath,
            rawOcr = parsed.rawText,
            source = "media-projection-v2"
        )

        ioExecutor.execute {
            runCatching { resultStore.append(playResult) }
                .onSuccess { TrackerRuntime.savedResults += 1 }
                .onFailure { error ->
                    TrackerRuntime.lastMessage = "Result save failed: ${error.message ?: error.javaClass.simpleName}"
                }
        }

        TrackerRuntime.lastOcrText = buildString {
            append(parsed.title ?: "?")
            append(" | ")
            append(parsed.trackState ?: "?")
            append(" | ")
            append(parsed.score ?: identity?.score ?: "?")
            if (judgements.pure != null || judgements.far != null || judgements.lost != null) {
                append(" | P/F/L ")
                append(judgements.pure ?: "?")
                append('/')
                append(judgements.far ?: "?")
                append('/')
                append(judgements.lost ?: "?")
                append(" | notes ")
                append(judgements.checksumDescription())
            }
        }
        TrackerRuntime.lastMessage = "Parsed distinct result; confidence=${"%.2f".format(confidence)}"

        // A slow old native pass is allowed to persist its result, but not overwrite the visual
        // debugger after a newer result has already been accepted.
        if (generation == latestCaptureGeneration) {
            DebugBus.publish(
                DebugSnapshot(
                    visible = true,
                    stage = VisionStage.NATIVE,
                    stateLabel = "LIVE",
                    regions = parsed.regions
                )
            )
        }
        queueDiagnosticsSnapshot(
            event = "native_result_parsed",
            extra = JSONObject()
                .put("generation", generation)
                .put("title", parsed.title)
                .put("score", parsed.score)
                .put("confidence", confidence.toDouble())
                .put("note_count", resolution?.chart?.notes)
                .put("judgement_checksum", judgements.checksumDescription())
        )
    }

    private fun recordOcrDuration() {
        val started = probeStartedAtNs
        probeStartedAtNs = 0L
        if (started == 0L) return
        val duration = ((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L).coerceAtLeast(0L)
        TrackerRuntime.lastOcrDurationMs = duration
        TrackerRuntime.totalOcrDurationMs += duration
        if (duration > TrackerRuntime.maxOcrDurationMs) TrackerRuntime.maxOcrDurationMs = duration
    }

    private fun scheduleProbe(delayMs: Long) {
        captureHandler.removeCallbacks(probeRunnable)
        if (TrackerRuntime.active && !shuttingDown.get() && !frameSource.isNativeCaptureActive()) {
            captureHandler.postDelayed(probeRunnable, delayMs)
        }
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

    private fun queueDiagnosticsSnapshot(event: String? = null, extra: JSONObject? = null) {
        if (!ownsSession) return
        val snapshot = diagnosticStore.snapshotFromRuntime()
        runCatching {
            ioExecutor.execute {
                diagnosticStore.saveSnapshot(snapshot)
                if (event != null) diagnosticStore.appendEvent(event, snapshot, extra)
            }
        }
    }

    private fun saveDiagnosticsNow(event: String) {
        if (!ownsSession) return
        val snapshot = diagnosticStore.snapshotFromRuntime()
        diagnosticStore.saveSnapshot(snapshot)
        diagnosticStore.appendEvent(event, snapshot)
    }

    private fun shutdownProjection(requestProjectionStop: Boolean) {
        if (!shuttingDown.compareAndSet(false, true)) return
        TrackerRuntime.active = false
        probeInFlight = false
        stateMachine.reset()
        DebugBus.clear()

        if (::captureHandler.isInitialized) {
            captureHandler.removeCallbacks(probeRunnable)
            captureHandler.removeCallbacks(diagnosticRunnable)
        }
        if (::frameSource.isInitialized) frameSource.close()

        val projection = mediaProjection
        mediaProjection = null
        if (projection != null) {
            runCatching { projection.unregisterCallback(projectionCallback) }
            if (requestProjectionStop) runCatching { projection.stop() }
        }

        TrackerRuntime.captureSize = "-"
        if (ownsSession) {
            saveDiagnosticsNow("session_stop")
            ownsSession = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "RenderMyMind tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while result-screen tracking is active"
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
            .setContentText("V2 result pipeline is watching for Arcaea results")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
        private const val PROBE_EMPTY_RETRY_MS = 100L
        private const val DIAGNOSTIC_INTERVAL_MS = 15_000L
    }
}
