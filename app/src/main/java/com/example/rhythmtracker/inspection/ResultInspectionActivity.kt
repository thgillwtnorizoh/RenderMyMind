package com.example.rhythmtracker.inspection

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.rhythmtracker.R
import com.example.rhythmtracker.detection.ArcaeaResultDetector
import com.example.rhythmtracker.game.arcaea.ArcaeaChartMarker
import com.example.rhythmtracker.game.arcaea.ArcaeaDatabaseStore
import com.example.rhythmtracker.identity.VisualFingerprint
import com.example.rhythmtracker.parser.ArcaeaJudgementReconciler
import com.example.rhythmtracker.parser.ArcaeaResultParser
import com.example.rhythmtracker.vision.DebugRegion
import com.example.rhythmtracker.vision.MlKitOcrEngine
import com.example.rhythmtracker.vision.VisionStage
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Offline inspection path for screenshots selected by the user.
 *
 * Every image pass is intentionally fresh: the original URI is decoded again, the database is
 * loaded again, and new detector/parser/OCR instances are created. Previous OCR text, parsed
 * fields, candidate choices and confidence values are never fed into a later pass. Imported
 * screenshots are never written to results.jsonl.
 */
class ResultInspectionActivity : Activity() {
    private lateinit var progressText: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var repeatAllButton: Button

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val activeOcrEngines = Collections.synchronizedSet(mutableSetOf<MlKitOcrEngine>())
    private var selectedUris: List<Uri> = emptyList()
    private var batchSerial = 0L
    private var activeBatchGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_inspection)

        progressText = findViewById(R.id.inspectionProgressText)
        resultsContainer = findViewById(R.id.inspectionResultsContainer)
        repeatAllButton = findViewById(R.id.repeatInspectionImagesButton)
        repeatAllButton.isEnabled = false

        findViewById<Button>(R.id.addInspectionImagesButton).setOnClickListener {
            requestImages()
        }
        repeatAllButton.setOnClickListener {
            if (selectedUris.isNotEmpty()) {
                startFreshBatch(selectedUris, clearResults = false)
            }
        }
    }

    override fun onDestroy() {
        synchronized(activeOcrEngines) {
            activeOcrEngines.toList().forEach { runCatching { it.close() } }
            activeOcrEngines.clear()
        }
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun requestImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_IMAGES)
    }

    @Deprecated("Kept dependency-free to match the rest of the alpha UI.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGES || resultCode != RESULT_OK || data == null) return

        val uris = if (data.clipData != null) {
            buildList {
                val clip = data.clipData!!
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        } else {
            data.data?.let(::listOf).orEmpty()
        }
        if (uris.isEmpty()) return

        selectedUris = uris
        repeatAllButton.isEnabled = true
        startFreshBatch(uris, clearResults = true)
    }

    private fun startFreshBatch(uris: List<Uri>, clearResults: Boolean) {
        if (uris.isEmpty()) return
        batchSerial += 1
        activeBatchGeneration += 1
        val generation = activeBatchGeneration
        val batchId = batchSerial

        if (clearResults) resultsContainer.removeAllViews()
        progressText.text = "Fresh batch $batchId queued: ${uris.size} image(s). No prior inspection data will be reused."
        inspectNext(
            uris = uris,
            index = 0,
            successes = 0,
            generation = generation,
            batchId = batchId
        )
    }

    private fun inspectNext(
        uris: List<Uri>,
        index: Int,
        successes: Int,
        generation: Long,
        batchId: Long
    ) {
        if (generation != activeBatchGeneration || isFinishing || isDestroyed) return
        if (index >= uris.size) {
            runOnUiThread {
                if (generation == activeBatchGeneration) {
                    progressText.text = "Fresh batch $batchId finished: $successes/${uris.size} image(s) analyzed."
                }
            }
            return
        }

        val uri = uris[index]
        runOnUiThread {
            if (generation == activeBatchGeneration) {
                progressText.text = "Fresh batch $batchId: inspecting ${index + 1}/${uris.size}…"
            }
        }

        analysisExecutor.execute {
            if (generation != activeBatchGeneration) return@execute

            val decoded = runCatching { decodeImage(uri) }
            decoded.onFailure { error ->
                runOnUiThread {
                    if (generation == activeBatchGeneration) addErrorCard(displayName(uri), error)
                }
                inspectNext(uris, index + 1, successes, generation, batchId)
                return@execute
            }

            val bitmap = decoded.getOrThrow()
            val name = displayName(uri)
            val width = bitmap.width
            val height = bitmap.height
            val fingerprint = VisualFingerprint.from(bitmap)
            val preview = makePreview(bitmap)

            // Deliberately reload the DB and recreate every interpretation component for this pass.
            val indexSnapshot = runCatching { ArcaeaDatabaseStore(this).load() }.getOrNull()
            val detector = ArcaeaResultDetector()
            val parser = ArcaeaResultParser(detector)
            val freshOcr = MlKitOcrEngine()
            activeOcrEngines += freshOcr

            val started = runCatching {
                freshOcr.recognizeNative(bitmap, analysisExecutor) { ocrResult ->
                    val inspection = ocrResult.map { lines ->
                        val detection = detector.detect(lines, fingerprint, VisionStage.NATIVE)
                        val parsed = parser.parse(lines)
                        val resolution = parsed.title?.let { title ->
                            indexSnapshot?.resolveResultTitle(
                                rawTitle = title,
                                displayedDifficulty = parsed.displayedDifficulty,
                                hiddenOnScreen = parsed.chartHiddenOnScreen
                            )
                        }
                        val resolvedChart = resolution?.chart
                        val judgements = ArcaeaJudgementReconciler.reconcile(
                            lines = lines,
                            initialPure = parsed.pure,
                            initialFar = parsed.far,
                            initialLost = parsed.lost,
                            noteCount = resolvedChart?.notes
                        )
                        val regions = (detection.regions + parsed.regions)
                            .distinctBy { regionKey(it) }

                        Inspection(
                            sourceUri = uri,
                            batchId = batchId,
                            name = name,
                            width = width,
                            height = height,
                            preview = preview,
                            regions = regions,
                            detected = detection.signal.present,
                            strong = detection.signal.strong,
                            strength = detection.signal.strength,
                            anchors = detection.signal.anchors.toList(),
                            title = parsed.title,
                            artist = parsed.artist,
                            trackState = parsed.trackState,
                            score = parsed.score,
                            pure = judgements.pure,
                            far = judgements.far,
                            lost = judgements.lost,
                            judgementBasis = judgements.basisDescription(),
                            judgementChecksum = judgements.checksumDescription(),
                            displayedDifficulty = parsed.displayedDifficulty,
                            displayedDifficultyLabel = parsed.displayedDifficultyLabel,
                            displayedLevel = parsed.displayedLevel,
                            chartHiddenOnScreen = parsed.chartHiddenOnScreen,
                            confidence = judgements.adjustConfidence(parsed.confidence),
                            databaseSchema = indexSnapshot?.let {
                                "${it.databaseFormat}/v${it.schemaVersion}"
                            },
                            songId = resolution?.song?.id,
                            resolutionBasis = resolution?.matchKind,
                            resolvedDifficulty = resolvedChart?.difficulty,
                            resolvedLevel = resolvedChart?.level,
                            resolvedNotes = resolvedChart?.notes,
                            resolvedClassification = resolvedChart?.classificationDescription(),
                            databaseVisibility = when {
                                resolvedChart != null -> resolvedChart.visibilityDescription()
                                parsed.chartHiddenOnScreen && resolution?.song != null ->
                                    "unresolved hidden chart"
                                else -> "-"
                            },
                            rawOcr = parsed.rawText
                        )
                    }

                    activeOcrEngines.remove(freshOcr)
                    runCatching { freshOcr.close() }

                    runOnUiThread {
                        if (generation != activeBatchGeneration) {
                            preview.recycle()
                            return@runOnUiThread
                        }
                        inspection.onSuccess(::addInspectionCard)
                            .onFailure { error ->
                                preview.recycle()
                                addErrorCard(name, error)
                            }
                    }
                    inspectNext(
                        uris,
                        index + 1,
                        successes + if (inspection.isSuccess) 1 else 0,
                        generation,
                        batchId
                    )
                }
            }

            bitmap.recycle()
            started.onFailure { error ->
                activeOcrEngines.remove(freshOcr)
                runCatching { freshOcr.close() }
                preview.recycle()
                runOnUiThread {
                    if (generation == activeBatchGeneration) addErrorCard(name, error)
                }
                inspectNext(uris, index + 1, successes, generation, batchId)
            }
        }
    }

    private fun decodeImage(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }

    private fun makePreview(source: Bitmap): Bitmap {
        val maxWidth = 760f
        val maxHeight = 430f
        val scale = min(1f, min(maxWidth / source.width, maxHeight / source.height))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return if (width == source.width && height == source.height) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "image"
    }

    private fun addInspectionCard(value: Inspection) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(26, 28, 33))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(65, 69, 78))
            }
        }

        card.addView(TextView(this).apply {
            text = "${value.name}  [fresh batch ${value.batchId}]"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val imageView = ResultInspectionImageView(this).apply {
            setInspectionBitmap(value.preview, value.regions)
        }
        card.addView(
            imageView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )

        card.addView(TextView(this).apply {
            text = inspectionText(value)
            setTextColor(Color.rgb(221, 225, 232))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, 0)
        })

        card.addView(Button(this).apply {
            text = "Inspect this image again (fresh)"
            setOnClickListener {
                startFreshBatch(listOf(value.sourceUri), clearResults = false)
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        resultsContainer.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        )
    }

    private fun addErrorCard(name: String, error: Throwable) {
        resultsContainer.addView(TextView(this).apply {
            text = "$name\nERROR: ${error.message ?: error.javaClass.simpleName}"
            setTextColor(Color.rgb(255, 190, 190))
            textSize = 13f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(48, 25, 28))
                cornerRadius = dp(10).toFloat()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })
    }

    private fun inspectionText(value: Inspection): String = buildString {
        appendLine("image             : ${value.width}x${value.height}")
        appendLine("inspection mode   : FRESH (no previous OCR/parse state reused)")
        appendLine("result detected   : ${value.detected}")
        appendLine("strong evidence   : ${value.strong}")
        appendLine("detector strength : ${"%.2f".format(value.strength)}")
        appendLine("anchors           : ${value.anchors.joinToString().ifBlank { "-" }}")
        appendLine("title             : ${value.title ?: "-"}")
        appendLine("artist            : ${value.artist ?: "-"}")
        appendLine("track state       : ${value.trackState ?: "-"}")
        appendLine("score             : ${value.score?.let(::formatScore) ?: "-"}")
        appendLine("PURE / FAR / LOST : ${value.pure ?: "-"} / ${value.far ?: "-"} / ${value.lost ?: "-"}")
        appendLine("judgement basis   : ${value.judgementBasis}")
        appendLine("judgement checksum: ${value.judgementChecksum}")
        appendLine("displayed chart   : ${displayedChartText(value)}")
        appendLine("screen chart state: ${ArcaeaChartMarker.visibilityLabel(value.chartHiddenOnScreen)}")
        appendLine("parse confidence  : ${"%.2f".format(value.confidence)}")
        appendLine("database schema   : ${value.databaseSchema ?: "-"}")
        appendLine("database match    : ${value.songId ?: "-"}")
        appendLine("resolution basis  : ${value.resolutionBasis ?: "-"}")
        appendLine("resolved chart    : ${resolvedChartText(value)}")
        appendLine("database notes    : ${value.resolvedNotes ?: "-"}")
        appendLine("chart class       : ${value.resolvedClassification ?: "-"}")
        appendLine("database visibility: ${value.databaseVisibility}")
        appendLine()
        append("raw OCR           : ${value.rawOcr.ifBlank { "-" }}")
    }

    private fun displayedChartText(value: Inspection): String = when {
        value.chartHiddenOnScreen -> "${value.displayedDifficultyLabel ?: "???"} / ${value.displayedLevel ?: "?"}"
        value.displayedDifficulty != null -> buildString {
            append(value.displayedDifficultyLabel ?: value.displayedDifficulty)
            append(" (")
            append(value.displayedDifficulty)
            append(')')
            value.displayedLevel?.let { append(" / $it") }
        }
        else -> "-"
    }

    private fun resolvedChartText(value: Inspection): String = when {
        value.resolvedDifficulty == null -> "-"
        value.resolvedLevel != null -> "${value.resolvedDifficulty} / ${value.resolvedLevel}"
        else -> value.resolvedDifficulty
    }

    private fun formatScore(value: Long): String {
        val digits = value.toString().padStart(8, '0')
        return "${digits.substring(0, 2)}'${digits.substring(2, 5)}'${digits.substring(5)}"
    }

    private fun regionKey(region: DebugRegion): String = buildString {
        append(region.key)
        append(':')
        append("%.3f".format(region.bounds.left))
        append(':')
        append("%.3f".format(region.bounds.top))
        append(':')
        append("%.3f".format(region.bounds.right))
        append(':')
        append("%.3f".format(region.bounds.bottom))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Inspection(
        val sourceUri: Uri,
        val batchId: Long,
        val name: String,
        val width: Int,
        val height: Int,
        val preview: Bitmap,
        val regions: List<DebugRegion>,
        val detected: Boolean,
        val strong: Boolean,
        val strength: Float,
        val anchors: List<String>,
        val title: String?,
        val artist: String?,
        val trackState: String?,
        val score: Long?,
        val pure: Int?,
        val far: Int?,
        val lost: Int?,
        val judgementBasis: String,
        val judgementChecksum: String,
        val displayedDifficulty: String?,
        val displayedDifficultyLabel: String?,
        val displayedLevel: String?,
        val chartHiddenOnScreen: Boolean,
        val confidence: Float,
        val databaseSchema: String?,
        val songId: String?,
        val resolutionBasis: String?,
        val resolvedDifficulty: String?,
        val resolvedLevel: String?,
        val resolvedNotes: Int?,
        val resolvedClassification: String?,
        val databaseVisibility: String,
        val rawOcr: String
    )

    companion object {
        private const val REQUEST_IMAGES = 5201
    }
}
