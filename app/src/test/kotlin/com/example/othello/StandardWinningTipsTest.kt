package com.example.othello

import com.example.othello.game.MoveOutcome
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StandardWinningTipsTest {
    @Test
    fun tipsAreGroupedIntoFourBasicThreeStepUpAndTwoExpertItems() {
        assertEquals(9, standardWinningTips.size)
        assertEquals(4, standardWinningTips.count { it.tier == StandardWinningTipTier.BASIC })
        assertEquals(3, standardWinningTips.count { it.tier == StandardWinningTipTier.STEP_UP })
        assertEquals(2, standardWinningTips.count { it.tier == StandardWinningTipTier.EXPERT })
        assertEquals(standardWinningTips.size, standardWinningTips.map { it.id }.toSet().size)
    }

    @Test
    fun everyBoardFixtureReplaysAsALegalGamePosition() {
        standardWinningTips.forEach { tip ->
            val state = standardTipStateFor(tip.boardExample.moves)
            assertTrue(state.ply >= tip.boardExample.moves.size)
            (tip.boardExample.focusSquares + tip.boardExample.warningSquares).forEach { square ->
                standardTipPosition(square)
            }
        }
    }

    @Test
    fun takeLessExampleReallyComparesOneFlipWithFourFlips() {
        val tip = standardWinningTips.single { it.id == "take-less-early" }
        val state = standardTipStateFor(tip.boardExample.moves)
        val quiet = state.play(standardTipPosition("c2")) as MoveOutcome.Played
        val greedy = state.play(standardTipPosition("a3")) as MoveOutcome.Played

        assertEquals(1, quiet.flipped.size)
        assertEquals(4, greedy.flipped.size)
    }

    @Test
    fun mobilityExampleLeavesFourMovesInsteadOfNine() {
        val tip = standardWinningTips.single { it.id == "limit-mobility" }
        val state = standardTipStateFor(tip.boardExample.moves)
        val constrained = state.play(standardTipPosition("f4")) as MoveOutcome.Played
        val open = state.play(standardTipPosition("d6")) as MoveOutcome.Played

        assertEquals(4, constrained.state.legalMoves.size)
        assertEquals(9, open.state.legalMoves.size)
    }

    @Test
    fun xSquareExampleHandsTheCornerToTheOpponent() {
        val tip = standardWinningTips.single { it.id == "avoid-x-square" }
        val state = standardTipStateFor(tip.boardExample.moves)
        val xMove = state.play(standardTipPosition("b2")) as MoveOutcome.Played

        assertTrue(standardTipPosition("a1") in xMove.state.legalMoves)
    }

    @Test
    fun cornerExampleUsesARealLegalCornerAndStableExampleHasItOccupied() {
        val cornerTip = standardWinningTips.single { it.id == "corners" }
        val beforeCorner = standardTipStateFor(cornerTip.boardExample.moves)
        assertTrue(standardTipPosition("a8") in beforeCorner.legalMoves)

        val stableTip = standardWinningTips.single { it.id == "stable-discs" }
        val afterCorner = standardTipStateFor(stableTip.boardExample.moves)
        assertTrue(standardTipPosition("a8") !in afterCorner.legalMoves)
    }
}
