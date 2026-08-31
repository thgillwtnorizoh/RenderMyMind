package com.example.rhythmtracker.state

import com.example.rhythmtracker.identity.IdentityRelation
import com.example.rhythmtracker.identity.ResultIdentity
import com.example.rhythmtracker.identity.ResultIdentityMatcher

/**
 * Owns result lifecycle and nothing else. No fake misses, no OCR callbacks hidden inside it.
 *
 * A strong first result can capture immediately. Weak candidates require two hits. Once live,
 * three real absent probes are required to exit, while a direct result-to-result change requires
 * two consistent DIFFERENT observations. UNKNOWN never manufactures a new play.
 */
class ResultStateMachine {
    private sealed interface State {
        data object Idle : State
        data class Candidate(val identity: ResultIdentity, val hits: Int) : State
        data class Live(
            val identity: ResultIdentity,
            val absentHits: Int = 0,
            val switchCandidate: ResultIdentity? = null,
            val switchHits: Int = 0
        ) : State
    }

    private var state: State = State.Idle

    data class Update(
        val captureRequested: Boolean,
        val resultVisible: Boolean,
        val stateLabel: String,
        val acceptedIdentity: ResultIdentity?
    )

    fun observe(signal: ResultSignal): Update {
        state = when (val current = state) {
            State.Idle -> onIdle(signal)
            is State.Candidate -> onCandidate(current, signal)
            is State.Live -> onLive(current, signal)
        }
        return snapshot(captureRequested = captureRequestedOnLastTransition)
    }

    private var captureRequestedOnLastTransition = false

    private fun onIdle(signal: ResultSignal): State {
        captureRequestedOnLastTransition = false
        if (!signal.present) return State.Idle
        if (signal.strong) {
            captureRequestedOnLastTransition = true
            return State.Live(signal.identity)
        }
        return State.Candidate(signal.identity, hits = 1)
    }

    private fun onCandidate(current: State.Candidate, signal: ResultSignal): State {
        captureRequestedOnLastTransition = false
        if (!signal.present) return State.Idle

        return when (ResultIdentityMatcher.compare(current.identity, signal.identity)) {
            IdentityRelation.DIFFERENT -> {
                if (signal.strong) {
                    captureRequestedOnLastTransition = true
                    State.Live(signal.identity)
                } else {
                    State.Candidate(signal.identity, hits = 1)
                }
            }

            IdentityRelation.SAME, IdentityRelation.UNKNOWN -> {
                val merged = ResultIdentityMatcher.merge(current.identity, signal.identity)
                val hits = current.hits + 1
                if (signal.strong || hits >= CANDIDATE_HITS_REQUIRED) {
                    captureRequestedOnLastTransition = true
                    State.Live(merged)
                } else {
                    State.Candidate(merged, hits)
                }
            }
        }
    }

    private fun onLive(current: State.Live, signal: ResultSignal): State {
        captureRequestedOnLastTransition = false

        if (!signal.present) {
            val misses = current.absentHits + 1
            return if (misses >= EXIT_MISSES_REQUIRED) {
                State.Idle
            } else {
                current.copy(absentHits = misses, switchCandidate = null, switchHits = 0)
            }
        }

        return when (ResultIdentityMatcher.compare(current.identity, signal.identity)) {
            IdentityRelation.SAME, IdentityRelation.UNKNOWN -> {
                current.copy(
                    identity = ResultIdentityMatcher.merge(current.identity, signal.identity),
                    absentHits = 0,
                    switchCandidate = null,
                    switchHits = 0
                )
            }

            IdentityRelation.DIFFERENT -> {
                val existingCandidate = current.switchCandidate
                val candidateRelation = existingCandidate?.let {
                    ResultIdentityMatcher.compare(it, signal.identity)
                }

                val nextHits = if (candidateRelation == IdentityRelation.SAME ||
                    candidateRelation == IdentityRelation.UNKNOWN
                ) {
                    current.switchHits + 1
                } else {
                    1
                }

                val nextCandidate = if (candidateRelation == IdentityRelation.SAME ||
                    candidateRelation == IdentityRelation.UNKNOWN
                ) {
                    ResultIdentityMatcher.merge(existingCandidate!!, signal.identity)
                } else {
                    signal.identity
                }

                if (nextHits >= SWITCH_HITS_REQUIRED) {
                    captureRequestedOnLastTransition = true
                    State.Live(nextCandidate)
                } else {
                    current.copy(
                        absentHits = 0,
                        switchCandidate = nextCandidate,
                        switchHits = nextHits
                    )
                }
            }
        }
    }

    fun recommendedProbeDelayMs(): Long = when (state) {
        State.Idle -> IDLE_PROBE_MS
        is State.Candidate -> ACTIVE_PROBE_MS
        is State.Live -> ACTIVE_PROBE_MS
    }

    fun reset() {
        state = State.Idle
        captureRequestedOnLastTransition = false
    }

    private fun snapshot(captureRequested: Boolean): Update = when (val current = state) {
        State.Idle -> Update(false, false, "IDLE", null)
        is State.Candidate -> Update(
            captureRequested = captureRequested,
            resultVisible = false,
            stateLabel = "CANDIDATE_${current.hits}",
            acceptedIdentity = current.identity
        )
        is State.Live -> Update(
            captureRequested = captureRequested,
            resultVisible = true,
            stateLabel = if (current.switchCandidate == null) "LIVE" else "SWITCH_${current.switchHits}",
            acceptedIdentity = current.identity
        )
    }

    companion object {
        private const val CANDIDATE_HITS_REQUIRED = 2
        private const val SWITCH_HITS_REQUIRED = 2
        private const val EXIT_MISSES_REQUIRED = 3

        const val IDLE_PROBE_MS = 650L
        const val ACTIVE_PROBE_MS = 350L
    }
}
