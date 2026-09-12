package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ModeSelectionUiContractTest {
    private val source = File("src/main/kotlin/com/example/othello/StandardModeNavigation.kt").readText()

    @Test
    fun modeSelectionUsesVisualChoiceCardsInsteadOfPlainTextCards() {
        val screen = source.substringAfter("private fun ModeSelectionScreen(")
            .substringBefore("@Composable\nprivate fun ModeChoiceCard")

        assertTrue("R.string.mode_selection_title" in screen)
        assertTrue("R.string.mode_selection_recommended" in screen)
        assertTrue("R.string.mode_selection_advanced_badge" in screen)
        assertTrue("StandardOpponentPacks.animal.bannerDrawableRes" in screen)
        assertTrue("AdvancedModePreview()" in screen)
        assertTrue("R.string.mode_selection_switch_note" in screen)
        assertFalse("ModeCard(" in screen)
    }

    @Test
    fun standardIsHighlightedWhileAdvancedKeepsAResearchPreview() {
        val cards = source.substringAfter("private fun ModeChoiceCard(")
            .substringBefore("@Composable\nprivate fun StandardHomeScreen")

        assertTrue("MaterialTheme.colorScheme.secondaryContainer" in cards)
        assertTrue("MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)" in cards)
        assertTrue("private fun AdvancedModePreview()" in cards)
        assertTrue("private fun MiniAnalysisBoard()" in cards)
        assertTrue("ChanrivaColors.board" in cards)
        assertTrue("\"+3.2\"" in cards)
    }
}
