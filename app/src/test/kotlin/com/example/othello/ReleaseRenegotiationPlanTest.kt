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
                controllerReconnecting = false,
                currentEpoch = 1,
                serverStatus = "ACTIVE",
                serverEpoch = 1,
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
                controllerReconnecting = true,
                currentEpoch = 0,
                serverStatus = "RECONNECTING",
                serverEpoch = 1,
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
                controllerReconnecting = true,
                currentEpoch = 0,
                serverStatus = "ACTIVE",
                serverEpoch = 1,
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
                controllerReconnecting = true,
                currentEpoch = 1,
                serverStatus = "ACTIVE",
                serverEpoch = 1,
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
                controllerReconnecting = true,
                currentEpoch = 1,
                serverStatus = "ACTIVE",
                serverEpoch = 1,
            ),
        )
    }
}
