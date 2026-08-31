package com.example.rhythmtracker.capture

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
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Two-stage result OCR.
 *
 * LIGHT runs continuously on the reduced MediaProjection surface. `Result` is still the preferred
 * tripwire, but a single tiny word is too fragile to be the only way into the result state. Strong
 * result-only structure such as TRACK COMPLETE / TRACK LOST, two judgement labels, or MAX RECALL
 * plus a score-shaped number can confirm the same screen when the tiny header is missed.
 *
 * NATIVE runs once after LIGHT confirms a result. It keeps the full captured frame at its original
 * resolution and combines the bundled Latin, Chinese, Japanese and Korean recognisers. Semantic
 * regions are derived from detected geometry rather than fixed song-title rectangles.
 */
class LightResultOcrGate : Closeable {

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
        // CaptureService recycles its frame immediately after this method returns. Keep our own
        // copy so ML Kit never observes recycled pixels.
        val working = frame.copy(Bitmap.Config.ARGB_8888, false)
        val image = InputImage.fromBitmap(working, 0)

        latinRecognizer.process(image)
            .addOnSuccessListener(callbackExecutor) { text ->
                val lines = extractLines(text, working.width, working.height, "latin")
                val result = classifyLight(lines)
                VisionDebugState.update(result)
                callback(result)
            }
            .addOnFailureListener(callbackExecutor) { error ->
                val result = LightOcrResult(
                    isResultLike = false,
                    matchedKeywords = emptySet(),
                    textPreview = "",
                    regionReadings = emptyList(),
                    pass = OcrPass.LIGHT,
                    error = error.message ?: error.javaClass.simpleName
                )
                VisionDebugState.update(result)
                callback(result)
            }
            .addOnCompleteListener(callbackExecutor) {
                working.recycle()
            }
    }

    /**
     * One-shot high-quality OCR. The entire native frame is retained at its original resolution.
     * This is intentionally heavier than the tripwire because it only runs after a song ends.
     */
    fun inspectNative(
        frame: Bitmap,
        callbackExecutor: Executor,
        callback: (LightOcrResult) -> Unit
    ) {
        val working = frame.copy(Bitmap.Config.ARGB_8888, false)
        val image = InputImage.fromBitmap(working, 0)
        val collected = Collections.synchronizedList(mutableListOf<DetectedLine>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val remaining = AtomicInteger(NATIVE_RECOGNIZER_COUNT)

        fun submit(recognizer: TextRecognizer, source: String) {
            recognizer.process(image)
                .addOnSuccessListener(callbackExecutor) { text ->
                    collected += extractLines(text, working.width, working.height, source)
                }
                .addOnFailureListener(callbackExecutor) { error ->
                    errors += "$source: ${error.message ?: error.javaClass.simpleName}"
                }
                .addOnCompleteListener(callbackExecutor) {
                    if (remaining.decrementAndGet() == 0) {
                        val result = classifyNative(
                            collected.toList(),
                            working.width,
                            working.height,
                            errors.toList()
                        )
                        VisionDebugState.update(result)
                        callback(result)
                        working.recycle()
                    }
                }
        }

        submit(latinRecognizer, "latin")
        submit(chineseRecognizer, "zh")
        submit(japaneseRecognizer, "ja")
        submit(koreanRecognizer, "ko")
    }

    override fun close() {
        latinRecognizer.close()
        chineseRecognizer.close()
        japaneseRecognizer.close()
        koreanRecognizer.close()
    }

    private fun classifyLight(lines: List<DetectedLine>): LightOcrResult {
        val signature = detectResultSignature(lines)
        val preview = lines
            .sortedWith(compareBy<DetectedLine> { it.bounds.top }.thenBy { it.bounds.left })
            .joinToString(" | ") { it.text }
            .take(LIGHT_PREVIEW_CHARS)

        return LightOcrResult(
            isResultLike = signature.resultLike,
            matchedKeywords = signature.matched,
            textPreview = preview,
            regionReadings = signature.anchorReadings,
            pass = OcrPass.LIGHT,
            error = null
        )
    }

    private fun classifyNative(
        rawLines: List<DetectedLine>,
        width: Int,
        height: Int,
        errors: List<String>
    ): LightOcrResult {
        val lines = deduplicateLines(rawLines)
        val signature = detectResultSignature(lines)
        val readings = if (signature.resultLike) {
            buildDynamicReadings(
                lines = lines,
                tripwire = signature.resultTripwire,
                track = signature.trackState,
                width = width,
                height = height
            )
        } else {
            emptyList()
        }

        val preview = if (readings.isNotEmpty()) {
            readings.joinToString(" | ") { "${it.label}: ${it.text}" }.take(MAX_PREVIEW_CHARS)
        } else {
            lines.sortedWith(compareBy<DetectedLine> { it.bounds.top }.thenBy { it.bounds.left })
                .joinToString(" | ") { it.text }
                .take(MAX_PREVIEW_CHARS)
        }

        return LightOcrResult(
            isResultLike = signature.resultLike,
            matchedKeywords = signature.matched,
            textPreview = preview,
            regionReadings = readings,
            pass = OcrPass.NATIVE,
            error = if (errors.size == NATIVE_RECOGNIZER_COUNT) errors.joinToString("; ") else null
        )
    }

    /**
     * Prefer the tiny Result header, but do not make the whole tracker depend on one 10-20 px word.
     * Every fallback below is result-screen-specific enough to be safe while gameplay is live.
     */
    private fun detectResultSignature(lines: List<DetectedLine>): ResultSignature {
        val resultTripwire = findResultTripwire(lines)
        val track = findTrackStateAnchor(lines)

        val judgementLines = linkedMapOf<String, DetectedLine>()
        JUDGEMENT_ANCHORS.forEach { token ->
            lines.firstOrNull { containsAnchor(it.text, token) }?.let { judgementLines[token] = it }
        }

        val maxRecall = lines.firstOrNull { looksLikeMaxRecall(it.text) }
        val score = lines
            .filter { scoreDigitCount(it.text) in 7..8 }
            .maxWithOrNull(compareBy<DetectedLine> { it.bounds.height() }.thenBy { it.bounds.width() })

        val matched = linkedSetOf<String>()
        if (resultTripwire != null) matched += "RESULT"
        if (track != null) matched += trackStateLabel(track.text)
        matched += judgementLines.keys
        if (maxRecall != null) matched += "MAX RECALL"
        if (score != null) matched += "SCORE_NUMBER"

        val resultLike = resultTripwire != null ||
            track != null ||
            judgementLines.size >= 2 ||
            (maxRecall != null && score != null)

        val readings = mutableListOf<OcrRegionReading>()
        resultTripwire?.toReading("result_tripwire", "RESULT TRIPWIRE")?.let(readings::add)
        if (resultTripwire == null) {
            track?.toReading("result_fallback", "RESULT FALLBACK")?.let(readings::add)
                ?: judgementLines.values.firstOrNull()?.toReading(
                    "result_fallback",
                    "RESULT FALLBACK"
                )?.let(readings::add)
                ?: maxRecall?.toReading("result_fallback", "RESULT FALLBACK")?.let(readings::add)
        }

        return ResultSignature(
            resultLike = resultLike,
            matched = matched,
            resultTripwire = resultTripwire,
            trackState = track,
            anchorReadings = readings
        )
    }

    private fun buildDynamicReadings(
        lines: List<DetectedLine>,
        tripwire: DetectedLine?,
        track: DetectedLine?,
        width: Int,
        height: Int
    ): List<OcrRegionReading> {
        val result = mutableListOf<OcrRegionReading>()
        tripwire?.toReading("result_tripwire", "RESULT TRIPWIRE")?.let(result::add)

        val titleTop = maxOf(
            tripwire?.bounds?.bottom?.plus((height * 0.008f).toInt()) ?: (height * 0.055f).toInt(),
            (height * 0.055f).toInt()
        )
        val titleBottom = minOf(
            track?.bounds?.top?.minus((height * 0.008f).toInt()) ?: (height * 0.33f).toInt(),
            (height * 0.35f).toInt()
        )

        if (titleBottom > titleTop) {
            val titleLines = lines.filter { line ->
                val cy = line.bounds.centerY()
                val cx = line.bounds.centerX()
                cy in titleTop..titleBottom &&
                    cx > width * 0.06f && cx < width * 0.94f &&
                    !isHeaderChrome(line.text) &&
                    !looksLikeTrackState(line.text)
            }
            regionFromLines("title", "TITLE / ARTIST", titleLines, width, height, 0.012f)?.let(result::add)
        }

        track?.toReading("track_state", "TRACK STATE")?.let(result::add)

        val score = lines
            .filter { line ->
                val cy = line.bounds.centerY()
                cy > height * 0.24f && cy < height * 0.70f && scoreDigitCount(line.text) in 7..8
            }
            .maxWithOrNull(compareBy<DetectedLine> { it.bounds.height() }.thenBy { it.bounds.width() })
        score?.toReading("score", "SCORE")?.let(result::add)

        val judgementLabels = lines.filter { line ->
            line.bounds.centerY() > height * 0.50f &&
                JUDGEMENT_ANCHORS.any { containsAnchor(line.text, it) }
        }
        if (judgementLabels.isNotEmpty()) {
            val tolerance = height * 0.045f
            val judgementLines = lines.filter { candidate ->
                candidate.bounds.centerY() > height * 0.48f &&
                    judgementLabels.any { label ->
                        abs(candidate.bounds.centerY() - label.bounds.centerY()) <= tolerance
                    }
            }
            regionFromLines(
                "judgements",
                "JUDGEMENTS",
                judgementLines,
                width,
                height,
                0.01f
            )?.let(result::add)
        }

        return result
    }

    private fun regionFromLines(
        key: String,
        label: String,
        lines: List<DetectedLine>,
        width: Int,
        height: Int,
        paddingFraction: Float
    ): OcrRegionReading? {
        if (lines.isEmpty()) return null
        val sorted = lines.sortedWith(compareBy<DetectedLine> { it.bounds.top }.thenBy { it.bounds.left })
        val union = Rect(
            sorted.minOf { it.bounds.left },
            sorted.minOf { it.bounds.top },
            sorted.maxOf { it.bounds.right },
            sorted.maxOf { it.bounds.bottom }
        )
        val paddingX = (width * paddingFraction).toInt()
        val paddingY = (height * paddingFraction).toInt()
        union.left = (union.left - paddingX).coerceAtLeast(0)
        union.top = (union.top - paddingY).coerceAtLeast(0)
        union.right = (union.right + paddingX).coerceAtMost(width)
        union.bottom = (union.bottom + paddingY).coerceAtMost(height)

        return OcrRegionReading(
            key = key,
            label = label,
            text = normalizeSpacing(sorted.joinToString(" ") { it.text }),
            left = union.left.toFloat() / width,
            top = union.top.toFloat() / height,
            right = union.right.toFloat() / width,
            bottom = union.bottom.toFloat() / height
        )
    }

    private fun extractLines(text: Text, width: Int, height: Int, source: String): List<DetectedLine> {
        val result = mutableListOf<DetectedLine>()
        text.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox ?: return@forEach
                if (bounds.width() <= 0 || bounds.height() <= 0) return@forEach
                if (bounds.right <= 0 || bounds.bottom <= 0 || bounds.left >= width || bounds.top >= height) {
                    return@forEach
                }
                result += DetectedLine(normalizeSpacing(line.text), Rect(bounds), source, width, height)
            }
        }
        return result
    }

    /** Merge duplicate geometry emitted by the four script recognisers. */
    private fun deduplicateLines(lines: List<DetectedLine>): List<DetectedLine> {
        val accepted = mutableListOf<DetectedLine>()
        lines.sortedByDescending { qualityScore(it.text) }.forEach { candidate ->
            val duplicate = accepted.any { existing ->
                intersectionOverUnion(existing.bounds, candidate.bounds) >= 0.58f &&
                    roughlySameText(existing.text, candidate.text)
            }
            if (!duplicate) accepted += candidate
        }
        return accepted
    }

    private fun findResultTripwire(lines: List<DetectedLine>): DetectedLine? {
        return lines
            .filter { line -> line.text.split(Regex("\\s+")).any(::looksLikeResultWord) }
            .minWithOrNull(compareBy<DetectedLine> { it.bounds.top }.thenBy { it.bounds.left })
    }

    private fun findTrackStateAnchor(lines: List<DetectedLine>): DetectedLine? {
        return lines
            .filter { looksLikeTrackState(it.text) }
            .maxByOrNull { it.bounds.width() * it.bounds.height() }
    }

    private fun looksLikeResultWord(raw: String): Boolean {
        val word = raw.uppercase(Locale.US).filter { it.isLetterOrDigit() }
        if (word == "RESULT") return true
        return word.length in 5..7 && levenshtein(word, "RESULT", 1) <= 1
    }

    private fun looksLikeTrackState(raw: String): Boolean {
        val compact = gateNormalize(raw).filter { it.isLetterOrDigit() }
        val hasTrack = compact.contains("TRACK") || compact.contains("TRAK") || compact.contains("TRAC")
        if (!hasTrack) return false
        return compact.contains("COMPLETE") || compact.contains("COMPLE") || compact.contains("COMPL") ||
            compact.contains("COMP") || compact.contains("LOST") || compact.contains("L0ST")
    }

    private fun trackStateLabel(raw: String): String {
        val compact = gateNormalize(raw).filter { it.isLetterOrDigit() }
        return if (compact.contains("LOST") || compact.contains("L0ST")) "TRACK LOST" else "TRACK COMPLETE"
    }

    private fun looksLikeMaxRecall(raw: String): Boolean {
        val compact = gateNormalize(raw).filter { it.isLetterOrDigit() }
        if (compact.contains("MAXRECALL")) return true
        return compact.length in 7..11 && levenshtein(compact.take(9), "MAXRECALL", 2) <= 2
    }

    private fun containsAnchor(raw: String, token: String): Boolean {
        val upper = gateNormalize(raw)
        if (token in upper) return true
        val compact = upper.filter { it.isLetterOrDigit() }
        return when (token) {
            "PURE" -> compact.contains("PURE")
            "FAR" -> compact.contains("FAR")
            "LOST" -> compact.contains("LOST") || compact.contains("L0ST")
            else -> false
        }
    }

    private fun isHeaderChrome(raw: String): Boolean {
        val upper = gateNormalize(raw)
        return HEADER_CHROME.any { token -> token in upper } || looksLikeResultWord(raw)
    }

    private fun scoreDigitCount(raw: String): Int = raw.count { it.isDigit() }

    private fun gateNormalize(value: String): String = normalizeSpacing(value).uppercase(Locale.US)

    private fun normalizeSpacing(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun qualityScore(text: String): Int {
        val visible = text.count { !it.isWhitespace() }
        val nonAscii = text.count { it.code > 0x7f }
        return visible + nonAscii * 2
    }

    private fun roughlySameText(a: String, b: String): Boolean {
        val aa = gateNormalize(a).filter { it.isLetterOrDigit() }
        val bb = gateNormalize(b).filter { it.isLetterOrDigit() }
        if (aa == bb) return true
        if (aa.isBlank() || bb.isBlank()) return false
        val shorter = minOf(aa.length, bb.length)
        return shorter >= 3 && (aa.contains(bb) || bb.contains(aa))
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

    private fun levenshtein(a: String, b: String, maxDistance: Int): Int {
        if (a == b) return 0
        if (abs(a.length - b.length) > maxDistance) return maxDistance + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, current[j - 1] + 1, previous[j] + 1)
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private data class ResultSignature(
        val resultLike: Boolean,
        val matched: Set<String>,
        val resultTripwire: DetectedLine?,
        val trackState: DetectedLine?,
        val anchorReadings: List<OcrRegionReading>
    )

    private data class DetectedLine(
        val text: String,
        val bounds: Rect,
        val source: String,
        val frameWidth: Int,
        val frameHeight: Int
    ) {
        fun toReading(key: String, label: String): OcrRegionReading = OcrRegionReading(
            key = key,
            label = label,
            text = text,
            left = bounds.left.toFloat() / frameWidth,
            top = bounds.top.toFloat() / frameHeight,
            right = bounds.right.toFloat() / frameWidth,
            bottom = bounds.bottom.toFloat() / frameHeight
        )
    }

    companion object {
        private const val NATIVE_RECOGNIZER_COUNT = 4
        private const val LIGHT_PREVIEW_CHARS = 320
        private const val MAX_PREVIEW_CHARS = 700

        private val JUDGEMENT_ANCHORS = setOf("PURE", "FAR", "LOST")
        private val HEADER_CHROME = setOf(
            "RESULT", "SYNC", "POTENTIAL", "FRAGMENTS", "MEMORIES", "KEEP"
        )
    }
}

enum class OcrPass {
    LIGHT,
    NATIVE
}

data class OcrRegionReading(
    val key: String,
    val label: String,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class LightOcrResult(
    val isResultLike: Boolean,
    val matchedKeywords: Set<String>,
    val textPreview: String,
    val regionReadings: List<OcrRegionReading>,
    val pass: OcrPass,
    val error: String?
)
