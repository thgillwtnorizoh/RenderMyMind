package com.example.rhythmtracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.example.rhythmtracker.capture.CaptureService
import com.example.rhythmtracker.data.DiagnosticSnapshot
import com.example.rhythmtracker.data.DiagnosticStore
import com.example.rhythmtracker.data.FileResultStore
import com.example.rhythmtracker.data.PlayResult
import com.example.rhythmtracker.game.arcaea.ArcaeaChartIndex
import com.example.rhythmtracker.game.arcaea.ArcaeaDatabaseStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
    private lateinit var diagnosticStore: DiagnosticStore
    private lateinit var resultStore: FileResultStore
    private lateinit var databaseStore: ArcaeaDatabaseStore
    private var persistedSnapshot: DiagnosticSnapshot? = null

    @Volatile
    private var databaseStatus: String = "not imported"

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingCaptureStart = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderRuntimeState()
            uiHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MediaProjectionManager::class.java)
        diagnosticStore = DiagnosticStore(this)
        resultStore = FileResultStore(this)
        databaseStore = ArcaeaDatabaseStore(this)
        persistedSnapshot = diagnosticStore.readSnapshot()
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            beginTrackingFlow()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            })
        }

        findViewById<Button>(R.id.importDatabaseButton).setOnClickListener {
            requestDatabaseImport()
        }

        findViewById<Button>(R.id.exportResultsButton).setOnClickListener {
            requestResultsExport()
        }

        findViewById<Button>(R.id.exportDiagnosticsButton).setOnClickListener {
            requestDiagnosticsExport()
        }

        findViewById<Button>(R.id.testRecordButton).setOnClickListener {
            val result = PlayResult.manualTest()
            resultStore.append(result)
            TrackerRuntime.savedResults += 1
            TrackerRuntime.lastMessage = "Manual test result appended to results.jsonl"
        }

        refreshDatabaseStatus()
    }

    override fun onResume() {
        super.onResume()
        persistedSnapshot = diagnosticStore.readSnapshot()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun beginTrackingFlow() {
        if (TrackerRuntime.active) {
            TrackerRuntime.lastMessage = "Tracking is already active"
            return
        }

        if (!databaseStore.exists()) {
            TrackerRuntime.lastMessage =
                "Import cheeseburger-merged.json first so captured results can resolve charts"
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCaptureStart = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
            return
        }

        requestCaptureConsent()
    }

    private fun requestCaptureConsent() {
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    private fun requestDatabaseImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQUEST_IMPORT_DATABASE)
    }

    private fun requestResultsExport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "RenderMyMind-results-$stamp.jsonl")
        }
        startActivityForResult(intent, REQUEST_EXPORT_RESULTS)
    }

    private fun requestDiagnosticsExport() {
        if (TrackerRuntime.active) {
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_FLUSH_DIAGNOSTICS
            })
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "RenderMyMind-diagnostics-$stamp.jsonl")
        }
        startActivityForResult(intent, REQUEST_EXPORT_DIAGNOSTICS)
    }

    @Deprecated("Prototype uses the small, dependency-free Activity result API on purpose.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CAPTURE -> handleCaptureResult(resultCode, data)
            REQUEST_IMPORT_DATABASE -> handleDatabaseImport(resultCode, data?.data)
            REQUEST_EXPORT_RESULTS -> handleResultsExport(resultCode, data?.data)
            REQUEST_EXPORT_DIAGNOSTICS -> handleDiagnosticsExport(resultCode, data?.data)
        }
    }

    private fun handleCaptureResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) {
            TrackerRuntime.lastMessage = "Screen capture permission was not granted"
            return
        }

        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(serviceIntent)
        TrackerRuntime.lastMessage = "Starting RenderMyMind Alpha projection service…"
    }

    private fun handleDatabaseImport(resultCode: Int, uri: Uri?) {
        if (resultCode != RESULT_OK || uri == null) return

        databaseStatus = "importing / validating…"
        TrackerRuntime.lastMessage = "Importing Arcaea database…"
        renderRuntimeState()

        Thread {
            val outcome = runCatching {
                val input = contentResolver.openInputStream(uri)
                    ?: error("Android returned no readable input stream")
                databaseStore.importFrom(input)
            }

            runOnUiThread {
                outcome.onSuccess { index ->
                    databaseStatus = databaseStatus(index)
                    TrackerRuntime.lastMessage =
                        "Arcaea database imported: ${index.songCount} songs / ${index.chartCount} charts"
                }.onFailure { error ->
                    databaseStatus = "import failed"
                    TrackerRuntime.lastMessage =
                        "Database import failed: ${error.message ?: error.javaClass.simpleName}"
                }
                renderRuntimeState()
            }
        }.start()
    }

    private fun handleResultsExport(resultCode: Int, uri: Uri?) {
        if (resultCode != RESULT_OK || uri == null) return

        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                resultStore.exportTo(output)
            } ?: error("Android returned no writable output stream")
            TrackerRuntime.lastMessage = "Results exported successfully"
        } catch (error: Throwable) {
            TrackerRuntime.lastMessage =
                "Results export failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun handleDiagnosticsExport(resultCode: Int, uri: Uri?) {
        if (resultCode != RESULT_OK || uri == null) return

        try {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                diagnosticStore.exportTo(output)
            } ?: error("Android returned no writable output stream")
            TrackerRuntime.lastMessage = "Diagnostics exported successfully"
        } catch (error: Throwable) {
            TrackerRuntime.lastMessage =
                "Diagnostics export failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && pendingCaptureStart) {
            pendingCaptureStart = false
            requestCaptureConsent()
        }
    }

    private fun refreshDatabaseStatus() {
        if (!databaseStore.exists()) {
            databaseStatus = "not imported"
            return
        }

        databaseStatus = "loading…"
        Thread {
            val status = runCatching { databaseStatus(databaseStore.load()) }
                .getOrElse { error ->
                    "invalid: ${error.message ?: error.javaClass.simpleName}"
                }
            runOnUiThread {
                databaseStatus = status
                renderRuntimeState()
            }
        }.start()
    }

    private fun databaseStatus(index: ArcaeaChartIndex): String =
        "${index.songCount} songs / ${index.chartCount} charts / ${index.knownConstantCount} CC"

    private fun renderRuntimeState() {
        if (TrackerRuntime.active) {
            statusText.text = liveRuntimeText()
            return
        }

        val saved = persistedSnapshot ?: diagnosticStore.readSnapshot().also {
            persistedSnapshot = it
        }

        statusText.text = if (saved != null) {
            persistedSnapshotText(saved)
        } else {
            buildString {
                appendLine("ARCAEA DATABASE")
                appendLine("database         : $databaseStatus")
                appendLine("results JSONL    : ${resultStore.sizeBytes()} bytes")
                appendLine()
                append("No persisted tracking session yet.\n\nStart tracking to create diagnostics.")
            }
        }
    }

    private fun liveRuntimeText(): String {
        val sampleTime = formatTime(TrackerRuntime.lastSampleAtMs)
        return buildString {
            appendLine("LIVE SESSION")
            appendLine("database         : $databaseStatus")
            appendLine("session          : ${TrackerRuntime.sessionId}")
            appendLine("tracking         : ${TrackerRuntime.active}")
            appendLine("capture mode     : ${TrackerRuntime.captureSize}")
            appendLine("frames pulled    : ${TrackerRuntime.sampledFrames}")
            appendLine("OCR probes       : ${TrackerRuntime.ocrProbes}")
            appendLine("OCR result hits  : ${TrackerRuntime.ocrHits}")
            appendLine("OCR last / avg   : ${TrackerRuntime.lastOcrDurationMs} / ${TrackerRuntime.averageOcrDurationMs} ms")
            appendLine("OCR max          : ${TrackerRuntime.maxOcrDurationMs} ms")
            appendLine("screens captured : ${TrackerRuntime.capturedScreens}")
            appendLine("saved results    : ${TrackerRuntime.savedResults}")
            appendLine("last sample      : $sampleTime")
            appendLine("last OCR text    : ${TrackerRuntime.lastOcrText}")
            appendLine("last PNG         : ${TrackerRuntime.lastCapturePath}")
            appendLine()
            append(TrackerRuntime.lastMessage)
        }
    }

    private fun persistedSnapshotText(snapshot: DiagnosticSnapshot): String = buildString {
        appendLine("PERSISTED LAST SESSION")
        appendLine("database         : $databaseStatus")
        appendLine("results JSONL    : ${resultStore.sizeBytes()} bytes")
        appendLine("version          : ${snapshot.version}")
        appendLine("session          : ${snapshot.sessionId}")
        appendLine("started          : ${formatDateTime(snapshot.sessionStartedAtMs)}")
        appendLine("snapshot saved   : ${formatDateTime(snapshot.snapshotAtMs)}")
        appendLine("tracking at save : ${snapshot.active}")
        appendLine("capture mode     : ${snapshot.captureMode}")
        appendLine("frames pulled    : ${snapshot.sampledFrames}")
        appendLine("OCR probes       : ${snapshot.ocrProbes}")
        appendLine("OCR result hits  : ${snapshot.ocrHits}")
        appendLine("OCR last / avg   : ${snapshot.lastOcrDurationMs} / ${snapshot.averageOcrDurationMs} ms")
        appendLine("OCR max          : ${snapshot.maxOcrDurationMs} ms")
        appendLine("screens captured : ${snapshot.capturedScreens}")
        appendLine("saved results    : ${snapshot.savedResults}")
        appendLine("last sample      : ${formatTime(snapshot.lastSampleAtMs)}")
        appendLine("last OCR text    : ${snapshot.lastOcrText}")
        appendLine("last PNG         : ${snapshot.lastCapturePath}")
        appendLine()
        append(snapshot.lastMessage)
    }

    private fun formatTime(timestampMs: Long): String {
        if (timestampMs == 0L) return "-"
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
    }

    private fun formatDateTime(timestampMs: Long): String {
        if (timestampMs == 0L) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    }

    companion object {
        private const val REQUEST_CAPTURE = 4101
        private const val REQUEST_NOTIFICATIONS = 4102
        private const val REQUEST_EXPORT_DIAGNOSTICS = 4103
        private const val REQUEST_IMPORT_DATABASE = 4104
        private const val REQUEST_EXPORT_RESULTS = 4105
    }
}
