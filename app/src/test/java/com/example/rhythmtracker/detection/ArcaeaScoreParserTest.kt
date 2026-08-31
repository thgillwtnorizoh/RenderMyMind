package com.example.rhythmtracker.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArcaeaScoreParserTest {
    private val detector = ArcaeaResultDetector()

    @Test
    fun acceptsEightDigitArcaeaScores() {
        assertEquals(9_815_066L, detector.parseScore("09'815'066"))
        assertEquals(9_876_828L, detector.parseScore("O9'876 828"))
        assertEquals(0L, detector.parseScore("00'000'000"))
        assertEquals(10_000_000L, detector.parseScore("10'000'000"))
    }

    @Test
    fun rejectsDroppedDigitScoresInsteadOfInventingAPlayIdentity() {
        assertNull(detector.parseScore("9986622"))
        assertNull(detector.parseScore("1141111"))
        assertNull(detector.parseScore("986622"))
    }
}
