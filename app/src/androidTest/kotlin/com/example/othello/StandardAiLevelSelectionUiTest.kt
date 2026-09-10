package com.example.othello

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.designsystem.OthelloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandardAiLevelSelectionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialProgressEnablesOnlyLevelOne() {
        composeRule.setContent {
            OthelloTheme {
                StandardAiLevelSelectionContent(
                    progress = StandardAiProgress(),
                    selectedLevel = StandardAiLevel.LV1,
                    onLevelSelected = {},
                    onStart = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("standard-ai-level-1").assertIsEnabled()
        (2..8).forEach { level ->
            composeRule.onNodeWithTag("standard-ai-level-$level").assertIsNotEnabled()
        }
    }

    @Test
    fun anUnlockedLevelCanBeSelectedAndStartedAgain() {
        var selected by mutableStateOf(StandardAiLevel.LV1)
        var started: StandardAiLevel? = null
        composeRule.setContent {
            OthelloTheme {
                StandardAiLevelSelectionContent(
                    progress = StandardAiProgress(
                        highestUnlockedLevel = StandardAiLevel.LV8,
                        clearedLevels = StandardAiLevel.entries.take(4).toSet(),
                    ),
                    selectedLevel = selected,
                    onLevelSelected = { selected = it },
                    onStart = { started = selected },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("standard-ai-level-5").performScrollTo().performClick()
        composeRule.onNodeWithTag("standard-ai-start").performClick()

        composeRule.runOnIdle { assertEquals(StandardAiLevel.LV5, started) }
    }
}
