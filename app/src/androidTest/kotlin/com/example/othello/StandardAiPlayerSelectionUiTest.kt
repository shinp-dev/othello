package com.example.othello

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.othello.designsystem.OthelloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandardAiPlayerSelectionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pack = opponentUiFixture()

    @Test
    fun initialProgressShowsFirstOpponentAndNextLockedState() {
        composeRule.setContent {
            OthelloTheme {
                StandardAiPlayerSelectionContent(
                    installedPack = pack,
                    progress = StandardAiProgress(pack.definition),
                    selectedPlayer = pack.definition.players.first(),
                    onPlayerSelected = {},
                    onStart = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("standard-ai-opponent-carousel").assertExists()
        composeRule.onNodeWithTag("standard-ai-strength-guide").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-chick").assertExists()
        composeRule.onNodeWithTag("standard-ai-state-animal-rabbit").assertExists()
    }

    @Test
    fun centeredUnlockedOpponentStartsWithOneTap() {
        var selected = mutableStateOf(pack.definition.players[4])
        var started: Player? = null
        composeRule.setContent {
            OthelloTheme {
                StandardAiPlayerSelectionContent(
                    installedPack = pack,
                    progress = StandardAiProgress(
                        pack = pack.definition,
                        unlockedPlayerIds = pack.definition.players.map { it.id }.toSet(),
                        clearedPlayerIds = pack.definition.players.take(4).map { it.id }.toSet(),
                    ),
                    selectedPlayer = selected.value,
                    onPlayerSelected = { selected.value = it },
                    onStart = { started = selected.value },
                    onBack = {},
                )
            }
        }

        composeRule.runOnIdle { assertNull(started) }
        composeRule.onNodeWithTag("standard-ai-player-animal-wild-chick").performClick()
        composeRule.runOnIdle { assertEquals(pack.definition.players[4], started) }
    }
}
