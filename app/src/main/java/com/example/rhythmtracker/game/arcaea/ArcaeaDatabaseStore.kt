package com.example.rhythmtracker.game.arcaea

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Stores a validated Arcaea database in private app storage.
 *
 * The old cheeseburger filename is still recognized so existing installs do not suddenly lose
 * their database after the schema-v2 rewrite. New imports are stored under the neutral v2 name.
 */
class ArcaeaDatabaseStore(context: Context) {
    private val databaseFile = File(context.filesDir, FILE_NAME_V2)
    private val legacyFile = File(context.filesDir, FILE_NAME_LEGACY)

    fun exists(): Boolean = activeFile()?.let { it.isFile && it.length() > 0L } == true

    fun load(): ArcaeaChartIndex {
        val file = activeFile() ?: error("No Arcaea database imported")
        return file.inputStream().use { ArcaeaChartIndex.parse(it) }
    }

    @Synchronized
    fun importFrom(input: InputStream): ArcaeaChartIndex {
        val temporary = File(databaseFile.parentFile, "$FILE_NAME_V2.tmp")
        try {
            input.use { source ->
                temporary.outputStream().buffered().use { output -> source.copyTo(output) }
            }

            // Parse and sanity-check before replacing the last known-good database.
            val parsed = temporary.inputStream().use { ArcaeaChartIndex.parse(it) }
            val sanity = parsed.sanityErrors()
            require(sanity.isEmpty()) { sanity.joinToString("; ") }

            if (databaseFile.exists() && !databaseFile.delete()) {
                error("Could not replace previous Arcaea database")
            }
            if (!temporary.renameTo(databaseFile)) {
                temporary.copyTo(databaseFile, overwrite = true)
                temporary.delete()
            }

            // Once a new import succeeds, the old filename is obsolete. Do not keep two possible
            // sources of truth around.
            if (legacyFile.exists() && legacyFile.absolutePath != databaseFile.absolutePath) {
                legacyFile.delete()
            }
            return parsed
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun activeFile(): File? = when {
        databaseFile.isFile && databaseFile.length() > 0L -> databaseFile
        legacyFile.isFile && legacyFile.length() > 0L -> legacyFile
        else -> null
    }

    companion object {
        private const val FILE_NAME_V2 = "arcaea-database.json"
        private const val FILE_NAME_LEGACY = "cheeseburger-merged.json"
    }
}
