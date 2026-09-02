package com.example.rhythmtracker.parser

import com.example.rhythmtracker.game.arcaea.ArcaeaResultLayout
import com.example.rhythmtracker.vision.VisionLine
import kotlin.math.abs

/**
 * Uses the resolved chart note count as a checksum for PURE/FAR/LOST.
 *
 * The database is never used to invent an entire judgement triplet. It may:
 *  1. validate three OCR values that sum exactly to the chart note count;
 *  2. choose a better nearby OCR candidate when the first parser guess is impossible;
 *  3. derive exactly one missing field when the other two are trustworthy.
 */
object ArcaeaJudgementReconciler {
    data class Result(
        val pure: Int?,
        val far: Int?,
        val lost: Int?,
        val pureBasis: String,
        val farBasis: String,
        val lostBasis: String,
        val noteCount: Int?,
        val checksumSum: Int?,
        val checksumMatched: Boolean,
        val usedDerivation: Boolean
    ) {
        fun checksumDescription(): String = when {
            noteCount == null -> "not available"
            checksumMatched && checksumSum != null -> "$checksumSum/$noteCount OK"
            checksumSum != null -> "$checksumSum/$noteCount MISMATCH"
            else -> "partial/$noteCount"
        }

        fun basisDescription(): String =
            "$pureBasis / $farBasis / $lostBasis"

        fun adjustConfidence(base: Float): Float = when {
            noteCount == null -> base
            checksumMatched && !usedDerivation -> (base + 0.12f).coerceAtMost(1f)
            checksumMatched -> (base + 0.08f).coerceAtMost(1f)
            checksumSum != null -> (base - 0.25f).coerceAtLeast(0f)
            else -> base
        }
    }

    private data class Candidate(
        val value: Int,
        val cost: Float,
        val basis: String
    )

    private data class Triple(
        val pure: Candidate,
        val far: Candidate,
        val lost: Candidate,
        val cost: Float
    )

    fun reconcile(
        lines: List<VisionLine>,
        initialPure: Int?,
        initialFar: Int?,
        initialLost: Int?,
        noteCount: Int?
    ): Result {
        if (noteCount == null || noteCount <= 0) {
            return Result(
                pure = initialPure,
                far = initialFar,
                lost = initialLost,
                pureBasis = if (initialPure != null) "ocr" else "missing",
                farBasis = if (initialFar != null) "ocr" else "missing",
                lostBasis = if (initialLost != null) "ocr" else "missing",
                noteCount = noteCount,
                checksumSum = sumOrNull(initialPure, initialFar, initialLost),
                checksumMatched = false,
                usedDerivation = false
            )
        }

        val pureCandidates = candidates(lines, "PURE", initialPure, noteCount)
        val farCandidates = candidates(lines, "FAR", initialFar, noteCount)
        val lostCandidates = candidates(lines, "LOST", initialLost, noteCount)

        bestExactTriple(pureCandidates, farCandidates, lostCandidates, noteCount)?.let { triple ->
            return Result(
                pure = triple.pure.value,
                far = triple.far.value,
                lost = triple.lost.value,
                pureBasis = triple.pure.basis + "+note-check",
                farBasis = triple.far.basis + "+note-check",
                lostBasis = triple.lost.basis + "+note-check",
                noteCount = noteCount,
                checksumSum = noteCount,
                checksumMatched = true,
                usedDerivation = false
            )
        }

        bestTwoPlusDerived(pureCandidates, farCandidates, lostCandidates, noteCount)?.let { result ->
            return result
        }

        val pure = initialPure?.takeIf { it in 0..noteCount }
        val far = initialFar?.takeIf { it in 0..noteCount }
        val lost = initialLost?.takeIf { it in 0..noteCount }
        val sum = sumOrNull(pure, far, lost)
        return Result(
            pure = pure,
            far = far,
            lost = lost,
            pureBasis = fallbackBasis(initialPure, pure),
            farBasis = fallbackBasis(initialFar, far),
            lostBasis = fallbackBasis(initialLost, lost),
            noteCount = noteCount,
            checksumSum = sum,
            checksumMatched = sum == noteCount,
            usedDerivation = false
        )
    }

    private fun bestExactTriple(
        pure: List<Candidate>,
        far: List<Candidate>,
        lost: List<Candidate>,
        noteCount: Int
    ): Triple? {
        var best: Triple? = null
        for (p in pure) {
            for (f in far) {
                val remaining = noteCount - p.value - f.value
                if (remaining < 0) continue
                for (l in lost) {
                    if (l.value != remaining) continue
                    val cost = p.cost + f.cost + l.cost
                    if (best == null || cost < best!!.cost) {
                        best = Triple(p, f, l, cost)
                    }
                }
            }
        }
        return best
    }

