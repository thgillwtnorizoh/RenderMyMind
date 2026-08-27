package com.example.rhythmtracker

object TrackerRuntime {
    @Volatile var active: Boolean = false
    @Volatile var sampledFrames: Long = 0
    @Volatile var stableCandidates: Long = 0
    @Volatile var savedResults: Long = 0
    @Volatile var lastSampleAtMs: Long = 0
    @Volatile var captureSize: String = "-"
    @Volatile var lastMessage: String = "Idle"
}
