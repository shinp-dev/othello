package com.example.othello

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class TheoryBoardTextSettingsTest {
    @Test
    fun presetsKeepTheirTargetsWhenTheCellHasRoom() {
        assertEquals(10f, resolveTheoryBoardTextSizeSp(TheoryBoardTextSize.STANDARD, 48f, 1f), 0.001f)
        assertEquals(12f, resolveTheoryBoardTextSizeSp(TheoryBoardTextSize.LARGE, 48f, 1f), 0.001f)
        assertEquals(16f, resolveTheoryBoardTextSizeSp(TheoryBoardTextSize.EXTRA_LARGE, 48f, 1f), 0.001f)
    }

    @Test
    fun extraLargeClampsToKeepTwoLinesInsideTheCell() {
        val cellHeightDp = 40f
        val fontScale = 1.3f
        val resolved = resolveTheoryBoardTextSizeSp(
            TheoryBoardTextSize.EXTRA_LARGE,
            cellHeightDp,
            fontScale,
        )

        assertTrue(resolved < TheoryBoardTextSize.EXTRA_LARGE.targetSp)
        val occupiedHeightDp = resolved * THEORY_BOARD_LINE_HEIGHT_FACTOR * 2f * fontScale
        assertTrue(occupiedHeightDp <= cellHeightDp - 4f + 0.001f)
    }

    @Test
    fun unknownStoredValueFallsBackToStandard() {
        assertEquals(TheoryBoardTextSize.STANDARD, TheoryBoardTextSize.fromStoredValue("unexpected"))
        assertEquals(TheoryBoardTextSize.STANDARD, TheoryBoardTextSize.fromStoredValue(null))
    }
}
