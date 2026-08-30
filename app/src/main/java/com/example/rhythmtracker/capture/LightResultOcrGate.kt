package com.example.rhythmtracker.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.example.rhythmtracker.game.GameAdapter
import com.example.rhythmtracker.game.arcaea.ArcaeaGameAdapter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Two-stage OCR:
 *
 * 1. LIGHT runs continuously on the deliberately tiny MediaProjection surface and only needs to
 *    decide whether a result screen is probably present.
 * 2. NATIVE runs once after that gate fires, using the full-resolution result frame for title,
 *    score and judgement text that the 480 px probe physically cannot preserve.
 *
 * Both stages use the same four normalized regions so the vision overlay always represents real
 * OCR input rather than decorative boxes.
 */
class LightResultOcrGate(
    private val gameAdapter: GameAdapter = ArcaeaGameAdapter()
) : Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun inspect(
        frame: Bitmap,
        callbackExecutor: Executor,
        callback: (LightOcrResult) -> Unit
    ) {
        inspectInternal(
            frame = frame,
            pass = OcrPass.LIGHT,
            targetWidthPx = LIGHT_REGION_WIDTH_PX,
            minRegionHeightPx = LIGHT_MIN_REGION_HEIGHT_PX,
            callbackExecutor = callbackExecutor,
            callback = callback
        )
    }

    /** Run once on the native result frame after the cheap gate has fired. */
    fun inspectNative(
        frame: Bitmap,
        callbackExecutor: Executor,
        callback: (LightOcrResult) -> Unit
    ) {
        inspectInternal(
            frame = frame,
            pass = OcrPass.NATIVE,
            targetWidthPx = NATIVE_REGION_WIDTH_PX,
            minRegionHeightPx = NATIVE_MIN_REGION_HEIGHT_PX,
            callbackExecutor = callbackExecutor,
            callback = callback
        )
    }

    private fun inspectInternal(
        frame: Bitmap,
        pass: OcrPass,
        targetWidthPx: Int,
        minRegionHeightPx: Int,
        callbackExecutor: Executor,
        callback: (LightOcrResult) -> Unit
    ) {
        val probe = createCompositeProbe(frame, targetWidthPx, minRegionHeightPx)
        val image = InputImage.fromBitmap(probe.bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener(callbackExecutor) { text ->
                val result = classify(text, probe.segments, pass)
                VisionDebugState.update(result)
                callback(result)
            }
            .addOnFailureListener(callbackExecutor) { error ->
                val result = LightOcrResult(
                    isResultLike = false,
                    matchedKeywords = emptySet(),
                    textPreview = "",
                    regionReadings = emptyRegionReadings(),
                    pass = pass,
                    error = error.message ?: error.javaClass.simpleName
                )
                VisionDebugState.update(result)
                callback(result)
            }
            .addOnCompleteListener(callbackExecutor) {
                probe.bitmap.recycle()
            }
    }

    override fun close() {
        recognizer.close()
    }

    private fun classify(
        text: Text,
        segments: List<CompositeSegment>,
        pass: OcrPass
    ): LightOcrResult {
        val linesByRegion = OcrProbeRegion.ALL.associate { it.key to mutableListOf<String>() }

        text.textBlocks.forEach { block ->
            block.lines.forEach lineLoop@{ line ->
                val bounds = line.boundingBox ?: return@lineLoop
                val centerY = (bounds.top + bounds.bottom) / 2
                val segment = segments.firstOrNull { centerY >= it.top && centerY < it.bottom }
                    ?: return@lineLoop
                linesByRegion.getValue(segment.region.key) += line.text
            }
        }

        val readings = OcrProbeRegion.ALL.map { region ->
            OcrRegionReading(
                key = region.key,
                label = region.label,
                text = normalize(linesByRegion.getValue(region.key).joinToString(" "))
            )
        }

        val mappedText = readings.joinToString(" ") { it.text }.trim()
        // Bounding boxes should normally map every line. Keep aggregate OCR text as a defensive
        // fallback so a future ML Kit quirk cannot silently disable the result gate.
        val normalised = if (mappedText.isNotBlank()) mappedText else normalize(text.text)
        val decision = gameAdapter.classifyLightText(normalised)

        return LightOcrResult(
            isResultLike = decision.isResultLike,
            matchedKeywords = decision.matchedAnchors,
            textPreview = normalised.take(MAX_PREVIEW_CHARS),
            regionReadings = readings,
            pass = pass,
            error = null
        )
    }

    private fun createCompositeProbe(
        frame: Bitmap,
        targetWidthPx: Int,
        minRegionHeightPx: Int
    ): CompositeProbe {
        val rendered = ArrayList<Pair<OcrProbeRegionSpec, Bitmap>>(OcrProbeRegion.ALL.size)

        OcrProbeRegion.ALL.forEach { region ->
            val left = (frame.width * region.left).roundToInt().coerceIn(0, frame.width - 1)
            val top = (frame.height * region.top).roundToInt().coerceIn(0, frame.height - 1)
            val right = (frame.width * region.right).roundToInt().coerceIn(left + 1, frame.width)
            val bottom = (frame.height * region.bottom).roundToInt().coerceIn(top + 1, frame.height)

            val cropped = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
            val targetHeight = (
                cropped.height * (targetWidthPx.toFloat() / cropped.width.toFloat())
            ).roundToInt().coerceAtLeast(minRegionHeightPx)

            // Never downscale the native crop below its original width. At result time OCR is a
            // one-shot operation, so preserving real glyph pixels matters more than saving a few
            // hundred kilobytes.
            val outputWidth = if (targetWidthPx >= cropped.width) targetWidthPx else cropped.width
            val outputHeight = (
                cropped.height * (outputWidth.toFloat() / cropped.width.toFloat())
            ).roundToInt().coerceAtLeast(minRegionHeightPx)

            val scaled = if (cropped.width == outputWidth && cropped.height == outputHeight) {
                cropped
            } else {
                Bitmap.createScaledBitmap(cropped, outputWidth, outputHeight, true).also {
                    cropped.recycle()
                }
            }
            rendered += region to scaled
        }

        val compositeWidth = rendered.maxOfOrNull { it.second.width } ?: targetWidthPx
        val totalHeight = rendered.sumOf { it.second.height } +
            COMPOSITE_GUTTER_PX * (rendered.size - 1).coerceAtLeast(0)
        val composite = Bitmap.createBitmap(
            compositeWidth.coerceAtLeast(2),
            totalHeight.coerceAtLeast(2),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(composite)
        canvas.drawColor(Color.BLACK)

        val segments = ArrayList<CompositeSegment>(rendered.size)
        var y = 0
        rendered.forEachIndexed { index, (region, bitmap) ->
            canvas.drawBitmap(bitmap, 0f, y.toFloat(), null)
            segments += CompositeSegment(region, y, y + bitmap.height)
            y += bitmap.height
            bitmap.recycle()

            if (index != rendered.lastIndex) y += COMPOSITE_GUTTER_PX
        }

        return CompositeProbe(composite, segments)
    }

    private fun normalize(value: String): String = value
        .uppercase(Locale.US)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun emptyRegionReadings(): List<OcrRegionReading> = OcrProbeRegion.ALL.map {
        OcrRegionReading(it.key, it.label, "")
    }

    private data class CompositeProbe(
        val bitmap: Bitmap,
        val segments: List<CompositeSegment>
    )

    private data class CompositeSegment(
        val region: OcrProbeRegionSpec,
        val top: Int,
        val bottom: Int
    )

    companion object {
        private const val LIGHT_REGION_WIDTH_PX = 320
        private const val LIGHT_MIN_REGION_HEIGHT_PX = 42

        // Native crops on a typical tablet/phone are already 400-700 px wide. Keep at least that
        // real resolution, only enlarging genuinely smaller crops.
        private const val NATIVE_REGION_WIDTH_PX = 720
        private const val NATIVE_MIN_REGION_HEIGHT_PX = 96

        private const val COMPOSITE_GUTTER_PX = 12
        private const val MAX_PREVIEW_CHARS = 320
    }
}

enum class OcrPass {
    LIGHT,
    NATIVE
}

data class OcrRegionReading(
    val key: String,
    val label: String,
    val text: String
)

data class LightOcrResult(
    val isResultLike: Boolean,
    val matchedKeywords: Set<String>,
    val textPreview: String,
    val regionReadings: List<OcrRegionReading>,
    val pass: OcrPass,
    val error: String?
)
