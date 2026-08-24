package com.example.othello

import com.example.othello.network.TransportState
import kotlin.test.assertEquals
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
                currentEpoch = 3,
                serverStatus = "ACTIVE",
                serverEpoch = 3,
                serverLocalAcked = true,
                serverBothAcked = true,
            ),
        )
    }

    @Test
    fun genuineDisconnectAfterCompletedEpochThreeRequestsBudgetDecision() {
        assertEquals(
            ReleaseRenegotiationAction.START_NEW_EPOCH,
            planReleaseRenegotiation(
                force = false,
                transportState = TransportState.OPEN,
                freshReconnectEpochRequired = true,
                adoptedReconnectEpoch = null,
                currentEpoch = 3,
                serverStatus = "ACTIVE",
                serverEpoch = 3,
                serverLocalAcked = true,
                serverBothAcked = true,
            ),
        )
    }
}
