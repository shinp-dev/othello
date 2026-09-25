package com.example.othello

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Captures the production animal opponent selection screen using the bundled transparent character art. */
class StandardAiAnimalSelectionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renderAnimalSelectionAtConfiguredWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.JAPAN) }
        val localizedContext = context.createConfigurationContext(configuration)
        val width = checkNotNull(InstrumentationRegistry.getArguments().getString("captureWidth")).toInt()
        require(width in setOf(320, 360, 390)) { "Unsupported animal selection screenshot width: $width" }
        val animals = opponentUiFixture("animal")
        val progress = StandardAiProgress(animals.definition)

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    StandardAiPlayerSelectionContent(
                        installedPack = animals,
                        progress = progress,
                        selectedPlayer = animals.definition.players.first(),
                        onPlayerSelected = {},
                        onStart = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("どうぶつチャレンジ").assertIsDisplayed()
        composeRule.onNodeWithText("8体のどうぶつAIに挑戦").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 8").assertIsDisplayed()
        composeRule.onNodeWithText("この相手と対戦する").assertIsDisplayed()
        composeRule.onNodeWithText("ひよこに勝つと解放").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-chick").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-rabbit").assertExists()
        composeRule.onNodeWithTag("standard-ai-player-animal-wild-elephant").assertDoesNotExist()

        // Wait for Coil to decode the existing transparent chick and rabbit portraits before capture.
        Thread.sleep(700)
        composeRule.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals("Screenshot pixel width for ${width}dp emulator", width, bitmap.width)
        val name = "standard-animal-selection-${width}dp.png"
        val uri = checkNotNull(context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ChanrivaPreviews")
            },
        ))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
            assertTrue("Failed to encode animal selection screenshot at ${width}dp", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("animalSelectionScreenshot", name) })
        println("Rendered StandardAiPlayerSelectionContent at ${width}dp; saved $name")
    }
}
