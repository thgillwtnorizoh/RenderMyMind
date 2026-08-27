package com.example.rhythmtracker.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** Stores v0.2 native-resolution result-screen evidence in private app storage. */
class ResultCaptureStore(context: Context) {
    private val directory = File(context.filesDir, "result-captures")

    @Synchronized
    fun save(bitmap: Bitmap, capturedAtMs: Long = System.currentTimeMillis()): File {
        if (!directory.exists()) directory.mkdirs()

        val file = File(directory, "result_$capturedAtMs.png")
        file.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Bitmap.compress() returned false"
            }
        }
        return file
    }
}
