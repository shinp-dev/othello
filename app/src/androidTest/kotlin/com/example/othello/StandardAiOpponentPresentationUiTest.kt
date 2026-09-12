package com.example.othello

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.designsystem.OthelloTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandardAiOpponentPresentationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun introShowsOpponentArtwork() {
        composeRule.setContent {
            OthelloTheme {
                StandardAiIntroDialog(StandardAiLevel.LV1, onStart = {})
            }
        }

        composeRule.onNodeWithTag("standard-ai-intro-dialog").assertExists()
        composeRule.onNodeWithTag("standard-ai-intro-image").assertExists()
    }

    @Test
    fun resultShowsOpponentArtwork() {
        composeRule.setContent {
            OthelloTheme {
                StandardAiResultDialog(
                    level = StandardAiLevel.LV8,
                    presentation = StandardAiResultPresentation(
                        outcome = StandardAiHumanOutcome.WIN,
                        firstClear = true,
                        conquered = true,
                    ),
                    onRetry = {},
                    onChooseOpponent = {},
                )
            }
        }

        composeRule.onNodeWithTag("standard-ai-result-dialog").assertExists()
        composeRule.onNodeWithTag("standard-ai-result-image").assertExists()
    }
}
