package com.example.rhythmtracker.detection

import com.example.rhythmtracker.identity.ResultIdentity
import com.example.rhythmtracker.identity.ResultIdentityMatcher
import com.example.rhythmtracker.state.ResultSignal
import com.example.rhythmtracker.vision.DebugRegion
import com.example.rhythmtracker.vision.VisionLine
import com.example.rhythmtracker.vision.VisionStage
import java.util.Locale
import kotlin.math.abs

/**
 * Converts raw OCR geometry into result-screen evidence. It deliberately does not own lifecycle,
 * duplicate suppression, capture timing, or persistence.
 */
class ArcaeaResultDetector {
    data class Detection(
        val signal: ResultSignal,
        val regions: List<DebugRegion>,
        val preview: String
    )

    fun detect(lines: List<VisionLine>, visualHash: Long): Detection {
        val resultLine = lines.firstOrNull(::looksLikeResultHeader)
        val trackLine = lines.filter(::looksLikeTrackState)
            .maxByOrNull { it.bounds.width() * it.bounds.height() }
        val maxRecall = lines.firstOrNull(::looksLikeMaxRecall)
        val scoreLine = chooseScoreLine(lines)

        val judgementLines = linkedMapOf<String, VisionLine>()
        JUDGEMENTS.forEach { token ->
            lines.firstOrNull { line ->
                line.bounds.centerY() > line.frameHeight * 0.48f && containsAnchor(line.text, token)
            }?.let { judgementLines[token] = it }
        }

        val anchors = linkedSetOf<String>()
        if (resultLine != null) anchors += "RESULT"
        if (trackLine != null) anchors += trackState(trackLine.text)
        if (maxRecall != null) anchors += "MAX RECALL"
        anchors += judgementLines.keys
        if (scoreLine != null) anchors += "SCORE"

        val strength = buildStrength(
            result = resultLine != null,
            track = trackLine != null,
            score = scoreLine != null,
            judgementCount = judgementLines.size,
            maxRecall = maxRecall != null
        )

        val present = trackLine != null ||
            (resultLine != null && (scoreLine != null || judgementLines.isNotEmpty() || maxRecall != null)) ||
            judgementLines.size >= 2 ||
            (maxRecall != null && scoreLine != null)

        val strong = present && (
            (trackLine != null && scoreLine != null) ||
                (resultLine != null && scoreLine != null) ||
                (trackLine != null && judgementLines.isNotEmpty()) ||
                judgementLines.size >= 2
            )

        val titleHint = chooseTitleHint(lines, resultLine, trackLine)
        val score = scoreLine?.let { parseScore(it.text) }
        val identity = ResultIdentity(
            score = score,
            titleKey = ResultIdentityMatcher.normalizeTitle(titleHint),
            visualHash = visualHash
        )

        val regions = buildList {
            resultLine?.let { add(DebugRegion("result", it.normalizedBounds, VisionStage.LIGHT)) }
            trackLine?.let { add(DebugRegion("track", it.normalizedBounds, VisionStage.LIGHT)) }
            scoreLine?.let { add(DebugRegion("score", it.normalizedBounds, VisionStage.LIGHT)) }
            judgementLines.values.forEach { add(DebugRegion("judgement", it.normalizedBounds, VisionStage.LIGHT)) }
        }

        val preview = lines.sortedWith(compareBy<VisionLine> { it.bounds.top }.thenBy { it.bounds.left })
            .joinToString(" | ") { it.text }
            .take(360)

        return Detection(
            signal = ResultSignal(
                present = present,
                strong = strong,
                strength = strength,
                anchors = anchors,
                identity = identity
            ),
            regions = regions,
            preview = preview
        )
    }

    private fun buildStrength(
        result: Boolean,
        track: Boolean,
        score: Boolean,
        judgementCount: Int,
        maxRecall: Boolean
    ): Float {
        var value = 0f
        if (result) value += 0.38f
        if (track) value += 0.52f
        if (score) value += 0.20f
        if (maxRecall) value += 0.16f
        value += minOf(judgementCount, 2) * 0.14f
        return value.coerceIn(0f, 1f)
    }

