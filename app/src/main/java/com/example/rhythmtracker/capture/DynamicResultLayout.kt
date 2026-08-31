package com.example.rhythmtracker.capture

import android.graphics.Rect
import com.example.rhythmtracker.game.GameAdapter
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class OcrScript {
    LATIN,
    CHINESE,
    JAPANESE,
    KOREAN
}

data class ScriptOcrText(
    val script: OcrScript,
    val text: Text
)

data class DynamicLayoutAnalysis(
    val isResultLike: Boolean,
    val matchedAnchors: Set<String>,
    val textPreview: String,
    val regionReadings: List<OcrRegionReading>
)

/**
 * Turns full-frame OCR output into semantic Arcaea result fields without fixed field rectangles.
 *
 * The only positional assumption kept by the cheap sentinel is broad and deliberate: Arcaea's
 * `Result` navigation label belongs to the upper part of the screen. Its X coordinate is not fixed.
 * Once that tripwire fires, the native pass derives title/state/score/judgement bounds from the OCR
 * lines themselves and from their relationship to one another.
 */
object DynamicResultLayout {

    fun analyse(
        recognized: List<ScriptOcrText>,
        frameWidth: Int,
        frameHeight: Int,
        gameAdapter: GameAdapter,
        fullLayout: Boolean
    ): DynamicLayoutAnalysis {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return DynamicLayoutAnalysis(false, emptySet(), "", emptyReadings())
        }

        val lines = deduplicate(extractLines(recognized))
        val topCandidates = lines.filter { it.bounds.top < frameHeight * TRIPWIRE_MAX_TOP }

        val headerMatch = topCandidates
            .mapNotNull { line ->
                val decision = gameAdapter.classifyTripwireText(normalizeForMatch(line.text))
                if (!decision.isResultLike) null else HeaderMatch(line, decision.matchedAnchors)
            }
            .maxByOrNull { match ->
                headerScore(match.line, frameWidth, frameHeight)
            }

        val allText = lines.joinToString(" ") { it.text }.trim()
        val fullDecision = if (fullLayout) {
            gameAdapter.classifyLightText(normalizeForMatch(allText))
        } else {
            null
        }

        val matched = linkedSetOf<String>()
        headerMatch?.anchors?.let(matched::addAll)
        fullDecision?.matchedAnchors?.let(matched::addAll)

        val resultLike = headerMatch != null || (fullDecision?.isResultLike == true)
        if (!fullLayout) {
            val readings = emptyReadings().toMutableList()
            headerMatch?.line?.let { line ->
                readings.replace(
                    OcrProbeRegion.RESULT_HEADER,
                    line.text,
                    toNormalized(expand(line.bounds, frameWidth * 0.008f, frameHeight * 0.008f), frameWidth, frameHeight)
                )
            }

            val preview = if (headerMatch != null) {
                headerMatch.line.text
            } else {
                topCandidates.joinToString(" ") { it.text }
            }

            return DynamicLayoutAnalysis(
                isResultLike = resultLike,
                matchedAnchors = matched,
                textPreview = preview.collapseWhitespace().take(MAX_PREVIEW_CHARS),
                regionReadings = readings
            )
        }

        val stateLine = findTrackState(lines, gameAdapter, frameWidth, frameHeight)
        val titleLines = findTitleLines(
            lines = lines,
            header = headerMatch?.line,
            state = stateLine,
            frameWidth = frameWidth,
            frameHeight = frameHeight
        )
        val scoreLine = findScoreLine(lines, stateLine, frameWidth, frameHeight)
        val judgementLines = findJudgementLines(
            lines = lines,
            state = stateLine,
            score = scoreLine,
            frameWidth = frameWidth,
            frameHeight = frameHeight
        )

        val readings = emptyReadings().toMutableList()
        headerMatch?.line?.let { line ->
            readings.replace(
                OcrProbeRegion.RESULT_HEADER,
                line.text,
                toNormalized(expand(line.bounds, frameWidth * 0.008f, frameHeight * 0.008f), frameWidth, frameHeight)
            )
        }

