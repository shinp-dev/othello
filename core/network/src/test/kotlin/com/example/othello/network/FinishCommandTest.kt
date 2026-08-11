package com.example.othello.network

import com.example.othello.game.Disc
import com.example.othello.game.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FinishCommandTest {
    private val state = GameState()
    private val command = FinishCommand(
        matchId = "match",
        ply = state.ply,
        commandId = "finish-1",
        stateHash = state.stateHash(),
        loserDisc = Disc.BLACK,
        reason = FinishSignalReason.RESIGNATION,
    )

    @Test
    fun finishCommandRoundTripsAndDuplicateIsIdempotent() {
        val decoded = FinishCommandJson.decode(FinishCommandJson.encode(command)).getOrThrow()
        assertEquals(command, decoded)
        val validator = FinishCommandValidator("match", Disc.BLACK)
        assertIs<FinishCommandValidation.Accepted>(validator.validate(state, decoded))
        assertIs<FinishCommandValidation.Duplicate>(validator.validate(state, decoded))
    }

    @Test
    fun wrongMatchHashAndClaimedLoserAreRejected() {
        assertEquals(
            ProtocolViolation.MATCH_MISMATCH,
            (FinishCommandValidator("other", Disc.BLACK).validate(state, command) as FinishCommandValidation.Rejected).violation,
        )
        assertEquals(
            ProtocolViolation.HASH_MISMATCH,
            (FinishCommandValidator("match", Disc.BLACK).validate(state, command.copy(stateHash = "wrong")) as FinishCommandValidation.Rejected).violation,
        )
        assertEquals(
            ProtocolViolation.WRONG_TURN,
            (FinishCommandValidator("match", Disc.WHITE).validate(state, command) as FinishCommandValidation.Rejected).violation,
        )
    }
}
