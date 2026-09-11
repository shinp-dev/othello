package com.example.othello

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.designsystem.OthelloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandardAiLevelSelectionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialProgressShowsFirstOpponentAndNextLockedState() {
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

        composeRule.onNodeWithTag("standard-ai-opponent-carousel").assertExists()
        composeRule.onNodeWithTag("standard-ai-level-1").assertExists()
        composeRule.onNodeWithTag("standard-ai-state-2").assertExists()
    }

    @Test
    fun centeredUnlockedOpponentStartsWithOneTap() {
        var selected = mutableStateOf(StandardAiLevel.LV5)
        var started: StandardAiLevel? = null
        composeRule.setContent {
            OthelloTheme {
                StandardAiLevelSelectionContent(
                    progress = StandardAiProgress(
                        highestUnlockedLevel = StandardAiLevel.LV8,
                        clearedLevels = StandardAiLevel.entries.take(4).toSet(),
                    ),
                    selectedLevel = selected.value,
                    onLevelSelected = { selected.value = it },
                    onStart = { started = selected.value },
                    onBack = {},
                )
            }
        }

        composeRule.runOnIdle { assertNull(started) }
        composeRule.onNodeWithTag("standard-ai-level-5").performClick()
        composeRule.runOnIdle { assertEquals(StandardAiLevel.LV5, started) }
    }
}
