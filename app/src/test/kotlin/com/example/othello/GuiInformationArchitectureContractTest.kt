package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class GuiInformationArchitectureContractTest {
    private val main = File("src/main/kotlin/com/example/othello/MainActivity.kt").readText()
    private val board = File("src/main/kotlin/com/example/othello/BoardUi.kt").readText()
    private val analysis = File("src/main/kotlin/com/example/othello/AnalysisScreens.kt").readText()
    private val research = File("src/main/kotlin/com/example/othello/ResearchSettingsScreen.kt").readText()

    @Test
    fun playAndScoreHeaderKeepModeOutOfTheFourthColumn() {
        val play = main.substringAfter("private fun PlayScreen(").substringBefore("internal fun opponentRatingLabel")
        val score = main.substringAfter("private fun ScoreHeader(game:")
            .substringBefore("private fun OthelloBoard")
        assertFalse("ちゃんと残る、ちゃんと振り返れるリバーシ" in play)
        assertTrue("ScoreHeader(viewState.game)" in main)
        assertTrue("status.orEmpty()" in score)
        assertFalse("AI対局" in score)
        assertFalse("ローカル" in score)
    }

    @Test
    fun coordinateBoardBalancesAllFourGutters() {
        assertTrue(board.count { it == 'G' } > 0)
        assertTrue(board.contains("Spacer(Modifier.width(CoordinateGutter))"))
        assertTrue(board.contains("Spacer(Modifier.weight(1f))"))
    }

    @Test
    fun settingsAndDataActionsUseTheNewHierarchy() {
        assertTrue(analysis.contains("R.string.ai_match_settings"))
        assertTrue(analysis.contains("R.string.match_common_settings"))
        assertTrue(analysis.contains("if (status.evaluationData != null)"))
        assertTrue(analysis.contains("if (status.openingBook != null)"))
        assertTrue(analysis.contains("R.string.delete_eval_confirm_text"))
        assertTrue(analysis.contains("R.string.delete_book_confirm_text"))
        assertFalse(analysis.contains("詳しい説明を見る"))
        assertTrue(analysis.contains("R.string.choose_book"))
    }

    @Test
    fun webRowsAreMarkedAndResearchSettingsAvoidInternalModelTerms() {
        val components = File("../core/designsystem/src/main/java/com/example/othello/designsystem/ChanrivaComponents.kt").readText()
        assertTrue(components.contains("trailingLabel: String?"))
        assertTrue(analysis.contains("R.string.edax_about"))
        assertTrue(analysis.contains("trailingLabel = \"Web\""))
        assertFalse(research.contains("研究subject:"))
        assertFalse(research.contains("参加period:"))
        assertTrue(research.contains("R.string.research_link"))
    }
}
