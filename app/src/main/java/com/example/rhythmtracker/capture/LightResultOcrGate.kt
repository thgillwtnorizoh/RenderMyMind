package com.example.rhythmtracker.capture

import android.graphics.Bitmap
import com.example.rhythmtracker.game.GameAdapter
import com.example.rhythmtracker.game.arcaea.ArcaeaGameAdapter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Two-stage OCR:
 *
 * 1. LIGHT OCR watches only the broad top band of the tiny persistent projection. It searches
 *    dynamically for the game's result tripwire (`Result` for Arcaea), not a fixed X/Y box.
 * 2. NATIVE OCR runs once after the tripwire fires. It keeps the complete native-resolution frame
 *    and runs Latin, Chinese, Japanese and Korean recognizers before deriving semantic field bounds.
 */
class LightResultOcrGate(
    private val gameAdapter: GameAdapter = ArcaeaGameAdapter()
) : Closeable {

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chineseRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val japaneseRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )
    private val koreanRecognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )

    fun inspect(
        frame: Bitmap,
        callbackExecutor: Executor,
        callback: (LightOcrResult) -> Unit
    ) {
        val probe = createLightTripwireProbe(frame)
        val image = InputImage.fromBitmap(probe, 0)

        latinRecognizer.process(image)
            .addOnCompleteListener(callbackExecutor) { task ->
                val result = if (task.isSuccessful) {
                    val analysis = DynamicResultLayout.analyse(
                        recognized = listOf(ScriptOcrText(OcrScript.LATIN, task.result)),
                        frameWidth = probe.width,
                        frameHeight = probe.height,
                        gameAdapter = gameAdapter,
                        fullLayout = false
                    )
                    LightOcrResult(
                        isResultLike = analysis.isResultLike,
                        matchedKeywords = analysis.matchedAnchors,
                        textPreview = analysis.textPreview,
                        regionReadings = remapLightBounds(analysis.regionReadings),
                        pass = OcrPass.LIGHT,
                        error = null
                    )
                } else {
                    failureResult(OcrPass.LIGHT, task.exception)
                }

                VisionDebugState.update(result)
                probe.recycle()
                callback(result)
            }
    }

    /**
     * Run once on the native result frame after the cheap gate fires.
     *
     * The bitmap is copied at full native resolution before this method returns. CaptureService can
     * therefore recycle its capture bitmap while all four recognizers continue asynchronously.
     */
    fun inspectNative(
        frame: Bitmap,
        callbackExecutor: Executor,
        callback: (LightOcrResult) -> Unit
    ) {
        val owned = frame.copy(Bitmap.Config.ARGB_8888, false)
        if (owned == null) {
            val result = failureResult(
                OcrPass.NATIVE,
                IllegalStateException("Could not copy native frame for OCR")
            )
            VisionDebugState.update(result)
            callback(result)
            return
        }

        val recognizers = listOf(
            RecognizerPass(OcrScript.LATIN, latinRecognizer),
            RecognizerPass(OcrScript.CHINESE, chineseRecognizer),
            RecognizerPass(OcrScript.JAPANESE, japaneseRecognizer),
            RecognizerPass(OcrScript.KOREAN, koreanRecognizer)
        )
        val remaining = AtomicInteger(recognizers.size)
        val successes = ArrayList<ScriptOcrText>(recognizers.size)
        val errors = ArrayList<Throwable>()

        recognizers.forEach { pass ->
            val image = InputImage.fromBitmap(owned, 0)
            pass.recognizer.process(image)
                .addOnCompleteListener(callbackExecutor) { task ->
                    if (task.isSuccessful) {
                        successes += ScriptOcrText(pass.script, task.result)
                    } else {
                        errors += task.exception ?: IllegalStateException(
                            "${pass.script} OCR failed without an exception"
                        )
                    }

                    if (remaining.decrementAndGet() == 0) {
                        val result = if (successes.isNotEmpty()) {
                            val analysis = DynamicResultLayout.analyse(
                                recognized = successes,
                                frameWidth = owned.width,
                                frameHeight = owned.height,
                                gameAdapter = gameAdapter,
                                fullLayout = true
                            )
                            LightOcrResult(
                                isResultLike = analysis.isResultLike,
                                matchedKeywords = analysis.matchedAnchors,
                                textPreview = analysis.textPreview,
                                regionReadings = analysis.regionReadings,
                                pass = OcrPass.NATIVE,
                                error = null
                            )
                        } else {
                            failureResult(
                                OcrPass.NATIVE,
                                errors.firstOrNull() ?: IllegalStateException("All OCR passes failed")
                            )
                        }

                        VisionDebugState.update(result)
                        owned.recycle()
                        callback(result)
                    }
                }
        }
    }

    override fun close() {
        latinRecognizer.close()
        chineseRecognizer.close()
        japaneseRecognizer.close()
        koreanRecognizer.close()
    }

    private fun createLightTripwireProbe(frame: Bitmap): Bitmap {
        val scanHeight = (frame.height * LIGHT_SCAN_HEIGHT_FRACTION)
            .roundToInt()
            .coerceIn(1, frame.height)
        val cropped = Bitmap.createBitmap(frame, 0, 0, frame.width, scanHeight)

        if (cropped.width >= LIGHT_SCAN_TARGET_WIDTH_PX) return cropped

        val targetHeight = (cropped.height *
            (LIGHT_SCAN_TARGET_WIDTH_PX.toFloat() / cropped.width.toFloat()))
            .roundToInt()
            .coerceAtLeast(1)
        return Bitmap.createScaledBitmap(
            cropped,
            LIGHT_SCAN_TARGET_WIDTH_PX,
            targetHeight,
            true
        ).also {
            cropped.recycle()
        }
    }

    /** Map top-band OCR rectangles back into full-screen normalized coordinates for the overlay. */
    private fun remapLightBounds(readings: List<OcrRegionReading>): List<OcrRegionReading> =
        readings.map { reading ->
            val bounds = reading.bounds
            if (bounds == null) {
                reading
            } else {
                reading.copy(
                    bounds = bounds.copy(
                        top = bounds.top * LIGHT_SCAN_HEIGHT_FRACTION,
                        bottom = bounds.bottom * LIGHT_SCAN_HEIGHT_FRACTION
                    ).clamped()
                )
            }
        }

    private fun failureResult(pass: OcrPass, error: Throwable?): LightOcrResult = LightOcrResult(
        isResultLike = false,
        matchedKeywords = emptySet(),
        textPreview = "",
        regionReadings = OcrProbeRegion.ALL.map {
            OcrRegionReading(it.key, it.label, "", null)
        },
        pass = pass,
        error = error?.message ?: error?.javaClass?.simpleName ?: "Unknown OCR error"
    )

    private data class RecognizerPass(
        val script: OcrScript,
        val recognizer: TextRecognizer
    )

    companion object {
        // A broad header band is intentionally used instead of a tiny hard-coded Result rectangle.
        private const val LIGHT_SCAN_HEIGHT_FRACTION = 0.34f
        private const val LIGHT_SCAN_TARGET_WIDTH_PX = 720
    }
}

enum class OcrPass {
    LIGHT,
    NATIVE
}

data class LightOcrResult(
    val isResultLike: Boolean,
    val matchedKeywords: Set<String>,
    val textPreview: String,
    val regionReadings: List<OcrRegionReading>,
    val pass: OcrPass,
    val error: String?
)
