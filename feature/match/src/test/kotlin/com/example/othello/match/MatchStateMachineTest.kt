package com.example.othello.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MatchStateMachineTest {
    @Test fun happyPathIsExplicit() {
        var state = MatchState()
        listOf(
            MatchCommand.JoinQueue to MatchStatus.WAITING,
            MatchCommand.MatchFound to MatchStatus.SIGNALING,
            MatchCommand.OfferAccepted to MatchStatus.P2P_CONNECTED,
            MatchCommand.DataChannelOpened to MatchStatus.PLAYING,
            MatchCommand.GameFinished to MatchStatus.FINISHING,
            MatchCommand.ResultConfirmed to MatchStatus.CONFIRMED,
        ).forEach { (command, expected) ->
            state = assertIs<MatchTransition.Accepted>(MatchStateMachine.reduce(state, command)).state
            assertEquals(expected, state.status)
        }
    }

    @Test fun invalidTransitionIsRejectedWithoutMutation() {
        val state = MatchState()
        val result = MatchStateMachine.reduce(state, MatchCommand.DataChannelOpened)
        assertIs<MatchTransition.Rejected>(result)
        assertEquals(state, result.state)
    }
}
