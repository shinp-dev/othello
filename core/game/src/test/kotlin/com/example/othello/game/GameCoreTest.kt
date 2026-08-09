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

    @Test fun eightDirectionFlipUsesOneCaptureImplementation() {
        val state = GameState(Board.fromRows(listOf(
            "........", ".B.B.B..", "..WWW...", ".BW.WB..", "..WWW...", ".B.B.B..", "........", "........",
        )))
        val outcome = state.play(Position(3, 3)) as MoveOutcome.Played
        assertEquals(
            setOf(
                Position(2, 2), Position(2, 3), Position(2, 4),
                Position(3, 2), Position(3, 4),
                Position(4, 2), Position(4, 3), Position(4, 4),
            ),
            outcome.flipped.toSet(),
        )
        assertEquals(8, outcome.flipped.size)
    }

    @Test fun canonicalMovesRoundTripIncludingPass() {
        val moves = listOf(Position(2, 3), null, Position(7, 7), Position(0, 0))
        assertEquals("d3--h8a1", CanonicalMoves.encode(moves))
        assertEquals(moves, CanonicalMoves.decode("d3--h8a1"))
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

    @Test fun forcedPassIsDerivedAtBothPeersWithIdenticalCanonicalState() {
        val initial = GameState(Board.fromRows(listOf(
            "BBBBBBB.", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB", "BBBBBBBB",
        )), Disc.WHITE)
        val local = TurnResolver.resolveForcedPasses(initial)
        val remote = TurnResolver.resolveForcedPasses(initial)

        assertEquals(2, local.forcedPasses)
        assertEquals(local.state, remote.state)
        assertEquals(local.state.ply, remote.state.ply)
        assertEquals(local.state.stateHash(), remote.state.stateHash())
        assertEquals(CanonicalMoves.encode(List(local.forcedPasses) { null }), CanonicalMoves.encode(List(remote.forcedPasses) { null }))
        assertTrue(local.state.status is GameStatus.Finished)
    }

    @Test fun aSingleForcedPassReturnsTurnToTheSamePlayer() {
        var state = GameState()
        var found = false
        var seed = 0
        while (!found && seed < 100) {
            state = GameState()
            val random = Random(seed++)
            while (state.status is GameStatus.InProgress) {
                val move = state.legalMoves.firstOrNull() ?: break
                val played = (state.play(move) as MoveOutcome.Played).state
                val resolution = TurnResolver.resolveForcedPasses(played)
                if (resolution.forcedPasses == 1) {
                    assertEquals(played.currentPlayer.opponent(), resolution.state.currentPlayer)
                    assertEquals(played.ply + 1, resolution.state.ply)
                    found = true
                    break
                }
                state = resolution.state
                if (state.status is GameStatus.InProgress && state.legalMoves.isNotEmpty()) {
                    val next = state.legalMoves.elementAt(random.nextInt(state.legalMoves.size))
                    state = (state.play(next) as MoveOutcome.Played).state
                }
            }
        }
        assertTrue(found, "expected a deterministic legal game to contain a single forced pass")
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