        if (titleLines.isNotEmpty()) {
            val bounds = union(titleLines.map { it.bounds })
            readings.replace(
                OcrProbeRegion.TITLE,
                titleLines.sortedBy { it.bounds.top }.joinToString(" / ") { it.text },
                bounds?.let {
                    toNormalized(
                        expand(it, frameWidth * 0.025f, frameHeight * 0.012f),
                        frameWidth,
                        frameHeight
                    )
                }
            )
        }

        stateLine?.let { line ->
            readings.replace(
                OcrProbeRegion.TRACK_STATE,
                line.text,
                toNormalized(expand(line.bounds, frameWidth * 0.02f, frameHeight * 0.012f), frameWidth, frameHeight)
            )
        }

        scoreLine?.let { line ->
            readings.replace(
                OcrProbeRegion.SCORE,
                line.text,
                toNormalized(expand(line.bounds, frameWidth * 0.025f, frameHeight * 0.012f), frameWidth, frameHeight)
            )
        }

        if (judgementLines.isNotEmpty()) {
            val bounds = union(judgementLines.map { it.bounds })
            readings.replace(
                OcrProbeRegion.JUDGEMENTS,
                judgementLines
                    .sortedWith(compareBy<RecognizedLine> { it.bounds.top }.thenBy { it.bounds.left })
                    .joinToString(" / ") { it.text },
                bounds?.let {
                    toNormalized(
                        expand(it, frameWidth * 0.02f, frameHeight * 0.012f),
                        frameWidth,
                        frameHeight
                    )
                }
            )
        }

        val semanticPreview = readings
            .filter { it.text.isNotBlank() }
            .joinToString(" | ") { "${it.label}: ${it.text}" }
            .ifBlank { allText }

