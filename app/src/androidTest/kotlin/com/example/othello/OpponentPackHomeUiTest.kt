package com.example.othello

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.othello.designsystem.OthelloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OpponentPackHomeUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun threeHomeCardsOpenTheirMatchingDestinations() {
        val animals = opponentUiFixture("animal")
        val king = opponentUiFixture("lione-boss")
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        var selected: String? = null
        var tipsOpened = false
        composeRule.setContent {
            OthelloTheme {
                StandardHomeScreen(
                    opponents = OpponentPackSnapshot(listOf(animals, king)),
                    onPackSelected = { selected = it },
                    onWinningTips = { tipsOpened = true },
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.standard_home_animals_title)).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("animal", selected) }
        composeRule.onNodeWithText(context.getString(R.string.standard_home_king_title)).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("lione-boss", selected) }
        composeRule.onNodeWithText(context.getString(R.string.standard_winning_tips_title)).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(true, tipsOpened) }
    }
}
