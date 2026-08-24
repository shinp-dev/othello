package com.example.othello.match

import com.example.othello.network.MAX_MATCH_NEGOTIATION_EPOCH
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OnlineMatchContractsTest {
    @Test
    fun serverStateEpochIsBoundedBeforeItReachesTheCoordinator() {
        MatchStartAck("RECONNECTING", false, false, negotiationEpoch = MAX_MATCH_NEGOTIATION_EPOCH)
        MatchFinishResult("RECONNECTING", negotiationEpoch = MAX_MATCH_NEGOTIATION_EPOCH)

        assertFailsWith<IllegalArgumentException> {
            MatchStartAck(
                "RECONNECTING", false, false,
                negotiationEpoch = MAX_MATCH_NEGOTIATION_EPOCH + 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MatchFinishResult(
                "RECONNECTING",
                negotiationEpoch = MAX_MATCH_NEGOTIATION_EPOCH + 1,
            )
        }
    }
}
