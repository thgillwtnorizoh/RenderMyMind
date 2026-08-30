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
 * Low-cost OCR sentinel. OCR mechanics live here; game-specific interpretation does not.
 *
 * Four small Arcaea result regions are cropped and stacked into one compact bitmap. That keeps
 * this at one ML Kit invocation per probe while giving each important UI area much more OCR
 * resolution than one giant result-screen crop.
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
        val probe = createCompositeProbe(frame)
        val image = InputImage.fromBitmap(probe.bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener(callbackExecutor) { text ->
                val result = classify(text, probe.segments)
                VisionDebugState.update(result)
                callback(result)
            }
            .addOnFailureListener(callbackExecutor) { error ->
                val result = LightOcrResult(
                    isResultLike = false,
                    matchedKeywords = emptySet(),
                    textPreview = "",
                    regionReadings = emptyRegionReadings(),
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

    private fun classify(text: Text, segments: List<CompositeSegment>): LightOcrResult {
        val linesByRegion = OcrProbeRegion.ALL.associate { it.key to mutableListOf<String>() }

        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox ?: return@forEach
                val centerY = (bounds.top + bounds.bottom) / 2
                val segment = segments.firstOrNull { centerY >= it.top && centerY < it.bottom }
                    ?: return@forEach
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
        // Bounding boxes should normally map every line. Keep the aggregate OCR text as a
        // defensive fallback so a future ML Kit quirk cannot silently disable the result gate.
        val normalised = if (mappedText.isNotBlank()) mappedText else normalize(text.text)
        val decision = gameAdapter.classifyLightText(normalised)

        return LightOcrResult(
            isResultLike = decision.isResultLike,
            matchedKeywords = decision.matchedAnchors,
            textPreview = normalised.take(MAX_PREVIEW_CHARS),
            regionReadings = readings,
            error = null
        )
    }

    private fun createCompositeProbe(frame: Bitmap): CompositeProbe {
        val rendered = ArrayList<Pair<OcrProbeRegionSpec, Bitmap>>(OcrProbeRegion.ALL.size)

        OcrProbeRegion.ALL.forEach { region ->
            val left = (frame.width * region.left).roundToInt().coerceIn(0, frame.width - 1)
            val top = (frame.height * region.top).roundToInt().coerceIn(0, frame.height - 1)
            val right = (frame.width * region.right).roundToInt().coerceIn(left + 1, frame.width)
            val bottom = (frame.height * region.bottom).roundToInt().coerceIn(top + 1, frame.height)

            val cropped = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
            val targetHeight = (
                cropped.height * (OCR_REGION_WIDTH_PX.toFloat() / cropped.width.toFloat())
            ).roundToInt().coerceAtLeast(MIN_REGION_HEIGHT_PX)

            val scaled = Bitmap.createScaledBitmap(
                cropped,
                OCR_REGION_WIDTH_PX,
                targetHeight,
                true
            )
            cropped.recycle()
            rendered += region to scaled
        }

        val totalHeight = rendered.sumOf { it.second.height } +
            COMPOSITE_GUTTER_PX * (rendered.size - 1).coerceAtLeast(0)
        val composite = Bitmap.createBitmap(
            OCR_REGION_WIDTH_PX,
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

            if (index != rendered.lastIndex) {
                y += COMPOSITE_GUTTER_PX
            }
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
        private const val OCR_REGION_WIDTH_PX = 320
        private const val MIN_REGION_HEIGHT_PX = 42
        private const val COMPOSITE_GUTTER_PX = 10
        private const val MAX_PREVIEW_CHARS = 240
    }
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
    val error: String?
)
