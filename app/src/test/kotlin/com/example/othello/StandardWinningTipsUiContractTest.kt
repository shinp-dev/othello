package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardWinningTipsUiContractTest {
    private val route = File("src/main/kotlin/com/example/othello/StandardWinningTipsRoute.kt").readText()
    private val board = File("src/main/kotlin/com/example/othello/StandardTipBoard.kt").readText()

    @Test
    fun indexKeepsExpertTipsUnlockedButClearlySeparated() {
        assertTrue("StandardWinningTipTier.entries.forEach" in route)
        assertTrue("R.string.standard_winning_tips_expert_supporting" in route)
        assertTrue("if (tier == StandardWinningTipTier.EXPERT)" in route)
        assertFalse("enabled = false" in route.substringBefore("private fun StandardWinningTipDetailScreen"))
    }

    @Test
    fun detailUsesSharedShortLessonLayout() {
        assertTrue("standard_winning_tips_progress" in route)
        assertTrue("StandardTipBoard(tip.boardExample)" in route)
        assertTrue("standard_winning_tips_takeaway_label" in route)
        assertTrue("standard_winning_tips_previous" in route)
        assertTrue("standard_winning_tips_next" in route)
    }

    @Test
    fun compactBoardIsEightByEightAndHasNoCoordinateLabelsOrInputHandler() {
        assertTrue("repeat(8) { row" in board)
        assertTrue("repeat(8) { column" in board)
        assertTrue("widthIn(max = 240.dp)" in board)
        assertTrue("ChanrivaColors.board" in board)
        assertTrue("ChanrivaColors.blackDisc" in board)
        assertTrue("ChanrivaColors.whiteDisc" in board)
        assertFalse("CoordinateBoard" in board)
        assertFalse("clickable" in board)
    }
}
