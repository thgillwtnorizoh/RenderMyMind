package com.example.rhythmtracker.game.arcaea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcaeaChartClassificationTest {
    @Test
    fun beyondAndInscribedShareRatingClassButNotBydType() {
        val beyond = ArcaeaChartClassification.fromSemantic("BYD")
        val inscribed = ArcaeaChartClassification.fromSemantic("INS")

        assertEquals(3, beyond.ratingClass)
        assertEquals(3, inscribed.ratingClass)
        assertEquals(0, beyond.bydType)
        assertEquals(1, inscribed.bydType)
        assertNull(beyond.ratingClassAlias)
        assertEquals(1, inscribed.ratingClassAlias)
        assertTrue(inscribed.isInscribed())
        assertFalse(beyond.isInscribed())
    }

    @Test
    fun songlistAliasOneMeansInscribed() {
        val value = ArcaeaChartClassification.fromSonglist(3, 1)
        assertEquals("INS", value.semanticDifficulty())
        assertEquals(1, value.bydType)
    }

    @Test
    fun ratingClassThreeWithoutAliasMeansBeyond() {
        val value = ArcaeaChartClassification.fromSonglist(3, null)
        assertEquals("BYD", value.semanticDifficulty())
        assertEquals(0, value.bydType)
    }

    @Test
    fun semanticMappingKeepsNormalClassesSimple() {
        assertEquals(0, ArcaeaChartClassification.fromSemantic("PST").ratingClass)
        assertEquals(1, ArcaeaChartClassification.fromSemantic("PRS").ratingClass)
        assertEquals(2, ArcaeaChartClassification.fromSemantic("FTR").ratingClass)
        assertEquals(4, ArcaeaChartClassification.fromSemantic("ETR").ratingClass)
    }
}
