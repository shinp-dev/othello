package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardWinningTipsUiContractTest {
    private val route = File("src/main/kotlin/com/example/othello/StandardWinningTipsRoute.kt").readText()
    private val board = File("src/main/kotlin/com/example/othello/FantasyBoard.kt").readText()
    private val detail = File("src/main/kotlin/com/example/othello/StandardWinningTipDetailScreen.kt").readText()
    private val japaneseStrings = File("src/main/res/values-ja/standard_winning_tips.xml").readText()

    @Test
    fun indexKeepsExpertTipsUnlockedAndGroupsEachTier() {
        val index = route.substringAfter("private fun StandardWinningTipsIndexScreen(").substringBefore("private fun StandardTipTierBadge")
        assertTrue("StandardWinningTipTier.entries.forEach" in index)
        assertTrue("R.string.standard_winning_tips_expert_supporting" in index)
        assertTrue("if (tier == StandardWinningTipTier.EXPERT)" in index)
        assertTrue("verticalArrangement = Arrangement.spacedBy(12.dp)" in index)
        assertFalse("enabled = false" in index)
    }

    @Test
    fun indexUsesTheSharedFantasyBannersAndKeepsDetailTierBadge() {
        assertTrue("StandardTipTierSectionHeader(tier)" in route)
        assertTrue("R.drawable.standard_winning_tips_tier_banner" in route)
        assertTrue("R.drawable.standard_winning_tips_card_frame" in route)
        assertTrue("color = TipsIvory" in route)
        assertTrue("color = MaterialTheme.colorScheme.onPrimaryContainer" in route)
        assertFalse("MaterialTheme.colorScheme.tertiaryContainer" in route)
        assertFalse("MaterialTheme.colorScheme.secondaryContainer" in route)
    }

    @Test
    fun everyTierTitleIsAnExplicitHeading() {
        val header = route.substringAfter("private fun StandardTipTierSectionHeader(")
            .substringBefore("private fun StandardTipTierBadge")

        assertTrue(".semantics { heading() }" in header)
        assertTrue("style = MaterialTheme.typography.titleLarge" in header)
        assertTrue("standard_winning_tips_expert_supporting" in header)
    }

    @Test
    fun detailUsesSharedShortLessonLayout() {
        assertTrue("standard_winning_tips_progress" in detail)
        assertTrue("StandardTipBoard(" in detail)
        assertTrue("standard_winning_tips_takeaway_label" in detail)
        assertTrue("standard_winning_tips_previous" in detail)
        assertTrue("standard_winning_tips_next" in detail)
        assertTrue("winning_tip_detail_bg" in detail)
    }

    @Test
    fun boardCaptionsAreLeftAlignedAndUseIntentionalBreaks() {
        assertTrue("textAlign = TextAlign.Start" in detail)
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
    fun sharedFantasyBoardUsesEightByEightArtAndHasNoInputHandler() {
        assertTrue("repeat(Board.SIZE) { row" in board)
        assertTrue("repeat(Board.SIZE) { column" in board)
        assertTrue("R.drawable.fantasy_board_surface" in board)
        assertTrue("R.drawable.fantasy_disc_black" in board)
        assertTrue("R.drawable.fantasy_disc_white" in board)
        assertTrue("R.drawable.fantasy_marker_ring" in board)
        assertTrue("R.drawable.fantasy_marker_x" in board)
        assertTrue("R.drawable.fantasy_marker_frame" in board)
        assertFalse("clickable" in board)
        assertFalse("CoordinateBoard" in board)
    }

    @Test
    fun tipsMapExistingFocusAndWarningSquaresToSharedMarkers() {
        val tipsBoard = File("src/main/kotlin/com/example/othello/StandardTipBoard.kt").readText()
        assertTrue("FantasyBoardMarkerStyle.GOLD_RING" in tipsBoard)
        assertTrue("FantasyBoardMarkerStyle.SQUARE_FRAME" in tipsBoard)
        assertTrue("FantasyBoardMarkerStyle.WARNING_CROSS" in tipsBoard)
        assertFalse("clickable" in board)
    }
}