        return DynamicLayoutAnalysis(
            isResultLike = resultLike,
            matchedAnchors = matched,
            textPreview = semanticPreview.collapseWhitespace().take(MAX_PREVIEW_CHARS),
            regionReadings = readings
        )
    }

    private fun findTrackState(
        lines: List<RecognizedLine>,
        gameAdapter: GameAdapter,
        frameWidth: Int,
        frameHeight: Int
    ): RecognizedLine? {
        return lines.mapNotNull { line ->
            val decision = gameAdapter.classifyLightText(normalizeForMatch(line.text))
            val state = decision.matchedAnchors.any {
                it == "TRACK COMPLETE" || it == "TRACK LOST"
            }
            if (!state) null else line
        }.maxByOrNull { line ->
            val centrePenalty = abs(line.bounds.exactCenterX() - frameWidth * 0.5f) * 0.04f
            val verticalPenalty = abs(line.bounds.exactCenterY() - frameHeight * 0.30f) * 0.02f
            line.bounds.height() * 4f + line.bounds.width() * 0.06f - centrePenalty - verticalPenalty
        }
    }

    private fun findTitleLines(
        lines: List<RecognizedLine>,
        header: RecognizedLine?,
        state: RecognizedLine?,
        frameWidth: Int,
        frameHeight: Int
    ): List<RecognizedLine> {
        val top = (header?.bounds?.bottom?.toFloat() ?: frameHeight * 0.035f) +
            frameHeight * 0.012f
        val bottom = (state?.bounds?.top?.toFloat() ?: frameHeight * 0.36f) -
            frameHeight * 0.012f
        if (bottom <= top) return emptyList()

        val candidates = lines.filter { line ->
            val centreY = line.bounds.exactCenterY()
            val centreX = line.bounds.exactCenterX()
            centreY in top..bottom &&
                centreX in frameWidth * 0.10f..frameWidth * 0.90f &&
                line.bounds.height() >= frameHeight * MIN_TITLE_LINE_HEIGHT &&
                !isChromeNoise(line.text)
        }

        return candidates
            .sortedByDescending { line ->
                val centrePenalty = abs(line.bounds.exactCenterX() - frameWidth * 0.5f) * 0.08f
                line.bounds.height() * 5f + line.bounds.width() * 0.05f - centrePenalty
            }
            .take(MAX_TITLE_LINES)
            .sortedBy { it.bounds.top }
    }

    private fun findScoreLine(
        lines: List<RecognizedLine>,
        state: RecognizedLine?,
        frameWidth: Int,
        frameHeight: Int
    ): RecognizedLine? {
        val top = (state?.bounds?.bottom?.toFloat() ?: frameHeight * 0.28f) +
            frameHeight * 0.01f
        val bottom = frameHeight * SCORE_MAX_BOTTOM

        return lines.filter { line ->
            val centreY = line.bounds.exactCenterY()
            centreY in top..bottom && looksLikeStandaloneScore(line.text)
        }.maxByOrNull { line ->
            val centrePenalty = abs(line.bounds.exactCenterX() - frameWidth * 0.55f) * 0.03f
            line.bounds.height() * 8f + line.bounds.width() * 0.04f - centrePenalty
        }
    }

    private fun findJudgementLines(
        lines: List<RecognizedLine>,
        state: RecognizedLine?,
        score: RecognizedLine?,
        frameWidth: Int,
        frameHeight: Int
    ): List<RecognizedLine> {
        val lowerAnchor = max(
            score?.bounds?.bottom?.toFloat() ?: 0f,
            state?.bounds?.bottom?.toFloat() ?: frameHeight * 0.34f
        )
        val minY = max(lowerAnchor + frameHeight * 0.05f, frameHeight * 0.48f)

        val labels = lines.filter { line ->
            if (line.bounds.exactCenterY() < minY) return@filter false
            val normalized = normalizeForMatch(line.text)
            !normalized.contains("TRACK") && JUDGEMENT_WORDS.any { normalized.contains(it) }
        }
        if (labels.isEmpty()) return emptyList()

        val nearbyNumbers = lines.filter { line ->
            if (!looksLikeJudgementNumber(line.text)) return@filter false
            labels.any { label ->
                val tolerance = max(label.bounds.height(), line.bounds.height()) * 0.9f
                val sameRow = abs(label.bounds.exactCenterY() - line.bounds.exactCenterY()) <= tolerance
                val horizontalGap = when {
                    line.bounds.left > label.bounds.right -> line.bounds.left - label.bounds.right
                    label.bounds.left > line.bounds.right -> label.bounds.left - line.bounds.right
                    else -> 0
                }
                sameRow && horizontalGap <= frameWidth * 0.22f
            }
        }

        return (labels + nearbyNumbers).distinctBy {
            "${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}:${it.text}"
        }
    }

    private fun looksLikeStandaloneScore(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        if (digits !in 5..8) return false

        val alphaNumeric = text.count { it.isLetterOrDigit() }
        if (alphaNumeric == 0) return false
        return digits.toFloat() / alphaNumeric.toFloat() >= 0.68f
    }

    private fun looksLikeJudgementNumber(text: String): Boolean {
        val trimmed = text.trim()
        val digits = trimmed.count { it.isDigit() }
        if (digits !in 1..5) return false
        return trimmed.all { it.isDigit() || it.isWhitespace() || it in "'’.," }
    }

    private fun isChromeNoise(text: String): Boolean {
        val normalized = normalizeForMatch(text)
        return CHROME_WORDS.any { normalized.contains(it) }
    }

    private fun extractLines(recognized: List<ScriptOcrText>): List<RecognizedLine> {
        val result = ArrayList<RecognizedLine>()
        recognized.forEach { pass ->
            pass.text.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    val bounds = line.boundingBox ?: return@forEach
                    if (line.text.isBlank() || bounds.width() <= 0 || bounds.height() <= 0) return@forEach
                    result += RecognizedLine(line.text.trim(), Rect(bounds), pass.script)
                }
            }
        }
        return result
    }

    private fun deduplicate(lines: List<RecognizedLine>): List<RecognizedLine> {
        val accepted = ArrayList<RecognizedLine>()
        lines.sortedByDescending(::quality).forEach { candidate ->
            if (accepted.none { existing -> sameVisualLine(existing, candidate) }) {
                accepted += candidate
            }
        }
        return accepted.sortedWith(compareBy<RecognizedLine> { it.bounds.top }.thenBy { it.bounds.left })
    }

    private fun sameVisualLine(a: RecognizedLine, b: RecognizedLine): Boolean {
        val intersection = Rect()
        if (!intersection.setIntersect(a.bounds, b.bounds)) return false
        val minArea = min(area(a.bounds), area(b.bounds)).coerceAtLeast(1L)
        val overlap = area(intersection).toFloat() / minArea.toFloat()
        return overlap >= DUPLICATE_OVERLAP
    }

    private fun quality(line: RecognizedLine): Int {
        val text = line.text
        val affinity = when {
            text.any(::isHangul) -> if (line.script == OcrScript.KOREAN) 120 else 10
            text.any(::isKana) -> if (line.script == OcrScript.JAPANESE) 120 else 10
            text.any(::isHan) -> when (line.script) {
                OcrScript.CHINESE -> 100
                OcrScript.JAPANESE -> 90
                else -> 10
            }
            line.script == OcrScript.LATIN -> 80
            else -> 20
        }
        return affinity + text.count { !it.isWhitespace() }
    }

    private fun headerScore(line: RecognizedLine, width: Int, height: Int): Float {
        val topReward = (1f - line.bounds.exactCenterY() / height.toFloat()) * 100f
        val leftReward = (1f - line.bounds.exactCenterX() / width.toFloat()) * 25f
        return topReward + leftReward + line.bounds.height() * 0.2f
    }

    private fun normalizeForMatch(value: String): String = value
        .uppercase(Locale.US)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.collapseWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun emptyReadings(): List<OcrRegionReading> = OcrProbeRegion.ALL.map {
        OcrRegionReading(it.key, it.label, "", null)
    }

    private fun MutableList<OcrRegionReading>.replace(
        spec: OcrProbeRegionSpec,
        text: String,
        bounds: NormalizedOcrRect?
    ) {
        val index = indexOfFirst { it.key == spec.key }
        val reading = OcrRegionReading(spec.key, spec.label, text.collapseWhitespace(), bounds)
        if (index >= 0) this[index] = reading else add(reading)
    }

    private fun union(rects: List<Rect>): Rect? {
        if (rects.isEmpty()) return null
        val result = Rect(rects.first())
        rects.drop(1).forEach(result::union)
        return result
    }

    private fun expand(rect: Rect, dx: Float, dy: Float): Rect = Rect(
        (rect.left - dx).toInt(),
        (rect.top - dy).toInt(),
        (rect.right + dx).toInt(),
        (rect.bottom + dy).toInt()
    )

    private fun toNormalized(rect: Rect, width: Int, height: Int): NormalizedOcrRect =
        NormalizedOcrRect(
            left = rect.left.toFloat() / width.toFloat(),
            top = rect.top.toFloat() / height.toFloat(),
            right = rect.right.toFloat() / width.toFloat(),
            bottom = rect.bottom.toFloat() / height.toFloat()
        ).clamped()

    private fun area(rect: Rect): Long = rect.width().toLong() * rect.height().toLong()

    private fun isHan(char: Char): Boolean = char.code in 0x3400..0x9FFF
    private fun isKana(char: Char): Boolean = char.code in 0x3040..0x30FF
    private fun isHangul(char: Char): Boolean = char.code in 0xAC00..0xD7AF

    private data class RecognizedLine(
        val text: String,
        val bounds: Rect,
        val script: OcrScript
    )

    private data class HeaderMatch(
        val line: RecognizedLine,
        val anchors: Set<String>
    )

    private const val TRIPWIRE_MAX_TOP = 0.35f
    private const val MIN_TITLE_LINE_HEIGHT = 0.018f
    private const val MAX_TITLE_LINES = 3
    private const val SCORE_MAX_BOTTOM = 0.68f
    private const val DUPLICATE_OVERLAP = 0.62f
    private const val MAX_PREVIEW_CHARS = 420

    private val JUDGEMENT_WORDS = listOf("PURE", "FAR", "LOST")
    private val CHROME_WORDS = listOf(
        "RESULT",
        "SYNC",
        "POTENTIAL",
        "FRAGMENTS",
        "MEMORIES",
        "KEEP"
    )
}
