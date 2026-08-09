package com.example.othello.network

import com.example.othello.game.GameState
import com.example.othello.game.Disc
import com.example.othello.game.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MoveCommandValidatorTest {
    @Test fun validCommandIsAcceptedAndDuplicateIsIdempotent() {
        val state = GameState()
        val validator = MoveCommandValidator("match-1", Disc.BLACK)
        val command = MoveCommand("match-1", 0, Position(2, 3), "command-1", state.stateHash())
        assertIs<CommandValidation.Accepted>(validator.validate(state, command))
        assertIs<CommandValidation.Duplicate>(validator.validate(state, command))
    }

    @Test fun plyAndHashMismatchAreRejected() {
        val validator = MoveCommandValidator("match-1", Disc.BLACK)
        val state = GameState()
        val plyMismatch = MoveCommand("match-1", 1, Position(2, 3), "command-1", state.stateHash())
        val hashMismatch = MoveCommand("match-1", 0, Position(2, 3), "command-2", "wrong")
        assertEquals(ProtocolViolation.PLY_MISMATCH, (validator.validate(state, plyMismatch) as CommandValidation.Rejected).violation)
        assertEquals(ProtocolViolation.HASH_MISMATCH, (validator.validate(state, hashMismatch) as CommandValidation.Rejected).violation)
    }

    @Test fun illegalMoveIsRejectedWithoutMarkingCommandHandled() {
        val state = GameState()
        val validator = MoveCommandValidator("match-1", Disc.BLACK)
        val command = MoveCommand("match-1", 0, Position(0, 0), "command-1", state.stateHash())
        assertEquals(ProtocolViolation.ILLEGAL_MOVE, (validator.validate(state, command) as CommandValidation.Rejected).violation)
        val validRetry = command.copy(move = Position(2, 3))
        assertIs<CommandValidation.Accepted>(validator.validate(state, validRetry))
    }

    @Test fun wrongRemoteDiscIsRejectedAndReusedCommandPayloadIsNotDuplicate() {
        val state = GameState()
        val validator = MoveCommandValidator("match-1", Disc.WHITE)
        val command = MoveCommand("match-1", 0, Position(2, 3), "command-1", state.stateHash())
        assertEquals(ProtocolViolation.WRONG_TURN, (validator.validate(state, command) as CommandValidation.Rejected).violation)

        val accepting = MoveCommandValidator("match-1", Disc.BLACK)
        assertIs<CommandValidation.Accepted>(accepting.validate(state, command))
        val changed = command.copy(move = Position(3, 2))
        assertEquals(ProtocolViolation.COMMAND_ID_REUSE, (accepting.validate(state, changed) as CommandValidation.Rejected).violation)
    }
}