    private fun bestTwoPlusDerived(
        pure: List<Candidate>,
        far: List<Candidate>,
        lost: List<Candidate>,
        noteCount: Int
    ): Result? {
        data class DerivedChoice(
            val result: Result,
            val cost: Float
        )

        val choices = mutableListOf<DerivedChoice>()

        for (p in pure) for (f in far) {
            val derived = noteCount - p.value - f.value
            if (derived < 0) continue
            choices += DerivedChoice(
                Result(
                    pure = p.value,
                    far = f.value,
                    lost = derived,
                    pureBasis = p.basis + "+note-check",
                    farBasis = f.basis + "+note-check",
                    lostBasis = "derived-note-count",
                    noteCount = noteCount,
                    checksumSum = noteCount,
                    checksumMatched = true,
                    usedDerivation = true
                ),
                p.cost + f.cost + 0.35f
            )
        }

        for (p in pure) for (l in lost) {
            val derived = noteCount - p.value - l.value
            if (derived < 0) continue
            choices += DerivedChoice(
                Result(
                    pure = p.value,
                    far = derived,
                    lost = l.value,
                    pureBasis = p.basis + "+note-check",
                    farBasis = "derived-note-count",
                    lostBasis = l.basis + "+note-check",
                    noteCount = noteCount,
                    checksumSum = noteCount,
                    checksumMatched = true,
                    usedDerivation = true
                ),
                p.cost + l.cost + 0.35f
            )
        }

        for (f in far) for (l in lost) {
            val derived = noteCount - f.value - l.value
            if (derived < 0) continue
            choices += DerivedChoice(
                Result(
                    pure = derived,
                    far = f.value,
                    lost = l.value,
                    pureBasis = "derived-note-count",
                    farBasis = f.basis + "+note-check",
                    lostBasis = l.basis + "+note-check",
                    noteCount = noteCount,
                    checksumSum = noteCount,
                    checksumMatched = true,
                    usedDerivation = true
                ),
                f.cost + l.cost + 0.35f
            )
        }

        return choices.minByOrNull { it.cost }?.result
    }

    private fun candidates(
        lines: List<VisionLine>,
        token: String,
        initial: Int?,
        noteCount: Int
    ): List<Candidate> {
        val collected = mutableListOf<Candidate>()
        initial?.takeIf { it in 0..noteCount }?.let {
            collected += Candidate(it, 0.22f, "parser-ocr")
        }

        val labels = lines.filter { line ->
            ArcaeaResultLayout.isJudgementBand(line) && containsToken(line.text, token)
        }

        labels.forEach { label ->
            firstNumberAfterToken(label.text, token)?.takeIf { it in 0..noteCount }?.let {
                collected += Candidate(it, 0f, "ocr-same-line")
            }

            val maxDx = label.frameWidth * 0.16f
            val minDx = -label.frameWidth * 0.015f
            val maxDy = label.frameHeight * 0.022f
            val frameWidth = label.frameWidth.coerceAtLeast(1).toFloat()
            val frameHeight = label.frameHeight.coerceAtLeast(1).toFloat()
            lines.asSequence()
                .filter(ArcaeaResultLayout::isJudgementBand)
                .filter { it !== label }
                .mapNotNull { candidate ->
                    val dx = (candidate.bounds.left - label.bounds.right).toFloat()
                    val dy = abs(candidate.bounds.centerY() - label.bounds.centerY()).toFloat()
                    if (dx !in minDx..maxDx || dy > maxDy) return@mapNotNull null
                    val value = firstNumber(candidate.text)?.takeIf { it in 0..noteCount }
                        ?: return@mapNotNull null
                    val geometryCost = 0.08f +
                        (abs(dx) / frameWidth) * 2.2f +
                        (dy / frameHeight) * 2.8f
                    Candidate(value, geometryCost, "ocr-near-label")
                }
                .sortedBy { it.cost }
                .take(6)
                .forEach(collected::add)
        }

        return collected
            .groupBy { it.value }
            .map { (_, sameValue) -> sameValue.minBy { it.cost } }
            .sortedBy { it.cost }
            .take(8)
    }

    private fun firstNumberAfterToken(raw: String, token: String): Int? {
        val compactToken = if (token == "LOST") Regex("L[O0]ST", RegexOption.IGNORE_CASE)
            else Regex(Regex.escape(token), RegexOption.IGNORE_CASE)
        val match = compactToken.find(raw) ?: return null
        return firstNumber(raw.substring(match.range.last + 1))
    }

    private fun firstNumber(raw: String): Int? {
        val normalized = buildString(raw.length) {
            raw.forEach { ch ->
                append(
                    when (ch) {
                        'O', 'o' -> '0'
                        'I', 'l', '|' -> '1'
                        else -> ch
                    }
                )
            }
        }
        return Regex("(?<!\\d)\\d{1,5}(?!\\d)")
            .find(normalized)
            ?.value
            ?.toIntOrNull()
    }

    private fun containsToken(raw: String, token: String): Boolean {
        val compact = raw.uppercase().filter { it.isLetterOrDigit() }
        return if (token == "LOST") {
            compact.contains("LOST") || compact.contains("L0ST")
        } else {
            compact.contains(token)
        }
    }

    private fun sumOrNull(pure: Int?, far: Int?, lost: Int?): Int? =
        if (pure != null && far != null && lost != null) pure + far + lost else null

    private fun fallbackBasis(original: Int?, accepted: Int?): String = when {
        accepted != null -> "parser-ocr-unverified"
        original != null -> "rejected-by-note-count"
        else -> "missing"
    }
}
