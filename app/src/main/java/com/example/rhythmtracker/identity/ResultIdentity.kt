package com.example.rhythmtracker.identity

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** A deliberately fuzzy identity. OCR text is evidence, not a primary key. */
data class ResultIdentity(
    val score: Long?,
    val titleKey: String?,
    val visualHash: Long
)

enum class IdentityRelation {
    SAME,
    DIFFERENT,
    UNKNOWN
}

object ResultIdentityMatcher {
    fun compare(previous: ResultIdentity, current: ResultIdentity): IdentityRelation {
        val hashDistance = java.lang.Long.bitCount(previous.visualHash xor current.visualHash)
        val sameTitle = titlesSimilar(previous.titleKey, current.titleKey)
        val titlesClearlyDifferent = titlesClearlyDifferent(previous.titleKey, current.titleKey)
        val scoresEqual = previous.score != null && current.score != null && previous.score == current.score
        val scoresDifferent = previous.score != null && current.score != null && previous.score != current.score

        if (scoresEqual && hashDistance <= 14) return IdentityRelation.SAME
        if (sameTitle && hashDistance <= 12) return IdentityRelation.SAME

        // This is the important anti-spaghetti rule learned from 0.3.7: one OCR digit changing
        // cannot create a new play if the pixels are effectively the same result screen.
        if (hashDistance <= 7) return IdentityRelation.SAME

        if (titlesClearlyDifferent && hashDistance >= 9) return IdentityRelation.DIFFERENT
        if (scoresDifferent && hashDistance >= 13) return IdentityRelation.DIFFERENT
        if (hashDistance >= 22) return IdentityRelation.DIFFERENT

        return IdentityRelation.UNKNOWN
    }

    /** Enrich a stable live identity without replacing trusted fields with one-frame OCR noise. */
    fun merge(stable: ResultIdentity, newer: ResultIdentity): ResultIdentity {
        val relation = compare(stable, newer)
        if (relation == IdentityRelation.DIFFERENT) return newer
        return ResultIdentity(
            score = stable.score ?: newer.score,
            titleKey = stable.titleKey ?: newer.titleKey,
            visualHash = newer.visualHash
        )
    }

    fun normalizeTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .takeIf { it.length >= 2 }
    }

    private fun titlesSimilar(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a == b) return true
        val maxLength = maxOf(a.length, b.length)
        if (maxLength == 0) return true
        val distance = levenshtein(a, b, 3)
        return distance <= 2 || (distance == 3 && maxLength >= 12)
    }

    private fun titlesClearlyDifferent(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val maxLength = maxOf(a.length, b.length)
        if (maxLength < 4) return false
        val distance = levenshtein(a, b, 5)
        return distance >= 4 && abs(a.length - b.length) + 2 < maxLength
    }

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
}
