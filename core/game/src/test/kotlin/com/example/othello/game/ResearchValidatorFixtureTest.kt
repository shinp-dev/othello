package com.example.othello.game

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchValidatorFixtureTest {
    private val fixture = Properties().apply {
        requireNotNull(ResearchValidatorFixtureTest::class.java.getResourceAsStream("/research-validator-v1.properties")).use(::load)
    }

    @Test
    fun sharedNormalFixtureReplaysWithTheSameHashResultAndDecisionCounts() {
        val canonical = fixture.getProperty("normal.canonicalMoves")
        var state = GameState()
        var blackDecisions = 0
        var whiteDecisions = 0
        CanonicalMoves.decode(canonical).forEach { move ->
            val outcome = if (move == null) {
                state.pass()
            } else {
                if (state.currentPlayer == Disc.BLACK) blackDecisions++ else whiteDecisions++
                state.play(move)
            }
            assertFalse(outcome is MoveOutcome.Rejected, "shared validator fixture must stay legal")
            state = when (outcome) {
                is MoveOutcome.Played -> outcome.state
                is MoveOutcome.Passed -> outcome.state
                is MoveOutcome.Rejected -> error(outcome.reason)
            }
        }

        assertTrue(canonical.contains("--"), "fixture must exercise forced-pass handling")
        assertTrue(state.status is GameStatus.Finished)
        assertEquals(fixture.getProperty("normal.finalPositionHash"), state.stateHash())
        val result = (state.status as GameStatus.Finished).result
        assertEquals(
            fixture.getProperty("normal.result"),
            when (result.winner) {
                Disc.BLACK -> "BLACK_WIN"
                Disc.WHITE -> "WHITE_WIN"
                null, Disc.EMPTY -> "DRAW"
            },
        )
        assertEquals(fixture.getProperty("normal.blackDecisionCount").toInt(), blackDecisions)
        assertEquals(fixture.getProperty("normal.whiteDecisionCount").toInt(), whiteDecisions)
    }

    @Test
    fun sharedZeroPlyFixtureUsesTheInitialStateHash() {
        assertEquals(fixture.getProperty("zeroPly.finalPositionHash"), GameState().stateHash())
    }
}
