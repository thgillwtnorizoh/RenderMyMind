package com.example.rhythmtracker.game.arcaea

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

/**
 * Read-only tracker view of a cheeseburger merged Arcaea database.
 *
 * Only entries with an official song id participate in automatic result resolution. Wiki-only
 * special/event pages may remain in the source file without becoming ambiguous tracker targets.
 */
class ArcaeaChartIndex private constructor(
    val songs: List<ArcaeaSongDefinition>,
    val sourceUpdatedAt: String?
) {
    private data class TitleTarget(
        val song: ArcaeaSongDefinition,
        val chartHint: ArcaeaChartDefinition?
    )

    private val byId = songs.associateBy { it.id.lowercase(Locale.ROOT) }
    private val exactTitles = linkedMapOf<String, MutableList<TitleTarget>>()
    private val looseTitles = linkedMapOf<String, MutableList<TitleTarget>>()

    val songCount: Int get() = songs.size
    val chartCount: Int get() = songs.sumOf { it.charts.size }
    val knownConstantCount: Int get() = songs.sumOf { song -> song.charts.count { it.constant != null } }

    init {
        songs.forEach { song ->
            addTitle(song.title, TitleTarget(song, null))
            song.aliases.forEach { addTitle(it, TitleTarget(song, null)) }
            song.charts.forEach { chart ->
                chart.variantTitle?.let { addTitle(it, TitleTarget(song, chart)) }
                chart.variantAliases.forEach { addTitle(it, TitleTarget(song, chart)) }
            }
        }
    }

    fun findById(songId: String): ArcaeaSongDefinition? =
        byId[songId.trim().lowercase(Locale.ROOT)]

    /**
     * Resolve OCR title text without guessing through collisions.
     *
     * Exact NFKC/case/whitespace matching is preferred. A punctuation-insensitive key is a
     * fallback for OCR that drops decoration. If more than one identity remains, null is
     * returned rather than silently choosing the wrong song.
     *
     * Chart-scoped alternate titles can infer their own difficulty. For example,
     * "Axium Divergence" resolves directly to Axium Crisis BYD.
     */
    fun resolveTitle(rawTitle: String, difficultyHint: String? = null): ArcaeaTitleResolution? {
        val exactKey = normalizeExact(rawTitle)
        if (exactKey.isEmpty()) return null

        val exact = exactTitles[exactKey].orEmpty()
        val targets = if (exact.isNotEmpty()) {
            exact
        } else {
            val looseKey = normalizeLoose(rawTitle)
            if (looseKey.isEmpty()) emptyList() else looseTitles[looseKey].orEmpty()
        }
        if (targets.isEmpty()) return null

        val requestedDifficulty = normalizeDifficulty(difficultyHint)
        val candidates = targets.mapNotNull { target ->
            val chart = when {
                target.chartHint != null && requestedDifficulty == null -> target.chartHint
                target.chartHint != null && target.chartHint.difficulty == requestedDifficulty -> target.chartHint
                target.chartHint != null -> null
                requestedDifficulty != null -> target.song.charts.singleOrNull {
                    it.difficulty == requestedDifficulty
                }
                else -> null
            }

            if (requestedDifficulty != null && chart == null) return@mapNotNull null

            ArcaeaTitleResolution(
                song = target.song,
                chart = chart,
                matchKind = if (exact.isNotEmpty()) "exact" else "loose",
                confidence = if (exact.isNotEmpty()) 1.0f else 0.88f
            )
        }.distinctBy { resolution ->
            resolution.song.id + ":" + (resolution.chart?.difficulty ?: "*")
        }

        return candidates.singleOrNull()
    }

    /**
     * Resolve the chart marker seen on a result screen.
     *
     * A visible difficulty such as FUTURE or INSCRIBED is authoritative. When the screen itself
     * deliberately shows `?` / `???`, the database may identify the chart only when exactly one
     * chart for that song is marked hidden-until-unlocked. If the imported database does not carry
     * that songlist metadata, the song still resolves but the chart remains unknown rather than
     * being guessed.
     */
    fun resolveResultTitle(
        rawTitle: String,
        displayedDifficulty: String?,
        hiddenOnScreen: Boolean
    ): ArcaeaTitleResolution? {
        if (displayedDifficulty != null) {
            return resolveTitle(rawTitle, displayedDifficulty)
        }

        val base = resolveTitle(rawTitle) ?: return null
        if (!hiddenOnScreen || base.chart != null) return base

        val hiddenCharts = base.song.charts.filter { it.hiddenBeforeUnlock() == true }
        val chart = hiddenCharts.singleOrNull() ?: return base
        return base.copy(
            chart = chart,
            matchKind = base.matchKind + "+hidden",
            confidence = minOf(base.confidence, 0.98f)
        )
    }

    /** Packaging/data sanity checks. These do not claim that OCR itself is tuned yet. */
    fun sanityErrors(): List<String> = buildList {
        if (songCount < 500) add("Official song count unexpectedly low: $songCount")
        if (findById("deinosphainein")?.charts?.none { it.difficulty == "INS" } != false) {
            add("DEINOS PHAINEIN INS is missing")
        }
        val axium = resolveTitle("Axium Divergence", "BYD")
        if (axium?.song?.id != "axiumcrisis" || axium.chart?.difficulty != "BYD") {
            add("Axium Divergence BYD identity did not resolve")
        }
        if (resolveTitle("光")?.song?.id != "hikari") {
            add("Localized Hikari title did not resolve")
        }
    }

    private fun addTitle(raw: String?, target: TitleTarget) {
        if (raw.isNullOrBlank()) return
        val exact = normalizeExact(raw)
        if (exact.isNotEmpty()) exactTitles.getOrPut(exact) { mutableListOf() }.add(target)

        val loose = normalizeLoose(raw)
        if (loose.length >= 2) looseTitles.getOrPut(loose) { mutableListOf() }.add(target)
    }

    companion object {
        fun parse(input: InputStream): ArcaeaChartIndex {
            val root = input.bufferedReader().use { JSONObject(it.readText()) }
            require(root.optString("format") == "arcaea_wiki_entries") {
                "Not a cheeseburger merged Arcaea database"
            }

            val entries = root.getJSONArray("entries")
            val songs = ArrayList<ArcaeaSongDefinition>(entries.length())

            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val song = entry.optJSONObject("song") ?: continue
                val songId = song.optNullableString("id") ?: continue
                val title = song.optNullableString("title") ?: continue
                val aliases = song.optJSONArray("title_aliases")?.stringList().orEmpty()
                val chartsObject = entry.optJSONObject("charts") ?: JSONObject()
                val charts = ArrayList<ArcaeaChartDefinition>(6)

                val keys = chartsObject.keys()
                while (keys.hasNext()) {
                    val difficultyKey = keys.next()
                    val chart = chartsObject.optJSONObject(difficultyKey) ?: continue
                    val level = chart.optNullableString("level")
                    val constant = chart.optNullableDouble("constant")
                    val notes = chart.optNullableInt("notes")
                    if (level == null && constant == null && notes == null) continue

                    charts += ArcaeaChartDefinition(
                        difficulty = normalizeDifficulty(difficultyKey) ?: difficultyKey.uppercase(Locale.ROOT),
                        level = level,
                        constant = constant,
                        notes = notes,
                        variantTitle = chart.optNullableString("variant_title"),
                        variantAliases = chart.optJSONArray("variant_title_aliases")?.stringList().orEmpty(),
                        hiddenUntilUnlocked = chart.optNullableBoolean("hidden_until_unlocked")
                            ?: chart.optNullableBoolean("hiddenUntilUnlocked"),
                        hiddenUntil = chart.optNullableString("hidden_until")
                            ?: chart.optNullableString("hiddenUntil")
                    )
                }

                songs += ArcaeaSongDefinition(
                    id = songId,
                    title = title,
                    aliases = aliases,
                    charts = charts.sortedBy { difficultyOrder(it.difficulty) }
                )
            }

            return ArcaeaChartIndex(
                songs = songs,
                sourceUpdatedAt = root.optNullableString("updated_at")
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

        private fun difficultyOrder(value: String): Int = when (value) {
            "PST" -> 0
            "PRS" -> 1
            "FTR" -> 2
            "ETR" -> 3
            "BYD" -> 4
            "INS" -> 5
            else -> 99
        }

        private fun normalizeExact(raw: String): String = Normalizer
            .normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun normalizeLoose(raw: String): String = normalizeExact(raw)
            .filter { it.isLetterOrDigit() }

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
            return optInt(key)
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
}

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
    val hiddenUntilUnlocked: Boolean?,
    val hiddenUntil: String?
) {
    /** null means the imported database does not contain songlist visibility metadata. */
    fun hiddenBeforeUnlock(): Boolean? {
        hiddenUntilUnlocked?.let { return it }
        val condition = hiddenUntil?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (condition) {
            "none" -> false
            "always", "difficulty", "song", "unlockconditions" -> true
            else -> null
        }
    }

    fun visibilityDescription(): String = when {
        hiddenUntilUnlocked == true -> "hidden_until_unlocked"
        hiddenUntilUnlocked == false -> "visible"
        !hiddenUntil.isNullOrBlank() -> "hidden_until=${hiddenUntil}"
        else -> "metadata unavailable"
    }
}

data class ArcaeaTitleResolution(
    val song: ArcaeaSongDefinition,
    val chart: ArcaeaChartDefinition?,
    val matchKind: String,
    val confidence: Float
)
