package com.example.rhythmtracker.capture

import android.graphics.Bitmap
import com.example.rhythmtracker.TrackerRuntime
import java.util.concurrent.Executors

/**
 * One-shot high-quality OCR for a confirmed result screen.
 *
 * ResultCaptureStore calls submit() while the native bitmap is still alive. LightResultOcrGate
 * copies the four OCR regions synchronously, so CaptureService is free to recycle the original
 * bitmap immediately after save() returns. Only this one-shot pass uses the larger 720 px crops.
 */
object NativeResultVisionOcr {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rendermymind-native-ocr").apply { isDaemon = true }
    }
    private val gate = LightResultOcrGate()

    fun submit(bitmap: Bitmap) {
        try {
            gate.inspectNative(bitmap, executor) { result ->
                if (result.textPreview.isNotBlank()) {
                    TrackerRuntime.lastOcrText = "NATIVE: ${result.textPreview}"
                }

                TrackerRuntime.lastMessage = when {
                    result.error != null ->
                        "Native result OCR failed: ${result.error}"
                    result.isResultLike ->
                        "Native result OCR complete; detailed region readings ready"
                    else ->
                        "Native result OCR complete, but result signature was weak"
                }
            }
        } catch (error: Throwable) {
            TrackerRuntime.lastMessage =
                "Native result OCR setup failed: ${error.message ?: error.javaClass.simpleName}"
        }
    }
}