    private fun chooseTitleHint(
        lines: List<VisionLine>,
        resultLine: VisionLine?,
        trackLine: VisionLine?
    ): String? {
        if (lines.isEmpty()) return null
        val frameHeight = lines.first().frameHeight
        val frameWidth = lines.first().frameWidth
        val top = maxOf(
            (frameHeight * 0.055f).toInt(),
            resultLine?.bounds?.bottom?.plus((frameHeight * 0.006f).toInt()) ?: 0
        )
        val bottom = minOf(
            (frameHeight * 0.36f).toInt(),
            trackLine?.bounds?.top?.minus((frameHeight * 0.006f).toInt()) ?: Int.MAX_VALUE
        )
        if (bottom <= top) return null

        return lines.asSequence()
            .filter { line ->
                line.bounds.centerY() in top..bottom &&
                    line.bounds.centerX() > frameWidth * 0.08f &&
                    line.bounds.centerX() < frameWidth * 0.92f &&
                    !looksLikeResultHeader(line) &&
                    !looksLikeTrackState(line) &&
                    !looksLikeMaxRecall(line) &&
                    parseScore(line.text) == null &&
                    !HEADER_CHROME.any { containsAnchor(line.text, it) }
            }
            .maxByOrNull { line -> line.bounds.height() * 4 + line.bounds.width() }
            ?.text
    }

    private fun chooseScoreLine(lines: List<VisionLine>): VisionLine? = lines
        .filter { line ->
            val y = line.bounds.centerY().toFloat() / line.frameHeight.coerceAtLeast(1)
            y in 0.20f..0.72f && parseScore(line.text) != null &&
                !normalize(line.text).contains("HIGH SCORE")
        }
        .maxWithOrNull(compareBy<VisionLine> { it.bounds.height() }.thenBy { it.bounds.width() })

    fun parseScore(raw: String): Long? {
        val normalized = raw.asSequence()
            .map { char ->
                when (char) {
                    'O', 'o' -> '0'
                    'I', 'l', '|' -> '1'
                    else -> char
                }
            }
            .filter { it.isDigit() }
            .joinToString("")

        if (normalized.length !in 7..8) return null
        val value = normalized.toLongOrNull() ?: return null
        return value.takeIf { it in 0L..10_000_000L }
    }

    fun looksLikeTrackState(line: VisionLine): Boolean = looksLikeTrackState(line.text)

    fun looksLikeTrackState(raw: String): Boolean {
        val compact = normalize(raw).filter { it.isLetterOrDigit() }
        val trackAt = compact.indexOf("TRACK")
        if (trackAt < 0) return false
        val suffix = compact.substring(trackAt + 5)
        return prefixClose(suffix, "COMPLETE") || prefixClose(suffix, "LOST")
    }

    fun trackState(raw: String): String {
        val compact = normalize(raw).filter { it.isLetterOrDigit() }
        return if (compact.contains("LOST") || compact.contains("L0ST")) {
            "TRACK LOST"
        } else {
            "TRACK COMPLETE"
        }
    }

    private fun looksLikeResultHeader(line: VisionLine): Boolean {
        val words = line.text.split(Regex("\\s+"))
        return words.any { word ->
            val compact = word.uppercase(Locale.US).filter { it.isLetterOrDigit() }
            compact == "RESULT" ||
                (compact.length in 5..7 && levenshtein(compact, "RESULT", 1) <= 1)
        }
    }

    private fun looksLikeMaxRecall(line: VisionLine): Boolean {
        val compact = normalize(line.text).filter { it.isLetterOrDigit() }
        return compact.contains("MAXRECALL") ||
            (compact.length >= 7 && levenshtein(compact.take(9), "MAXRECALL", 2) <= 2)
    }

    private fun prefixClose(observed: String, expected: String): Boolean {
        val maxLength = minOf(observed.length, expected.length)
        if (maxLength < 4) return false
        for (length in 4..maxLength) {
            val maxEdits = if (length >= 7) 2 else 1
            if (levenshtein(observed.take(length), expected.take(length), maxEdits) <= maxEdits) {
                return true
            }
        }
        return false
    }

    private fun containsAnchor(raw: String, token: String): Boolean {
        val compact = normalize(raw).filter { it.isLetterOrDigit() }
        return when (token) {
            "LOST" -> compact.contains("LOST") || compact.contains("L0ST")
            else -> compact.contains(token.filter { it.isLetterOrDigit() })
        }
    }

    private fun normalize(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim().uppercase(Locale.US)

    private fun levenshtein(a: String, b: String, stopAfter: Int): Int {
        if (a == b) return 0
        if (abs(a.length - b.length) > stopAfter) return stopAfter + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, current[j - 1] + 1, previous[j] + 1)
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > stopAfter) return stopAfter + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    companion object {
        private val JUDGEMENTS = setOf("PURE", "FAR", "LOST")
        private val HEADER_CHROME = setOf("SYNC", "POTENTIAL", "FRAGMENTS", "MEMORIES", "KEEP")
    }
}
