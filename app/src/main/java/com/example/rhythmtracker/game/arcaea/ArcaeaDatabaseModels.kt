package com.example.rhythmtracker.game.arcaea

import java.util.Locale

/** Canonical in-app model for Arcaea database schema v2. */
data class ArcaeaDatabaseDocument(
    val format: String,
    val schemaVersion: Int,
    val sourceUpdatedAt: String?,
    val sourceFormat: String?,
    val songs: List<ArcaeaSongDefinition>
)

data class ArcaeaSongDefinition(
    val id: String,
    val title: String,
    val aliases: List<String>,
    val charts: List<ArcaeaChartDefinition>
)

data class ArcaeaChartDefinition(
    val difficulty: String,
    val level: String?,
    val constant: Double?,
    val notes: Int?,
    val variantTitle: String?,
    val variantAliases: List<String>,
    val classification: ArcaeaChartClassification,
    val visibility: ArcaeaChartVisibility
) {
    fun hiddenBeforeUnlock(): Boolean? = visibility.hiddenBeforeUnlock()

    fun visibilityDescription(): String = visibility.description()

    fun classificationDescription(): String = classification.description()
}

/**
 * Keeps both our semantic difficulty and the source songlist classification.
 *
 * BYD and INS intentionally share ratingClass=3. bydType is the normalized discriminator:
 *   0 = Beyond
 *   1 = Inscribed
 *
 * ratingClassAlias is preserved separately because it is source data, not our semantic key.
 */
data class ArcaeaChartClassification(
    val ratingClass: Int,
    val ratingClassAlias: Int?,
    val bydType: Int?,
    val source: String
) {
    fun isInscribed(): Boolean = ratingClass == 3 && bydType == BYD_TYPE_INSCRIBED

    fun isBeyond(): Boolean = ratingClass == 3 && bydType == BYD_TYPE_BEYOND

    fun semanticDifficulty(): String? = when (ratingClass) {
        0 -> "PST"
        1 -> "PRS"
        2 -> "FTR"
        3 -> when (bydType) {
            BYD_TYPE_BEYOND -> "BYD"
            BYD_TYPE_INSCRIBED -> "INS"
            else -> null
        }
        4 -> "ETR"
        else -> null
    }

    fun description(): String = buildString {
        append("ratingClass=")
        append(ratingClass)
        append(" / alias=")
        append(ratingClassAlias ?: "-")
        append(" / byd_type=")
        append(bydType ?: "-")
        append(" / ")
        append(source)
    }

    fun validationError(expectedDifficulty: String): String? {
        val normalizedExpected = normalizeDifficulty(expectedDifficulty) ?: return null
        val semantic = semanticDifficulty()
        if (semantic != normalizedExpected) {
            return "$normalizedExpected classified as ${semantic ?: "unknown"} ($description())"
        }
        if (normalizedExpected == "INS" && ratingClassAlias != 1) {
            return "INS requires ratingClassAlias=1 ($description())"
        }
        return null
    }

    companion object {
        const val BYD_TYPE_BEYOND = 0
        const val BYD_TYPE_INSCRIBED = 1

        fun fromSemantic(difficulty: String, source: String = "inferred-semantic"): ArcaeaChartClassification {
            return when (normalizeDifficulty(difficulty)) {
                "PST" -> ArcaeaChartClassification(0, null, null, source)
                "PRS" -> ArcaeaChartClassification(1, null, null, source)
                "FTR" -> ArcaeaChartClassification(2, null, null, source)
                "BYD" -> ArcaeaChartClassification(3, null, BYD_TYPE_BEYOND, source)
                "ETR" -> ArcaeaChartClassification(4, null, null, source)
                "INS" -> ArcaeaChartClassification(3, 1, BYD_TYPE_INSCRIBED, source)
                else -> ArcaeaChartClassification(-1, null, null, source)
            }
        }

        fun fromSonglist(
            ratingClass: Int,
            ratingClassAlias: Int?,
            source: String = "songlist"
        ): ArcaeaChartClassification {
            val bydType = if (ratingClass == 3) {
                if (ratingClassAlias == 1) BYD_TYPE_INSCRIBED else BYD_TYPE_BEYOND
            } else {
                null
            }
            return ArcaeaChartClassification(
                ratingClass = ratingClass,
                ratingClassAlias = ratingClassAlias,
                bydType = bydType,
                source = source
            )
        }

        fun normalizeDifficulty(raw: String?): String? {
            val value = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
            return when (value) {
                "PST", "PAST" -> "PST"
                "PRS", "PRESENT" -> "PRS"
                "FTR", "FUTURE" -> "FTR"
                "BYD", "BEYOND" -> "BYD"
                "ETR", "ETERNAL" -> "ETR"
                "INS", "INSCRIBED" -> "INS"
                else -> null
            }
        }
    }
}

data class ArcaeaChartVisibility(
    val hiddenUntilUnlocked: Boolean?,
    val hiddenUntil: String?
) {
    /** null means the imported source carries no usable visibility metadata. */
    fun hiddenBeforeUnlock(): Boolean? {
        hiddenUntilUnlocked?.let { return it }
        val condition = hiddenUntil?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (condition) {
            "none" -> false
            "always", "difficulty", "song", "unlockconditions" -> true
            else -> null
        }
    }

    fun description(): String = when {
        hiddenUntilUnlocked == true -> "hidden_until_unlocked"
        hiddenUntilUnlocked == false -> "visible"
        !hiddenUntil.isNullOrBlank() -> "hidden_until=$hiddenUntil"
        else -> "metadata unavailable"
    }
}

data class ArcaeaTitleResolution(
    val song: ArcaeaSongDefinition,
    val chart: ArcaeaChartDefinition?,
    val matchKind: String,
    val confidence: Float
)
