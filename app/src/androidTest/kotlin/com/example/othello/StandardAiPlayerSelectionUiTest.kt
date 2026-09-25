package com.example.othello

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
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
    fun singleOpponentPackHasOneCountAndNoAdjacentCards() {
        val oneOpponent = pack.copy(definition = pack.definition.copy(players = pack.definition.players.take(1)))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.JAPAN) }
        val localizedContext = context.createConfigurationContext(configuration)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    StandardAiPlayerSelectionContent(
                        installedPack = oneOpponent,
                        progress = StandardAiProgress(oneOpponent.definition),
                        selectedPlayer = oneOpponent.definition.players.single(),
                        onPlayerSelected = {},
                        onStart = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("1体のどうぶつAIに挑戦").assertExists()
        composeRule.onNodeWithText("1 / 1").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-chick").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-rabbit").assertDoesNotExist()
    }

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
        composeRule.onNodeWithTag("standard-ai-selection-title").assertExists()
        composeRule.onNodeWithTag("standard-ai-selection-subtitle").assertExists()
        composeRule.onNodeWithTag("standard-ai-selection-dots").assertExists()
        composeRule.onNodeWithText("1 / 8").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-chick").assertExists()
        composeRule.onNodeWithTag("standard-ai-state-animal-rabbit").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-wild-elephant").assertDoesNotExist()
        composeRule.onNodeWithText("ひよこに勝つと解放").assertExists()
    }

    @Test
    fun opponentSelectionAndExplicitStartAreSeparateActions() {
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
        composeRule.runOnIdle { assertNull(started) }
        composeRule.onNodeWithTag("standard-ai-start").performClick()
        composeRule.runOnIdle { assertEquals(pack.definition.players[4], started) }
    }

    @Test
    fun firstAndLastOpponentsHaveNonCyclingCarouselEdges() {
        var selected = mutableStateOf(pack.definition.players.first())
        composeRule.setContent {
            OthelloTheme {
                StandardAiPlayerSelectionContent(
                    installedPack = pack,
                    progress = StandardAiProgress(pack.definition),
                    selectedPlayer = selected.value,
                    onPlayerSelected = { selected.value = it },
                    onStart = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag("standard-ai-player-animal-wild-elephant").assertDoesNotExist()
        composeRule.onNodeWithTag("standard-ai-player-animal-rabbit").performClick()
        composeRule.runOnIdle { assertEquals("rabbit", selected.value.id) }
        composeRule.onNodeWithTag("standard-ai-player-animal-chick").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-koala").assertExists()
        composeRule.runOnIdle { selected.value = pack.definition.players.last() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals("wild-elephant", selected.value.id) }
        composeRule.onNodeWithTag("standard-ai-player-animal-chick").assertDoesNotExist()
        composeRule.onNodeWithTag("standard-ai-player-animal-wild-koala").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-rabbit").assertDoesNotExist()
    }
}
