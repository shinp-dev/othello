package com.example.othello.theory

import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TheoryMetricsTest {
    @Test
    fun initialPositionMetricsUseTheMoverPerspective() {
        val move = Position(2, 3)
        val values = requireNotNull(TheoryMetricEvaluator.evaluateAll(GameState())[move]).values

        assertEquals(4, values["openness"])
        assertEquals(3, values["opponent_mobility"])
        assertEquals(4, values["frontier_discs"])
        assertEquals(5, values["potential_mobility"])
    }

    @Test
    fun cornerMoveFlippingThreeDirectionsCountsOpennessPerFlippedDisc() {
        val state = GameState(
            board = Board.fromRows(
                listOf(
                    ".WB.....",
                    "WW......",
                    "B.B.....",
                    "........",
                    "........",
                    "........",
                    "........",
                    "........",
                ),
            ),
            currentPlayer = Disc.BLACK,
        )
        val corner = Position(0, 0)
        val values = requireNotNull(TheoryMetricEvaluator.evaluateAll(state)[corner]).values

        // c2 and b3 each touch more than one flipped disc and therefore contribute repeatedly.
        assertEquals(4, values["openness"])
    }

    @Test
    fun edgeMoveCanGiveOpponentZeroMobility() {
        val state = GameState(
            board = Board.fromRows(
                listOf(
                    "........",
                    "........",
                    "........",
                    ".WB.....",
                    "........",
                    "........",
                    "........",
                    "........",
                ),
            ),
            currentPlayer = Disc.BLACK,
        )
        val edge = Position(3, 0)
        val values = requireNotNull(TheoryMetricEvaluator.evaluateAll(state)[edge]).values

        assertEquals(6, values["openness"])
        assertEquals(0, values["opponent_mobility"])
        assertEquals(3, values["frontier_discs"])
        assertEquals(0, values["potential_mobility"])
    }

    @Test
    fun frontierCountsEachDiscOnceEvenWhenItTouchesManyEmpties() {
        val values = requireNotNull(
            TheoryMetricEvaluator.evaluateAll(GameState())[Position(2, 3)],
        ).values

        assertEquals(4, values["frontier_discs"])
        assertTrue(requireNotNull(values["openness"]) > requireNotNull(values["frontier_discs"]) - 1)
    }

    @Test
    fun potentialMobilityCountsEachEmptySquareOnce() {
        val state = GameState(
            board = Board.fromRows(
                listOf(
                    "........",
                    ".WW.....",
                    ".W......",
                    "....WB..",
                    "........",
                    "........",
                    "........",
                    "........",
                ),
            ),
            currentPlayer = Disc.BLACK,
        )
        val values = requireNotNull(
            TheoryMetricEvaluator.evaluateAll(state)[Position(3, 3)],
        ).values

        // Three white discs have 18 empty adjacencies, but their union is 12 empty squares.
        assertEquals(12, values["potential_mobility"])
    }

    @Test
    fun passCandidateUsesTheImmediateOpponentMobilityBeforePassResolution() {
        val state = GameState(
            board = Board.fromRows(
                listOf(
                    ".WBBBBBB",
                    "BBBBBBBB",
                    "BBBBBBBB",
                    "BBBBBBBB",
                    "BBB.WBBB",
                    "BBBBBBBB",
                    "BBBBBBBB",
                    "BBBBBBBB",
                ),
            ),
            currentPlayer = Disc.BLACK,
        )
        val values = requireNotNull(
            TheoryMetricEvaluator.evaluateAll(state)[Position(0, 0)],
        ).values

        assertEquals(0, values["opponent_mobility"])
    }

    @Test
    fun terminalPositionHasNoCandidateMetrics() {
        val finished = GameState(
            board = Board.fromRows(List(Board.SIZE) { "BBBBBBBB" }),
            currentPlayer = Disc.BLACK,
            consecutivePasses = 2,
        )

        assertTrue(TheoryMetricEvaluator.evaluateAll(finished).isEmpty())
    }

    @Test
    fun registryCarriesStableIdsDirectionsCopyAndFormatting() {
        assertEquals(
            listOf("openness", "opponent_mobility", "frontier_discs", "potential_mobility"),
            TheoryMetricRegistry.definitions.map { it.id },
        )
        assertEquals(
            TheoryMetricDirection.HIGHER_IS_GENERALLY_BETTER,
            requireNotNull(TheoryMetricRegistry.find("potential_mobility")).direction,
        )
        assertEquals("潜在モビリティ", requireNotNull(TheoryMetricRegistry.find("potential_mobility")).text("ja").displayName)
        assertEquals("12", requireNotNull(TheoryMetricRegistry.find("potential_mobility")).format(12))
    }
}
