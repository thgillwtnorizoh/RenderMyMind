package com.example.rhythmtracker.game.arcaea

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale

/**
 * Parses both the old cheeseburger schema and the canonical tracker database schema v2.
 *
 * Legacy input is upgraded in memory by deriving chart classification from semantic chart keys.
 * New v2 databases should carry classification explicitly so source songlist metadata is retained.
 */
object ArcaeaDatabaseCodec {
    const val FORMAT_V2 = "arcaea_tracker_database"
    const val FORMAT_LEGACY = "arcaea_wiki_entries"
    const val SCHEMA_V2 = 2

    fun parse(input: InputStream): ArcaeaDatabaseDocument =
        input.bufferedReader().use { parse(JSONObject(it.readText())) }

    fun parse(root: JSONObject): ArcaeaDatabaseDocument {
        val format = root.optString("format")
        val schema = root.optInt("schema_version", if (format == FORMAT_LEGACY) 1 else 0)

        require(format == FORMAT_V2 || format == FORMAT_LEGACY) {
            "Unsupported Arcaea database format: ${format.ifBlank { "<missing>" }}"
        }
        require(schema in 1..SCHEMA_V2) {
            "Unsupported Arcaea database schema_version=$schema"
        }

        val entries = root.optJSONArray("entries") ?: error("Database has no entries array")
        val songs = ArrayList<ArcaeaSongDefinition>(entries.length())

        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            parseSong(entry, schema)?.let(songs::add)
        }

        return ArcaeaDatabaseDocument(
            format = format,
            schemaVersion = schema,
            sourceUpdatedAt = root.optNullableString("updated_at"),
            sourceFormat = root.optNullableString("source_format"),
            songs = songs
        )
    }

    private fun parseSong(entry: JSONObject, schema: Int): ArcaeaSongDefinition? {
        val song = entry.optJSONObject("song") ?: return null
        val songId = song.optNullableString("id") ?: return null
        val title = song.optNullableString("title") ?: return null
        val aliases = song.optJSONArray("title_aliases")?.stringList().orEmpty()
        val chartsObject = entry.optJSONObject("charts") ?: JSONObject()
        val charts = ArrayList<ArcaeaChartDefinition>(6)

        val keys = chartsObject.keys()
        while (keys.hasNext()) {
            val rawDifficulty = keys.next()
            val difficulty = ArcaeaChartClassification.normalizeDifficulty(rawDifficulty)
                ?: rawDifficulty.uppercase(Locale.ROOT)
            val chart = chartsObject.optJSONObject(rawDifficulty) ?: continue
            val level = chart.optNullableString("level")
            val constant = chart.optNullableDouble("constant")
            val notes = chart.optNullableInt("notes")
            if (level == null && constant == null && notes == null) continue

            charts += ArcaeaChartDefinition(
                difficulty = difficulty,
                level = level,
                constant = constant,
                notes = notes,
                variantTitle = chart.optNullableString("variant_title"),
                variantAliases = chart.optJSONArray("variant_title_aliases")?.stringList().orEmpty(),
                classification = parseClassification(chart, difficulty, schema),
                visibility = parseVisibility(chart)
            )
        }

        return ArcaeaSongDefinition(
            id = songId,
            title = title,
            aliases = aliases,
            charts = charts.sortedBy { difficultyOrder(it.difficulty) }
        )
    }

    private fun parseClassification(
        chart: JSONObject,
        difficulty: String,
        schema: Int
    ): ArcaeaChartClassification {
        val objectValue = chart.optJSONObject("classification")
        if (objectValue != null) {
            val ratingClass = objectValue.optNullableInt("ratingClass")
            val alias = objectValue.optNullableInt("ratingClassAlias")
            val bydType = objectValue.optNullableInt("bydType")
            val source = objectValue.optNullableString("source") ?: "database-v2"

            if (ratingClass != null) {
                val normalizedBydType = when {
                    ratingClass != 3 -> null
                    bydType != null -> bydType
                    alias == 1 -> ArcaeaChartClassification.BYD_TYPE_INSCRIBED
                    else -> ArcaeaChartClassification.BYD_TYPE_BEYOND
                }
                return ArcaeaChartClassification(
                    ratingClass = ratingClass,
                    ratingClassAlias = alias,
                    bydType = normalizedBydType,
                    source = source
                )
            }
        }

        // Transitional compatibility for early experiments that placed source fields directly
        // on the chart object.
        val directRatingClass = chart.optNullableInt("ratingClass")
        if (directRatingClass != null) {
            return ArcaeaChartClassification.fromSonglist(
                ratingClass = directRatingClass,
                ratingClassAlias = chart.optNullableInt("ratingClassAlias"),
                source = "songlist-direct"
            )
        }

        return ArcaeaChartClassification.fromSemantic(
            difficulty = difficulty,
            source = if (schema >= SCHEMA_V2) "v2-fallback-semantic" else "legacy-v1-semantic"
        )
    }

    private fun parseVisibility(chart: JSONObject): ArcaeaChartVisibility {
        val nested = chart.optJSONObject("visibility")
        return ArcaeaChartVisibility(
            hiddenUntilUnlocked = nested?.optNullableBoolean("hiddenUntilUnlocked")
                ?: nested?.optNullableBoolean("hidden_until_unlocked")
                ?: chart.optNullableBoolean("hidden_until_unlocked")
                ?: chart.optNullableBoolean("hiddenUntilUnlocked"),
            hiddenUntil = nested?.optNullableString("hiddenUntil")
                ?: nested?.optNullableString("hidden_until")
                ?: chart.optNullableString("hidden_until")
                ?: chart.optNullableString("hiddenUntil")
        )
    }

    fun validationErrors(document: ArcaeaDatabaseDocument): List<String> = buildList {
        if (document.songs.isEmpty()) add("Database contains no official song entries")

        document.songs.forEach { song ->
            val duplicateDifficulties = song.charts.groupingBy { it.difficulty }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            duplicateDifficulties.forEach {
                add("${song.id}: duplicate semantic chart $it")
            }

            song.charts.forEach { chart ->
                chart.classification.validationError(chart.difficulty)?.let { reason ->
                    add("${song.id}/${chart.difficulty}: $reason")
                }
            }
        }
    }

    private fun difficultyOrder(value: String): Int = when (value) {
        "PST" -> 0
        "PRS" -> 1
        "FTR" -> 2
        "ETR" -> 3
        "BYD" -> 4
        "INS" -> 5
        else -> 99
    }

    private fun JSONArray.stringList(): List<String> = buildList {
        for (i in 0 until length()) {
            if (!isNull(i)) {
                optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeUnless { it.isNaN() }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Boolean -> value
            is String -> when (value.trim().lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }
}
