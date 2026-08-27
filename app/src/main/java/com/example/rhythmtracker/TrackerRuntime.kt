package com.example.rhythmtracker

object TrackerRuntime {
    @Volatile var active: Boolean = false
    @Volatile var sessionId: String = "-"
    @Volatile var sessionStartedAtMs: Long = 0L

    @Volatile var sampledFrames: Long = 0
    @Volatile var ocrProbes: Long = 0
    @Volatile var ocrHits: Long = 0
    @Volatile var capturedScreens: Long = 0
    @Volatile var savedResults: Long = 0

    @Volatile var lastSampleAtMs: Long = 0
    @Volatile var lastOcrDurationMs: Long = 0
    @Volatile var maxOcrDurationMs: Long = 0
    @Volatile var totalOcrDurationMs: Long = 0

    @Volatile var captureSize: String = "-"
    @Volatile var lastOcrText: String = "-"
    @Volatile var lastCapturePath: String = "-"
    @Volatile var lastMessage: String = "Idle"

    fun resetForNewSession(id: String, startedAtMs: Long) {
        sessionId = id
        sessionStartedAtMs = startedAtMs
        sampledFrames = 0
        ocrProbes = 0
        ocrHits = 0
        capturedScreens = 0
        savedResults = 0
        lastSampleAtMs = 0
        lastOcrDurationMs = 0
        maxOcrDurationMs = 0
        totalOcrDurationMs = 0
        captureSize = "-"
        lastOcrText = "-"
        lastCapturePath = "-"
        lastMessage = "Starting session"
    }

    val averageOcrDurationMs: Long
        get() = if (ocrProbes <= 0L) 0L else totalOcrDurationMs / ocrProbes
}
