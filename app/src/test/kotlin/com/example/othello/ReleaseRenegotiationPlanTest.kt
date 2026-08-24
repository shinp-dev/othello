package com.example.othello

import com.example.othello.match.MatchStartAck
import com.example.othello.match.ReconnectEpochProgress
import com.example.othello.network.TransportState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ReleaseRenegotiationPlanTest {
    @Test
    fun transientOpenWithUnchangedActiveEpochDoesNotRenegotiate() {
        assertEquals(
            ReleaseRenegotiationAction.SKIP_TRANSIENT_RECOVERY,
            planReleaseRenegotiation(
                force = false,
                transportState = TransportState.OPEN,
                freshReconnectEpochRequired = false,
                adoptedReconnectEpoch = null,
                completedReconnectEpoch = null,
                currentEpoch = 1,
                serverStatus = "ACTIVE",
                serverEpoch = 1,
                serverLocalAcked = true,
                serverBothAcked = true,
            ),
        )
    }

    @Test
    fun reportBeforeServerReadSignalsTheAuthoritativeReconnectEpoch() {
        assertEquals(
            ReleaseRenegotiationAction.SIGNAL_CURRENT_EPOCH,
            planReleaseRenegotiation(
                force = false,
                transportState = TransportState.OPEN,
                freshReconnectEpochRequired = true,
                adoptedReconnectEpoch = null,
                completedReconnectEpoch = null,
                currentEpoch = 0,
                serverStatus = "RECONNECTING",
                serverEpoch = 1,
                serverLocalAcked = false,
                serverBothAcked = false,
            ),
        )
    }

    @Test
    fun alreadyRecoveredActiveEpochSynchronizesWithoutSpendingAnotherEpoch() {
        assertEquals(
            ReleaseRenegotiationAction.SYNCHRONIZE_CURRENT_EPOCH,
            planReleaseRenegotiation(
                force = false,
                transportState = TransportState.OPEN,
                freshReconnectEpochRequired = false,
                adoptedReconnectEpoch = null,
                completedReconnectEpoch = null,
                currentEpoch = 0,
                serverStatus = "ACTIVE",
                serverEpoch = 1,
                serverLocalAcked = true,
                serverBothAcked = true,
            ),
        )
    }

    @Test
    fun activeSameEpochReadAfterLocalDebounceStartsOneAuthoritativeReconnectEpoch() {
        assertEquals(
            ReleaseRenegotiationAction.START_NEW_EPOCH,
            planReleaseRenegotiation(
                force = false,
                transportState = TransportState.OPEN,
                freshReconnectEpochRequired = true,
                adoptedReconnectEpoch = null,
                completedReconnectEpoch = 1,
                currentEpoch = 1,
                serverStatus = "ACTIVE",
                serverEpoch = 1,
                serverLocalAcked = true,
                serverBothAcked = true,
            ),
        )
    }

    @Test
    fun failedTransportStartsOneNewEpochFromActiveServerState() {
        assertEquals(
            ReleaseRenegotiationAction.START_NEW_EPOCH,
            planReleaseRenegotiation(
                force = false,
                transportState = TransportState.FAILED,
                freshReconnectEpochRequired = true,
                adoptedReconnectEpoch = null,
                completedReconnectEpoch = 1,
                currentEpoch = 1,
                serverStatus = "ACTIVE",
                serverEpoch = 1,
                serverLocalAcked = true,
                serverBothAcked = true,
            ),
        )
    }

    @Test
    fun ackResponseLossAtSameAdoptedEpochSynchronizesWithoutResume() {
        assertEquals(
            ReleaseRenegotiationAction.SYNCHRONIZE_CURRENT_EPOCH,
            planReleaseRenegotiation(
                force = true,
                transportState = TransportState.OPEN,
                freshReconnectEpochRequired = false,
                adoptedReconnectEpoch = 2,
                completedReconnectEpoch = null,
                currentEpoch = 2,
                serverStatus = "ACTIVE",
                serverEpoch = 2,
                serverLocalAcked = true,
                serverBothAcked = true,
            ),
        )
    }

    @Test
    fun epochThreeAckResponseLossNeverRequestsEpochFour() {
        assertEquals(
            ReleaseRenegotiationAction.SYNCHRONIZE_CURRENT_EPOCH,
            planReleaseRenegotiation(
                force = true,
                transportState = TransportState.OPEN,
                freshReconnectEpochRequired = false,
                adoptedReconnectEpoch = 3,
                completedReconnectEpoch = null,
                currentEpoch = 3,
                serverStatus = "ACTIVE",
                serverEpoch = 3,
                serverLocalAcked = true,
                serverBothAcked = true,
            ),
        )
    }

    @Test
    fun delayedForcedRetryAfterCompletedEpochThreeSynchronizesWithoutResume() = runBlocking {
        val progress = ReconnectEpochProgress(
            authoritativeEpoch = 3,
            completedEpoch = 3,
            serverLocalAcked = true,
            serverBothAcked = true,
        )
        val action = planReleaseRenegotiation(
            force = true,
            transportState = TransportState.OPEN,
            freshReconnectEpochRequired = progress.freshEpochRequired,
            adoptedReconnectEpoch = progress.adoptedEpoch,
            completedReconnectEpoch = progress.completedEpoch,
            currentEpoch = 3,
            serverStatus = "ACTIVE",
            serverEpoch = 3,
            serverLocalAcked = true,
            serverBothAcked = true,
        )

        assertEquals(ReleaseRenegotiationAction.SYNCHRONIZE_CURRENT_EPOCH, action)
        var resumeCalls = 0
        val resumeState = requestReconnectEpochIfRequired(action, "ACTIVE") {
            resumeCalls++
            MatchStartAck("EXPIRED", localAcked = false, bothAcked = false, negotiationEpoch = 3)
        }
        assertEquals(0, resumeCalls)
        assertNull(resumeState)
    }

    @Test
    fun genuineDisconnectAfterCompletedEpochThreeRequestsBudgetDecision() {
        val action = planReleaseRenegotiation(
            force = true,
            transportState = TransportState.OPEN,
            freshReconnectEpochRequired = true,
            adoptedReconnectEpoch = null,
            completedReconnectEpoch = 3,
            currentEpoch = 3,
            serverStatus = "ACTIVE",
            serverEpoch = 3,
            serverLocalAcked = true,
            serverBothAcked = true,
        )

        assertEquals(ReleaseRenegotiationAction.START_NEW_EPOCH, action)
        assertTrue(shouldRequestReconnectEpoch(action, "ACTIVE"))
    }

    @Test
    fun passivePeerNewEpochSignalUsesCoordinatorAdoptionGate() {
        val playingProgress = ReconnectEpochProgress(
            authoritativeEpoch = 0,
            completedEpoch = 0,
        )

        assertTrue(shouldAdoptReconnectEpochFromSignal(1, playingProgress))
        assertFalse(
            shouldAdoptReconnectEpochFromSignal(
                1,
                playingProgress.copy(authoritativeEpoch = 1, completedEpoch = 1),
            ),
        )
    }
}
