package com.example.rhythmtracker.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Generic v0.2 OCR sentinel.
 *
 * This is deliberately NOT a universal rhythm-game result detector. It only proves the
 * low-cost pipeline: crop a small normalised region, run one bundled Latin OCR model, and
 * wake the expensive native-resolution capture only when result-like text is present.
 *
 * A real GameAdapter should replace the ROI and keyword rules for each game.
 */
class LightResultOcrGate : Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun inspect(
        frame: Bitmap,
        callbackExecutor: Executor,
        callback: (LightOcrResult) -> Unit
    ) {
        val probe = createProbeBitmap(frame)
        val image = InputImage.fromBitmap(probe, 0)

        recognizer.process(image)
            .addOnSuccessListener(callbackExecutor) { text ->
                callback(classify(text))
            }
            .addOnFailureListener(callbackExecutor) { error ->
                callback(
                    LightOcrResult(
                        isResultLike = false,
                        matchedKeywords = emptySet(),
                        textPreview = "",
                        error = error.message ?: error.javaClass.simpleName
                    )
                )
            }
            .addOnCompleteListener(callbackExecutor) {
                probe.recycle()
            }
    }

    override fun close() {
        recognizer.close()
    }

    private fun classify(text: Text): LightOcrResult {
        val normalised = text.text
            .uppercase(Locale.US)
            .replace(Regex("\\s+"), " ")
            .trim()

        val matched = RESULT_KEYWORDS
            .filterTo(linkedSetOf()) { keyword -> normalised.contains(keyword) }

        val hasTitleSignal = matched.any { it in TITLE_KEYWORDS }
        val statSignals = matched.count { it in STAT_KEYWORDS }

        // Generic plumbing rule only. Game adapters should use their own exact anchors.
        val resultLike = (hasTitleSignal && statSignals >= 1) || statSignals >= 3

        return LightOcrResult(
            isResultLike = resultLike,
            matchedKeywords = matched,
            textPreview = normalised.take(MAX_PREVIEW_CHARS),
            error = null
        )
    }

    private fun createProbeBitmap(frame: Bitmap): Bitmap {
        val left = (frame.width * ROI_LEFT).roundToInt().coerceIn(0, frame.width - 1)
        val top = (frame.height * ROI_TOP).roundToInt().coerceIn(0, frame.height - 1)
        val right = (frame.width * ROI_RIGHT).roundToInt().coerceIn(left + 1, frame.width)
        val bottom = (frame.height * ROI_BOTTOM).roundToInt().coerceIn(top + 1, frame.height)

        val cropped = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        val longest = max(cropped.width, cropped.height)
        if (longest <= OCR_LONG_EDGE_PX) return cropped

        val scale = OCR_LONG_EDGE_PX.toFloat() / longest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).roundToInt().coerceAtLeast(2),
            (cropped.height * scale).roundToInt().coerceAtLeast(2),
            true
        )
        cropped.recycle()
        return scaled
    }

    companion object {
        // Generic centre/top sentinel. Game adapters will replace this with exact ROIs.
        private const val ROI_LEFT = 0.15f
        private const val ROI_TOP = 0.08f
        private const val ROI_RIGHT = 0.85f
        private const val ROI_BOTTOM = 0.62f
        private const val OCR_LONG_EDGE_PX = 360
        private const val MAX_PREVIEW_CHARS = 120

        private val TITLE_KEYWORDS = setOf(
            "RESULT",
            "RESULTS",
            "COMPLETE",
            "COMPLETED",
            "CLEAR"
        )

        private val STAT_KEYWORDS = setOf(
            "SCORE",
            "RANK",
            "COMBO",
            "PERFECT",
            "MISS",
            "ACCURACY"
        )

        private val RESULT_KEYWORDS = TITLE_KEYWORDS + STAT_KEYWORDS
    }
}

data class LightOcrResult(
    val isResultLike: Boolean,
    val matchedKeywords: Set<String>,
    val textPreview: String,
    val error: String?
)
