package com.example.rhythmtracker.data

import android.content.Context
import com.example.rhythmtracker.BuildConfig
import com.example.rhythmtracker.TrackerRuntime
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/**
 * Low-frequency diagnostic persistence for prototype performance testing.
 *
 * Important: this intentionally does NOT write once per OCR probe. The tracker is measuring
 * gameplay interference, so the logger itself must stay out of the hot path.
 */
class DiagnosticStore(context: Context) {
    private val logFile = File(context.filesDir, "diagnostics.jsonl")
    private val snapshotFile = File(context.filesDir, "diagnostic_state.json")

    @Synchronized
    fun saveSnapshot(snapshot: DiagnosticSnapshot) {
        val temp = File(snapshotFile.parentFile, "${snapshotFile.name}.tmp")
        temp.writeText(snapshot.toJson().toString(), Charsets.UTF_8)
        if (!temp.renameTo(snapshotFile)) {
            temp.copyTo(snapshotFile, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    fun appendEvent(type: String, snapshot: DiagnosticSnapshot, extra: JSONObject? = null) {
        val event = snapshot.toJson()
            .put("event", type)
            .put("eventAtMs", System.currentTimeMillis())
        if (extra != null) event.put("extra", extra)
        logFile.appendText(event.toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun readSnapshot(): DiagnosticSnapshot? {
        if (!snapshotFile.isFile) return null
        return runCatching {
            DiagnosticSnapshot.fromJson(JSONObject(snapshotFile.readText(Charsets.UTF_8)))
        }.getOrNull()
    }

    @Synchronized
    fun exportTo(output: OutputStream) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            readSnapshot()?.let { snapshot ->
                writer.appendLine(
                    snapshot.toJson()
                        .put("event", "export_snapshot")
                        .put("eventAtMs", System.currentTimeMillis())
                        .toString()
                )
            }
            if (logFile.isFile) {
                logFile.forEachLine(Charsets.UTF_8) { line ->
                    if (line.isNotBlank()) writer.appendLine(line)
                }
            }
        }
    }

    companion object {
        fun snapshotFromRuntime(): DiagnosticSnapshot = DiagnosticSnapshot(
            version = BuildConfig.VERSION_NAME,
            sessionId = TrackerRuntime.sessionId,
            sessionStartedAtMs = TrackerRuntime.sessionStartedAtMs,
            snapshotAtMs = System.currentTimeMillis(),
            active = TrackerRuntime.active,
            captureMode = TrackerRuntime.captureSize,
            sampledFrames = TrackerRuntime.sampledFrames,
            ocrProbes = TrackerRuntime.ocrProbes,
            ocrHits = TrackerRuntime.ocrHits,
            capturedScreens = TrackerRuntime.capturedScreens,
            savedResults = TrackerRuntime.savedResults,
            lastSampleAtMs = TrackerRuntime.lastSampleAtMs,
            lastOcrDurationMs = TrackerRuntime.lastOcrDurationMs,
            maxOcrDurationMs = TrackerRuntime.maxOcrDurationMs,
            totalOcrDurationMs = TrackerRuntime.totalOcrDurationMs,
            lastOcrText = TrackerRuntime.lastOcrText,
            lastCapturePath = TrackerRuntime.lastCapturePath,
            lastMessage = TrackerRuntime.lastMessage
        )
    }
}

data class DiagnosticSnapshot(
    val version: String,
    val sessionId: String,
    val sessionStartedAtMs: Long,
    val snapshotAtMs: Long,
    val active: Boolean,
    val captureMode: String,
    val sampledFrames: Long,
    val ocrProbes: Long,
    val ocrHits: Long,
    val capturedScreens: Long,
    val savedResults: Long,
    val lastSampleAtMs: Long,
    val lastOcrDurationMs: Long,
    val maxOcrDurationMs: Long,
    val totalOcrDurationMs: Long,
    val lastOcrText: String,
    val lastCapturePath: String,
    val lastMessage: String
) {
    val averageOcrDurationMs: Long
        get() = if (ocrProbes <= 0L) 0L else totalOcrDurationMs / ocrProbes

    fun toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("sessionId", sessionId)
        .put("sessionStartedAtMs", sessionStartedAtMs)
        .put("snapshotAtMs", snapshotAtMs)
        .put("active", active)
        .put("captureMode", captureMode)
        .put("sampledFrames", sampledFrames)
        .put("ocrProbes", ocrProbes)
        .put("ocrHits", ocrHits)
        .put("capturedScreens", capturedScreens)
        .put("savedResults", savedResults)
        .put("lastSampleAtMs", lastSampleAtMs)
        .put("lastOcrDurationMs", lastOcrDurationMs)
        .put("maxOcrDurationMs", maxOcrDurationMs)
        .put("totalOcrDurationMs", totalOcrDurationMs)
        .put("averageOcrDurationMs", averageOcrDurationMs)
        .put("lastOcrText", lastOcrText)
        .put("lastCapturePath", lastCapturePath)
        .put("lastMessage", lastMessage)

    companion object {
        fun fromJson(json: JSONObject): DiagnosticSnapshot = DiagnosticSnapshot(
            version = json.optString("version", "unknown"),
            sessionId = json.optString("sessionId", "-"),
            sessionStartedAtMs = json.optLong("sessionStartedAtMs", 0L),
            snapshotAtMs = json.optLong("snapshotAtMs", 0L),
            active = json.optBoolean("active", false),
            captureMode = json.optString("captureMode", "-"),
            sampledFrames = json.optLong("sampledFrames", 0L),
            ocrProbes = json.optLong("ocrProbes", 0L),
            ocrHits = json.optLong("ocrHits", 0L),
            capturedScreens = json.optLong("capturedScreens", 0L),
            savedResults = json.optLong("savedResults", 0L),
            lastSampleAtMs = json.optLong("lastSampleAtMs", 0L),
            lastOcrDurationMs = json.optLong("lastOcrDurationMs", 0L),
            maxOcrDurationMs = json.optLong("maxOcrDurationMs", 0L),
            totalOcrDurationMs = json.optLong("totalOcrDurationMs", 0L),
            lastOcrText = json.optString("lastOcrText", "-"),
            lastCapturePath = json.optString("lastCapturePath", "-"),
            lastMessage = json.optString("lastMessage", "-")
        )
    }
}
