package com.example.rhythmtracker.state

import com.example.rhythmtracker.identity.ResultIdentity

data class ResultSignal(
    val present: Boolean,
    val strong: Boolean,
    val strength: Float,
    val anchors: Set<String>,
    val identity: ResultIdentity
)
