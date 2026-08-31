package com.example.rhythmtracker.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * OCR only. This class does not know what a result screen is, when to capture, or whether two
 * frames belong to the same play. Keeping those decisions outside ML Kit is deliberate.
 */
class MlKitOcrEngine : Closeable {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    fun recognizeLight(
        frame: Bitmap,
        callbackExecutor: Executor,
        callback: (Result<List<VisionLine>>) -> Unit
    ) {
        recognizeSingle(frame, latin, "latin", callbackExecutor, callback)
    }

    fun recognizeNative(
        frame: Bitmap,
        callbackExecutor: Executor,
        callback: (Result<List<VisionLine>>) -> Unit
    ) {
        val working = frame.copy(Bitmap.Config.ARGB_8888, false)
        val image = InputImage.fromBitmap(working, 0)
        val collected = Collections.synchronizedList(mutableListOf<VisionLine>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val remaining = AtomicInteger(4)

        fun submit(recognizer: TextRecognizer, source: String) {
            recognizer.process(image)
                .addOnSuccessListener(callbackExecutor) { text ->
                    collected += extract(text, working.width, working.height, source)
                }
                .addOnFailureListener(callbackExecutor) { failures += it }
                .addOnCompleteListener(callbackExecutor) {
                    if (remaining.decrementAndGet() == 0) {
                        val value = deduplicate(collected.toList())
                        working.recycle()
                        if (value.isNotEmpty() || failures.size < 4) {
                            callback(Result.success(value))
                        } else {
                            callback(Result.failure(failures.first()))
                        }
                    }
                }
        }

        submit(latin, "latin")
        submit(chinese, "zh")
        submit(japanese, "ja")
        submit(korean, "ko")
    }

    private fun recognizeSingle(
        frame: Bitmap,
        recognizer: TextRecognizer,
        source: String,
        callbackExecutor: Executor,
        callback: (Result<List<VisionLine>>) -> Unit
    ) {
        val working = frame.copy(Bitmap.Config.ARGB_8888, false)
        val image = InputImage.fromBitmap(working, 0)
        recognizer.process(image)
            .addOnSuccessListener(callbackExecutor) { text ->
                callback(Result.success(extract(text, working.width, working.height, source)))
            }
            .addOnFailureListener(callbackExecutor) { callback(Result.failure(it)) }
            .addOnCompleteListener(callbackExecutor) { working.recycle() }
    }

    private fun extract(text: Text, width: Int, height: Int, source: String): List<VisionLine> {
        val lines = mutableListOf<VisionLine>()
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox ?: return@forEach
                if (bounds.width() <= 0 || bounds.height() <= 0) return@forEach
                val normalizedText = line.text.replace(Regex("\\s+"), " ").trim()
                if (normalizedText.isBlank()) return@forEach
                lines += VisionLine(
                    text = normalizedText,
                    bounds = Rect(bounds),
                    frameWidth = width,
                    frameHeight = height,
                    source = source
                )
            }
        }
        return lines
    }

    private fun deduplicate(lines: List<VisionLine>): List<VisionLine> {
        val accepted = mutableListOf<VisionLine>()
        lines.sortedByDescending { quality(it.text) }.forEach { candidate ->
            val duplicate = accepted.any { existing ->
                intersectionOverUnion(existing.bounds, candidate.bounds) >= 0.58f &&
                    roughlySameText(existing.text, candidate.text)
            }
            if (!duplicate) accepted += candidate
        }
        return accepted
    }

    private fun quality(text: String): Int =
        text.count { !it.isWhitespace() } + text.count { it.code > 0x7f } * 2

    private fun roughlySameText(a: String, b: String): Boolean {
        val aa = a.uppercase().filter { it.isLetterOrDigit() }
        val bb = b.uppercase().filter { it.isLetterOrDigit() }
        if (aa == bb) return true
        if (aa.length < 3 || bb.length < 3) return false
        return aa.contains(bb) || bb.contains(aa)
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toLong() * (bottom - top).toLong()
        val union = a.width().toLong() * a.height().toLong() +
            b.width().toLong() * b.height().toLong() - intersection
        return if (union <= 0L) 0f else intersection.toFloat() / union.toFloat()
    }

    override fun close() {
        latin.close()
        chinese.close()
        japanese.close()
        korean.close()
    }
}
