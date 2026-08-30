package com.example.rhythmtracker.data

import android.content.Context
import android.graphics.Bitmap
import com.example.rhythmtracker.capture.NativeResultVisionOcr
import java.io.File

/** Stores native-resolution result-screen evidence in private app storage. */
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

        // submit() copies its OCR regions synchronously. CaptureService may safely recycle the
        // native bitmap as soon as this method returns while ML Kit continues asynchronously.
        NativeResultVisionOcr.submit(bitmap)
        return file
    }
}
