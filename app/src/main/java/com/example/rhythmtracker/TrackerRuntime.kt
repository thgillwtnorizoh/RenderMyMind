package com.example.rhythmtracker

object TrackerRuntime {
    @Volatile var active: Boolean = false
    @Volatile var sampledFrames: Long = 0
    @Volatile var ocrProbes: Long = 0
    @Volatile var ocrHits: Long = 0
    @Volatile var capturedScreens: Long = 0
    @Volatile var savedResults: Long = 0
    @Volatile var lastSampleAtMs: Long = 0
    @Volatile var captureSize: String = "-"
    @Volatile var lastOcrText: String = "-"
    @Volatile var lastCapturePath: String = "-"
    @Volatile var lastMessage: String = "Idle"
}
