package com.example.othello.game

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameCoreTest {
    @Test fun initialPositionHasFourLegalMoves() {
        assertEquals(setOf(Position(2, 3), Position(3, 2), Position(4, 5), Position(5, 4)), GameState().legalMoves)
    }

    @Test fun oneDirectionFlip() {
        val state = GameState(Board.fromRows(listOf(
            "........", "........", "........", "...WB...", "........", "........", "........", "........",
        )))
        val outcome = state.play(Position(3, 2)) as MoveOutcome.Played
        assertEquals(Disc.BLACK, outcome.state.board[Position(3, 3)])
        assertEquals(3, outcome.state.board.count(Disc.BLACK), outcome.state.board.toCompactString())
    }

    @Test fun multipleDirectionFlipAndCountsConserveBoard() {
        var state = GameState()
        repeat(200) {
            if (state.status is GameStatus.Finished) return@repeat
            val moves = state.legalMoves
            val outcome = if (moves.isNotEmpty()) state.play(moves.first()) else state.pass()
            state = (outcome as? MoveOutcome.Played)?.state ?: (outcome as MoveOutcome.Passed).state
            assertEquals(64, state.board.count(Disc.BLACK) + state.board.count(Disc.WHITE) + state.board.count(Disc.EMPTY))
        }
    }

    @Test fun passOnlyWhenThereAreNoLegalMoves() {
        val state = GameState(Board.fromRows(listOf("BBBBBBB.", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB")), Disc.WHITE)
        assertTrue(state.legalMoves.isEmpty())
        assertTrue(state.pass() is MoveOutcome.Passed)
        assertTrue(GameState().pass() is MoveOutcome.Rejected)
    }

    @Test fun terminalAfterConsecutivePasses() {
        val state = GameState(Board.fromRows(listOf("BBBBBBB.", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB")), Disc.WHITE)
        val afterFirst = (state.pass() as MoveOutcome.Passed).state
        val afterSecond = (afterFirst.pass() as MoveOutcome.Passed).state
        assertTrue(afterSecond.status is GameStatus.Finished)
        assertEquals(63, (afterSecond.status as GameStatus.Finished).result.black)
        assertEquals(64, afterSecond.board.count(Disc.BLACK) + afterSecond.board.count(Disc.WHITE) + afterSecond.board.count(Disc.EMPTY))
    }

    @Test fun randomLegalGamesNeverCreateIllegalTransition() {
        repeat(20) {
            var state = GameState()
            val random = Random(it)
            while (state.status is GameStatus.InProgress) {
                val moves = state.legalMoves.toList()
                val outcome = if (moves.isEmpty()) state.pass() else state.play(moves[random.nextInt(moves.size)])
                state = when (outcome) {
                    is MoveOutcome.Played -> outcome.state
                    is MoveOutcome.Passed -> outcome.state
                    is MoveOutcome.Rejected -> error("legal game rejected: ${outcome.reason}")
                }
                assertEquals(64, state.board.count(Disc.BLACK) + state.board.count(Disc.WHITE) + state.board.count(Disc.EMPTY))
            }
        }
    }
}
