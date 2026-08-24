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
            MatchCommand.DataChannelOpened to MatchStatus.P2P_CONNECTED,
            MatchCommand.StartConfirmed to MatchStatus.PLAYING,
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

    @Test fun pendingResultResolvesAndTransportFailuresCanRetry() {
        val pending = MatchState(MatchStatus.PENDING_RESULT)
        assertEquals(MatchStatus.CONFIRMED, (MatchStateMachine.reduce(pending, MatchCommand.ResultConfirmed) as MatchTransition.Accepted).state.status)
        assertEquals(MatchStatus.DISPUTED, (MatchStateMachine.reduce(pending, MatchCommand.ResultDisputed) as MatchTransition.Accepted).state.status)
        val disconnected = MatchState(MatchStatus.DISCONNECTED)
        assertEquals(MatchStatus.WAITING, (MatchStateMachine.reduce(disconnected, MatchCommand.Retry) as MatchTransition.Accepted).state.status)
    }

    @Test fun terminalStatesRejectLateProtocolEventsWithoutMutation() {
        val terminalStatuses = listOf(
            MatchStatus.CONFIRMED,
            MatchStatus.FORFEIT,
            MatchStatus.EXPIRED,
            MatchStatus.ABANDONED,
            MatchStatus.DISPUTED,
        )
        val lateEvents = listOf(
            MatchCommand.MoveAcknowledged,
            MatchCommand.Synchronized,
            MatchCommand.Reconnected,
            MatchCommand.GameFinished,
            MatchCommand.ResultConfirmed,
            MatchCommand.ResultPending,
            MatchCommand.ResultDisputed,
            MatchCommand.Retry,
        )

        terminalStatuses.forEach { status ->
            val terminal = MatchState(status)
            lateEvents.forEach { event ->
                val result = assertIs<MatchTransition.Rejected>(MatchStateMachine.reduce(terminal, event))
                assertEquals(terminal, result.state)
            }
        }
    }
}
