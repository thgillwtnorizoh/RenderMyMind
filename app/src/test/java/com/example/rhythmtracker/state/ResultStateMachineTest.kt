package com.example.rhythmtracker.state

import com.example.rhythmtracker.identity.ResultIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultStateMachineTest {
    @Test
    fun noisyScoreOnSamePixelsDoesNotCreateDuplicate() {
        val machine = ResultStateMachine()
        val first = strong(score = 9_876_828, hash = 0x00FF00FF00FF00FFL, title = "blythe")
        val noisy = strong(score = 9_875_828, hash = 0x00FF00FF00FF00FEL, title = "blythe")

        assertTrue(machine.observe(first).captureRequested)
        assertFalse(machine.observe(noisy).captureRequested)
        assertFalse(machine.observe(noisy).captureRequested)
    }

    @Test
    fun directResultSwitchCapturesSecondResultWithoutBlankScreen() {
        val machine = ResultStateMachine()
        val a = strong(score = 9_395_359, hash = 0x0000000000000000L, title = "cataclysmcry")
        val b = strong(score = 9_736_910, hash = 0x7F7F7F7F7F7F7F7FL, title = "dreadarea")

        assertTrue(machine.observe(a).captureRequested)
        assertFalse(machine.observe(b).captureRequested)
        assertTrue(machine.observe(b).captureRequested)
    }

    @Test
    fun sameResultCanBeCapturedAgainAfterRealExit() {
        val machine = ResultStateMachine()
        val result = strong(score = 9_395_359, hash = 0x0F0F0F0F0F0F0F0FL, title = "cataclysmcry")
        val absent = ResultSignal(
            present = false,
            strong = false,
            strength = 0f,
            anchors = emptySet(),
            identity = ResultIdentity(null, null, 0L)
        )

        assertTrue(machine.observe(result).captureRequested)
        repeat(3) { machine.observe(absent) }
        assertTrue(machine.observe(result).captureRequested)
    }

    private fun strong(score: Long, hash: Long, title: String) = ResultSignal(
        present = true,
        strong = true,
        strength = 1f,
        anchors = setOf("TRACK COMPLETE", "SCORE"),
        identity = ResultIdentity(score, title, hash)
    )
}
