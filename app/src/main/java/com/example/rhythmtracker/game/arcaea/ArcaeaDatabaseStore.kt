package com.example.rhythmtracker.game.arcaea

import android.content.Context
import java.io.File
import java.io.InputStream

/** Stores a validated copy of the user-selected merged database in private app storage. */
class ArcaeaDatabaseStore(context: Context) {
    private val databaseFile = File(context.filesDir, FILE_NAME)

    fun exists(): Boolean = databaseFile.isFile && databaseFile.length() > 0L

    fun load(): ArcaeaChartIndex = databaseFile.inputStream().use { ArcaeaChartIndex.parse(it) }

    @Synchronized
    fun importFrom(input: InputStream): ArcaeaChartIndex {
        val temporary = File(databaseFile.parentFile, "$FILE_NAME.tmp")
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
            return parsed
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        private const val FILE_NAME = "cheeseburger-merged.json"
    }
}
