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

    @Test fun multiplePacksRemainReachableAndSelectTheirOwnId() {
        val first = opponentUiFixture()
        val second = first.copy(definition = first.definition.copy(
            id = "beginner", title = OpponentText(mapOf("en" to "Beginner pack", "ja" to "Beginner pack")),
            home = OpponentHomePlacement(OpponentHomeSection.CHALLENGES, OpponentHomeStyle.COMPACT, 10),
        ))
        var selected: String? = null
        composeRule.setContent {
            OthelloTheme {
                StandardHomeScreen(OpponentPackSnapshot(listOf(first, second)), { selected = it }, {}, {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithText("Beginner pack").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("beginner", selected) }
    }
}
