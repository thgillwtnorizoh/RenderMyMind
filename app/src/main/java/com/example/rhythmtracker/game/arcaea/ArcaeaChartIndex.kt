package com.example.rhythmtracker.game.arcaea

import java.text.Normalizer
import java.util.Locale

/**
 * Read-only semantic index over an ArcaeaDatabaseDocument.
 *
 * Parsing and schema migration live in ArcaeaDatabaseCodec. This class only indexes song/chart
 * identity and resolves OCR evidence against already-normalized database semantics.
 */
class ArcaeaChartIndex private constructor(
    val document: ArcaeaDatabaseDocument
) {
    private data class TitleTarget(
        val song: ArcaeaSongDefinition,
        val chartHint: ArcaeaChartDefinition?
    )

    val schemaVersion: Int get() = document.schemaVersion
    val databaseFormat: String get() = document.format
    val sourceUpdatedAt: String? get() = document.sourceUpdatedAt
    val songs: List<ArcaeaSongDefinition> get() = document.songs

    private val byId = songs.associateBy { it.id.lowercase(Locale.ROOT) }
    private val exactTitles = linkedMapOf<String, MutableList<TitleTarget>>()
    private val looseTitles = linkedMapOf<String, MutableList<TitleTarget>>()

    val songCount: Int get() = songs.size
    val chartCount: Int get() = songs.sumOf { it.charts.size }
    val knownConstantCount: Int get() = songs.sumOf { song -> song.charts.count { it.constant != null } }
    val explicitClassificationCount: Int get() = songs.sumOf { song ->
        song.charts.count { !it.classification.source.contains("semantic") }
    }
    val inscribedChartCount: Int get() = songs.sumOf { song ->
        song.charts.count { it.classification.isInscribed() }
    }

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
     * fallback for OCR that drops decoration. If more than one identity remains, null is returned.
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

        val requestedDifficulty = ArcaeaChartClassification.normalizeDifficulty(difficultyHint)
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
     * Visible PST/PRS/FTR/BYD/ETR/INS text is authoritative. For `?` / `???`, resolution is
     * deliberately conservative:
     *   1. a single chart explicitly marked hidden-until-unlock wins;
     *   2. otherwise a single Inscribed classification (ratingClass 3 + byd_type 1) can identify
     *      the hidden chart;
     *   3. otherwise the song resolves but the chart remains unknown.
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

        val visibilityCandidates = base.song.charts.filter { it.hiddenBeforeUnlock() == true }
        visibilityCandidates.singleOrNull()?.let { chart ->
            return base.copy(
                chart = chart,
                matchKind = base.matchKind + "+hidden-visibility",
                confidence = minOf(base.confidence, 0.99f)
            )
        }

        val inscribedCandidates = base.song.charts.filter { it.classification.isInscribed() }
        inscribedCandidates.singleOrNull()?.let { chart ->
            return base.copy(
                chart = chart,
                matchKind = base.matchKind + "+hidden-inscribed-class",
                confidence = minOf(base.confidence, 0.97f)
            )
        }

        return base
    }

    /** Packaging/data sanity checks. These do not claim that OCR itself is tuned yet. */
    fun sanityErrors(): List<String> = buildList {
        addAll(ArcaeaDatabaseCodec.validationErrors(document))
        if (songCount < 500) add("Official song count unexpectedly low: $songCount")

        val deinos = findById("deinosphainein")
        val deinosIns = deinos?.charts?.singleOrNull { it.difficulty == "INS" }
        if (deinosIns == null) {
            add("DEINOS PHAINEIN INS is missing")
        } else if (!deinosIns.classification.isInscribed()) {
            add("DEINOS PHAINEIN INS classification is inconsistent")
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

    private fun normalizeExact(raw: String): String = Normalizer
        .normalize(raw, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeLoose(raw: String): String = normalizeExact(raw)
        .filter { it.isLetterOrDigit() }

    companion object {
        fun parse(input: java.io.InputStream): ArcaeaChartIndex =
            fromDocument(ArcaeaDatabaseCodec.parse(input))

        fun fromDocument(document: ArcaeaDatabaseDocument): ArcaeaChartIndex =
            ArcaeaChartIndex(document)

        fun normalizeDifficulty(raw: String?): String? =
            ArcaeaChartClassification.normalizeDifficulty(raw)
    }
}
