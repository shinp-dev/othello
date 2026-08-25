package com.example.othello.transport.webrtc

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NonTrickleIceGatheringGateTest {
    @Test
    fun completeBeforeCandidateDoesNotReleaseCandidateZeroSdp() {
        val gate = NonTrickleIceGatheringGate()
        gate.onGatheringStarted(0)
        gate.onGatheringComplete(0)

        assertIs<NonTrickleIceReadiness.WaitingForCandidate>(
            gate.readiness(callbackGeneration = 0, sdpCandidateCount = 0),
        )
    }

    @Test
    fun candidateCallbackMustBeReflectedBeforeSdpIsReady() {
        val gate = NonTrickleIceGatheringGate()
        gate.onGatheringStarted(0)
        gate.onGatheringComplete(0)
        gate.onCandidate(0)

        assertEquals(
            NonTrickleIceReadiness.WaitingForSdpReflection(expected = 1, reflected = 0),
            gate.readiness(callbackGeneration = 0, sdpCandidateCount = 0),
        )
        assertEquals(
            NonTrickleIceReadiness.Ready(candidateCount = 1),
            gate.readiness(callbackGeneration = 0, sdpCandidateCount = 1),
        )
    }

    @Test
    fun statsOnlyCandidatesDoNotCreateAnImpossibleCallbackExpectation() {
        val gate = NonTrickleIceGatheringGate()
        gate.onGatheringStarted(0)
        gate.onGatheringComplete(0)
        gate.onCandidate(0)

        assertEquals(
            NonTrickleIceReadiness.Ready(candidateCount = 1),
            gate.readiness(callbackGeneration = 0, sdpCandidateCount = 1),
        )
    }

    @Test
    fun configuredStunServerSettlesOnCandidateOrError() {
        val url = "stun:stun.l.google.com:19302"
        val candidateGate = NonTrickleIceGatheringGate(setOf(url))
        candidateGate.onGatheringStarted(0)
        candidateGate.onGatheringComplete(0)
        candidateGate.onCandidate(0)
        assertEquals(false, candidateGate.externalGatheringSettled(0))
        candidateGate.onCandidate(0, url)
        assertEquals(true, candidateGate.externalGatheringSettled(0))

        val errorGate = NonTrickleIceGatheringGate(setOf(url))
        errorGate.onCandidateError(0, url.uppercase())
        assertEquals(true, errorGate.externalGatheringSettled(0))
    }

    @Test
    fun confirmedCandidateZeroEndsAsExplicitFailure() {
        val gate = NonTrickleIceGatheringGate()
        gate.onGatheringStarted(0)
        gate.onGatheringComplete(0)

        assertIs<NoIceCandidatesException>(gate.timeoutFailure(0))
    }

    @Test
    fun sdpCandidateCounterIgnoresNonCandidateLines() {
        assertEquals(
            2,
            countSdpCandidates(
                """
                v=0
                m=application 9 UDP/DTLS/SCTP webrtc-datachannel
                a=candidate:first
                a=ice-ufrag:test
                  a=candidate:second
                """.trimIndent(),
            ),
        )
    }
}
