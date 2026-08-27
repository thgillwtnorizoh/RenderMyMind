package com.example.rhythmtracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import com.example.rhythmtracker.capture.CaptureService
import com.example.rhythmtracker.data.FileResultStore
import com.example.rhythmtracker.data.PlayResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var statusText: TextView
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
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            beginTrackingFlow()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            })
        }

        findViewById<Button>(R.id.testRecordButton).setOnClickListener {
            val result = PlayResult.manualTest()
            FileResultStore(this).append(result)
            TrackerRuntime.savedResults += 1
            TrackerRuntime.lastMessage = "Manual test result appended to results.jsonl"
        }
    }

    override fun onResume() {
        super.onResume()
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

    @Deprecated("Prototype uses the small, dependency-free Activity result API on purpose.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return

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
        TrackerRuntime.lastMessage = "Starting projection service…"
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

    private fun renderRuntimeState() {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val sampleTime = if (TrackerRuntime.lastSampleAtMs == 0L) {
            "-"
        } else {
            fmt.format(Date(TrackerRuntime.lastSampleAtMs))
        }

        statusText.text = buildString {
            appendLine("tracking       : ${TrackerRuntime.active}")
            appendLine("capture size   : ${TrackerRuntime.captureSize}")
            appendLine("sampled frames : ${TrackerRuntime.sampledFrames}")
            appendLine("stable screens : ${TrackerRuntime.stableCandidates}")
            appendLine("saved results  : ${TrackerRuntime.savedResults}")
            appendLine("last sample    : $sampleTime")
            appendLine()
            append(TrackerRuntime.lastMessage)
        }
    }

    companion object {
        private const val REQUEST_CAPTURE = 4101
        private const val REQUEST_NOTIFICATIONS = 4102
    }
}
