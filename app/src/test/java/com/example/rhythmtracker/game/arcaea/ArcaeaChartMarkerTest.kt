package com.example.rhythmtracker.game.arcaea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcaeaChartMarkerTest {
    @Test
    fun parsesNormalDifficultyNames() {
        assertEquals("FTR", ArcaeaChartMarker.parseDifficulty("FUTURE")?.difficulty)
        assertEquals("BYD", ArcaeaChartMarker.parseDifficulty("Beyond")?.difficulty)
        assertEquals("ETR", ArcaeaChartMarker.parseDifficulty("ETERNAL")?.difficulty)
    }

    @Test
    fun inscribedHasItsOwnScreenIdentity() {
        val marker = ArcaeaChartMarker.parseDifficulty("INSCRIBED")
        assertEquals("INS", marker?.difficulty)
        assertEquals("INSCRIBED", marker?.displayName)
    }

    @Test
    fun questionMarksAreAValidHiddenMarker() {
        assertTrue(ArcaeaChartMarker.isHiddenDifficulty("???"))
        assertTrue(ArcaeaChartMarker.isHiddenDifficulty("？？？"))
        assertFalse(ArcaeaChartMarker.isHiddenDifficulty("?"))
        assertEquals("?", ArcaeaChartMarker.parseLevel("?"))
    }

    @Test
    fun parsesOnlyPlausibleArcaeaLevels() {
        assertEquals("10", ArcaeaChartMarker.parseLevel("10"))
        assertEquals("11+", ArcaeaChartMarker.parseLevel("11+"))
        assertNull(ArcaeaChartMarker.parseLevel("100"))
        assertNull(ArcaeaChartMarker.parseLevel("MAX RECALL 314"))
    }

    @Test
    fun chartVisibilityMetadataCanRemainUnknown() {
        val unknown = chart(hiddenUntilUnlocked = null, hiddenUntil = null)
        val hidden = chart(hiddenUntilUnlocked = true, hiddenUntil = null)
        val conditional = chart(hiddenUntilUnlocked = null, hiddenUntil = "difficulty")
        val visible = chart(hiddenUntilUnlocked = false, hiddenUntil = null)

        assertNull(unknown.hiddenBeforeUnlock())
        assertEquals(true, hidden.hiddenBeforeUnlock())
        assertEquals(true, conditional.hiddenBeforeUnlock())
        assertEquals(false, visible.hiddenBeforeUnlock())
    }

    private fun chart(
        hiddenUntilUnlocked: Boolean?,
        hiddenUntil: String?
    ) = ArcaeaChartDefinition(
        difficulty = "INS",
        level = "11",
        constant = 11.4,
        notes = 1663,
        variantTitle = null,
        variantAliases = emptyList(),
        classification = ArcaeaChartClassification.fromSemantic("INS"),
        visibility = ArcaeaChartVisibility(
            hiddenUntilUnlocked = hiddenUntilUnlocked,
            hiddenUntil = hiddenUntil
        )
    )
}
