package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardWinningTipsUiContractTest {
    private val route = File("src/main/kotlin/com/example/othello/StandardWinningTipsRoute.kt").readText()
    private val board = File("src/main/kotlin/com/example/othello/StandardTipBoard.kt").readText()
    private val japaneseStrings = File("src/main/res/values-ja/standard_winning_tips.xml").readText()

    @Test
    fun indexKeepsExpertTipsUnlockedButClearlySeparated() {
        assertTrue("StandardWinningTipTier.entries.forEach" in route)
        assertTrue("R.string.standard_winning_tips_expert_supporting" in route)
        assertTrue("if (tier == StandardWinningTipTier.EXPERT)" in route)
        assertFalse("enabled = false" in route.substringBefore("private fun StandardWinningTipDetailScreen"))
    }

    @Test
    fun indexUsesColorCodedTierHeaders() {
        assertTrue("StandardTipTierSectionHeader(tier)" in route)
        assertTrue("StandardWinningTipTier.BASIC -> MaterialTheme.colorScheme.primaryContainer" in route)
        assertTrue("StandardWinningTipTier.STEP_UP -> MaterialTheme.colorScheme.tertiaryContainer" in route)
        assertTrue("StandardWinningTipTier.EXPERT -> MaterialTheme.colorScheme.secondaryContainer" in route)
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
    fun boardCaptionsAreLeftAlignedAndUseIntentionalBreaks() {
        assertTrue("textAlign = TextAlign.Start" in route)
        assertTrue("\\n" in japaneseStrings)
    }

    @Test
    fun japaneseTipsUseDirectBeginnerGuidanceWithoutVisibleExceptions() {
        assertFalse("とは限りません" in japaneseStrings)
        assertFalse("ことがあります" in japaneseStrings)
        assertFalse("いつでも悪いわけではありません" in japaneseStrings)
        assertTrue("序盤は、石を少なく取る手を選びましょう。" in japaneseStrings)
        assertTrue("強い手は、相手が置ける場所を減らす手です。" in japaneseStrings)
        assertTrue("角に置けるなら、角を取りましょう。" in japaneseStrings)
        assertTrue("空いている角の斜め隣には置かない。" in japaneseStrings)
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
