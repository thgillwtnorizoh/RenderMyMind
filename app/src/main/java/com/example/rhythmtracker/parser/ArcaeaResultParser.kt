package com.example.rhythmtracker.parser

import com.example.rhythmtracker.detection.ArcaeaResultDetector
import com.example.rhythmtracker.game.arcaea.ArcaeaResultLayout
import com.example.rhythmtracker.vision.DebugRegion
import com.example.rhythmtracker.vision.VisionLine
import com.example.rhythmtracker.vision.VisionStage
import kotlin.math.abs

/** Full-resolution parser. It extracts fields but never decides whether a frame is a duplicate. */
class ArcaeaResultParser(
    private val detector: ArcaeaResultDetector = ArcaeaResultDetector()
) {
    data class Parsed(
        val title: String?,
        val artist: String?,
        val trackState: String?,
        val score: Long?,
        val pure: Int?,
        val far: Int?,
        val lost: Int?,
        val confidence: Float,
        val regions: List<DebugRegion>,
        val rawText: String
    )

    fun parse(lines: List<VisionLine>): Parsed {
        if (lines.isEmpty()) {
            return Parsed(null, null, null, null, null, null, null, 0f, emptyList(), "")
        }

        val trackLine = lines
            .filter(ArcaeaResultLayout::isTrackBand)
            .filter(detector::looksLikeTrackState)
            .maxByOrNull { it.bounds.width() * it.bounds.height() }
        val scoreLine = chooseScoreLine(lines)
        val titleLine = chooseTitleLine(lines, trackLine)
        val artistLine = chooseArtistLine(lines, titleLine, trackLine)

        val purePair = judgement(lines, "PURE")
        val farPair = judgement(lines, "FAR")
        val lostPair = judgement(lines, "LOST")

        val trackState = trackLine?.let { detector.trackState(it.text) }
        val score = scoreLine?.let { detector.parseScore(it.text) }

        var points = 0f
        var possible = 0f
        possible += 0.20f
        if (titleLine != null) points += 0.20f
        possible += 0.20f
        if (trackLine != null) points += 0.20f
        possible += 0.25f
        if (score != null) points += 0.25f
        possible += 0.35f
        val judgementCount = listOf(purePair.second, farPair.second, lostPair.second).count { it != null }
        points += judgementCount * (0.35f / 3f)
        val confidence = if (possible <= 0f) 0f else (points / possible).coerceIn(0f, 1f)

        val regions = buildList {
            titleLine?.let { add(DebugRegion("title", it.normalizedBounds, VisionStage.NATIVE)) }
            artistLine?.let { add(DebugRegion("artist", it.normalizedBounds, VisionStage.NATIVE)) }
            trackLine?.let { add(DebugRegion("track", it.normalizedBounds, VisionStage.NATIVE)) }
            scoreLine?.let { add(DebugRegion("score", it.normalizedBounds, VisionStage.NATIVE)) }
            purePair.first?.let { add(DebugRegion("pure", it.normalizedBounds, VisionStage.NATIVE)) }
            farPair.first?.let { add(DebugRegion("far", it.normalizedBounds, VisionStage.NATIVE)) }
            lostPair.first?.let { add(DebugRegion("lost", it.normalizedBounds, VisionStage.NATIVE)) }
        }

        val rawText = lines.sortedWith(compareBy<VisionLine> { it.bounds.top }.thenBy { it.bounds.left })
            .joinToString(" | ") { it.text }
            .take(1200)

        return Parsed(
            title = titleLine?.text,
            artist = artistLine?.text,
            trackState = trackState,
            score = score,
            pure = purePair.second,
            far = farPair.second,
            lost = lostPair.second,
            confidence = confidence,
            regions = regions,
            rawText = rawText
        )
    }

    private fun chooseScoreLine(lines: List<VisionLine>): VisionLine? = lines
        .filter(ArcaeaResultLayout::isScoreBand)
        .filter { line ->
            detector.parseScore(line.text) != null && !line.text.uppercase().contains("HIGH SCORE")
        }
        .maxWithOrNull(compareBy<VisionLine> { it.bounds.height() }.thenBy { it.bounds.width() })

    private fun chooseTitleLine(lines: List<VisionLine>, trackLine: VisionLine?): VisionLine? {
        val frameHeight = lines.first().frameHeight
        val trackTop = trackLine?.bounds?.top ?: (frameHeight * 0.40f).toInt()
        return lines.asSequence()
            .filter(ArcaeaResultLayout::isTitleBand)
            .filter { line ->
                line.bounds.centerY() < trackTop &&
                    detector.parseScore(line.text) == null &&
                    !detector.looksLikeTrackState(line) &&
                    !isChrome(line.text)
            }
            .maxByOrNull { line -> line.bounds.height() * 5 + line.bounds.width() }
    }

    private fun chooseArtistLine(
        lines: List<VisionLine>,
        titleLine: VisionLine?,
        trackLine: VisionLine?
    ): VisionLine? {
        titleLine ?: return null
        val frameHeight = titleLine.frameHeight
        val trackTop = trackLine?.bounds?.top ?: (frameHeight * 0.40f).toInt()
        return lines.asSequence()
            .filter(ArcaeaResultLayout::isTitleBand)
            .filter { line ->
                line !== titleLine &&
                    line.bounds.centerY() >= titleLine.bounds.bottom &&
                    line.bounds.centerY() < trackTop &&
                    abs(line.bounds.centerX() - titleLine.bounds.centerX()) < titleLine.frameWidth * 0.28f &&
                    detector.parseScore(line.text) == null &&
                    !isChrome(line.text)
            }
            .minByOrNull { it.bounds.top - titleLine.bounds.bottom }
    }

    private fun judgement(lines: List<VisionLine>, token: String): Pair<VisionLine?, Int?> {
        val label = lines.firstOrNull { line ->
            ArcaeaResultLayout.isJudgementBand(line) && containsToken(line.text, token)
        } ?: return null to null

        parseSmallNumber(label.text.replace(token, "", ignoreCase = true))?.let { return label to it }

        val tolerance = label.frameHeight * 0.035f
        val nearby = lines.asSequence()
            .filter(ArcaeaResultLayout::isJudgementBand)
            .filter { candidate ->
                candidate !== label &&
                    abs(candidate.bounds.centerY() - label.bounds.centerY()) <= tolerance &&
                    candidate.bounds.centerX() > label.bounds.centerX() - label.frameWidth * 0.04f
            }
            .mapNotNull { candidate -> parseSmallNumber(candidate.text)?.let { candidate to it } }
            .minByOrNull { (candidate, _) ->
                abs(candidate.bounds.centerX() - label.bounds.right)
            }

        return nearby ?: (label to null)
    }

    private fun parseSmallNumber(raw: String): Int? {
        val digits = raw.asSequence()
            .map { char ->
                when (char) {
                    'O', 'o' -> '0'
                    'I', 'l', '|' -> '1'
                    else -> char
                }
            }
            .filter { it.isDigit() }
            .joinToString("")
        if (digits.isEmpty() || digits.length > 5) return null
        return digits.toIntOrNull()
    }

    private fun containsToken(raw: String, token: String): Boolean {
        val compact = raw.uppercase().filter { it.isLetterOrDigit() }
        return if (token == "LOST") {
            compact.contains("LOST") || compact.contains("L0ST")
        } else {
            compact.contains(token)
        }
    }

    private fun isChrome(raw: String): Boolean {
        val upper = raw.uppercase()
        return listOf(
            "RESULT", "SYNC", "POTENTIAL", "FRAGMENTS", "MEMORIES", "KEEP",
            "BACK", "SHARE", "RETRY", "PARTNER", "FIRST CLEAR", "MAX RECALL"
        ).any { it in upper }
    }
}
