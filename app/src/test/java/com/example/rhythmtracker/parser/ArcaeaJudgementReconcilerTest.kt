package com.example.rhythmtracker.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcaeaJudgementReconcilerTest {
    @Test
    fun validatesExactJudgementTripletAgainstDatabaseNotes() {
        val result = ArcaeaJudgementReconciler.reconcile(
            lines = emptyList(),
            initialPure = 1198,
            initialFar = 2,
            initialLost = 0,
            noteCount = 1200
        )

        assertEquals(1198, result.pure)
        assertEquals(2, result.far)
        assertEquals(0, result.lost)
        assertTrue(result.checksumMatched)
        assertFalse(result.usedDerivation)
        assertEquals("1200/1200 OK", result.checksumDescription())
    }

    @Test
    fun repairsOneImpossibleFieldFromTheOtherTwoAndNoteCount() {
        val result = ArcaeaJudgementReconciler.reconcile(
            lines = emptyList(),
            initialPure = 1198,
            initialFar = 24504,
            initialLost = 0,
            noteCount = 1200
        )

        assertEquals(1198, result.pure)
        assertEquals(2, result.far)
        assertEquals(0, result.lost)
        assertTrue(result.checksumMatched)
        assertTrue(result.usedDerivation)
        assertEquals("derived-note-count", result.farBasis)
    }

    @Test
    fun missingZeroCanBeDerivedInsteadOfTreatedAsAbsent() {
        val result = ArcaeaJudgementReconciler.reconcile(
            lines = emptyList(),
            initialPure = 830,
            initialFar = 0,
            initialLost = null,
            noteCount = 830
        )

        assertEquals(830, result.pure)
        assertEquals(0, result.far)
        assertEquals(0, result.lost)
        assertTrue(result.checksumMatched)
        assertEquals("derived-note-count", result.lostBasis)
    }

    @Test
    fun oneKnownFieldIsNotEnoughToInventTheOtherTwo() {
        val result = ArcaeaJudgementReconciler.reconcile(
            lines = emptyList(),
            initialPure = null,
            initialFar = null,
            initialLost = 44,
            noteCount = 2032
        )

        assertNull(result.pure)
        assertNull(result.far)
        assertEquals(44, result.lost)
        assertFalse(result.checksumMatched)
        assertNull(result.checksumSum)
    }

    @Test
    fun noDatabaseNotesLeavesParserValuesUntouched() {
        val result = ArcaeaJudgementReconciler.reconcile(
            lines = emptyList(),
            initialPure = null,
            initialFar = 31010,
            initialLost = null,
            noteCount = null
        )

        assertNull(result.pure)
        assertEquals(31010, result.far)
        assertNull(result.lost)
        assertEquals("not available", result.checksumDescription())
    }
}
