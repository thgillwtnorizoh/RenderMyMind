package com.example.rhythmtracker.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/** Append-only JSONL store. Partial OCR fields stay explicit nulls instead of being guessed. */
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
            .put("title", result.title)
            .put("artist", result.artist)
            .put("trackState", result.trackState)
            .put("score", result.score)
            .put("pure", result.pure)
            .put("far", result.far)
            .put("lost", result.lost)
            .put("confidence", result.confidence.toDouble())
            .put("screenshotPath", result.screenshotPath)
            .put("rawOcr", result.rawOcr)
            .put("source", result.source)

        file.appendText(json.toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun exportTo(output: OutputStream) {
        if (!file.exists()) return
        file.inputStream().buffered().use { input -> input.copyTo(output) }
    }

    fun sizeBytes(): Long = if (file.exists()) file.length() else 0L
}
