package com.example.rhythmtracker.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * v0 persistence: append-only JSONL in internal app storage.
 *
 * This is intentionally boring and crash-resistant for the skeleton. Replace with Room
 * once the result schema is stable enough to deserve migrations.
 */
class FileResultStore(context: Context) {
    private val file = File(context.filesDir, "results.jsonl")

    @Synchronized
    fun append(result: PlayResult) {
        val json = JSONObject()
            .put("id", result.id)
            .put("capturedAtMs", result.capturedAtMs)
            .put("gameId", result.gameId)
            .put("songId", result.songId)
            .put("difficulty", result.difficulty)
            .put("score", result.score)
            .put("confidence", result.confidence.toDouble())
            .put("source", result.source)

        file.appendText(json.toString() + "\n", Charsets.UTF_8)
    }
}
