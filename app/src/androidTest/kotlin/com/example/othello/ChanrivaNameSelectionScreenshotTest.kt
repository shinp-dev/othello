package com.example.othello

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Captures the name selection screen at the supported narrow phone widths. */
class ChanrivaNameSelectionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renderChanrivaNameSelectionAtConfiguredWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPAN)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        val width = checkNotNull(InstrumentationRegistry.getArguments().getString("captureWidth")).toInt()
        require(width in setOf(320, 360, 390)) {
            "Unsupported Chanriva name selection screenshot width: $width"
        }

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    ChanrivaNameSelectionScreen(
                        onBack = {},
                        onNameConfirmed = {},
                        initialHasRareCandidate = true,
                        rerolledHasRareCandidate = false,
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val bitmap = awaitRenderedNameScreen()
        assertEquals("Screenshot pixel width for ${width}dp emulator", width, bitmap.width)
        saveScreenshot(context, bitmap, "chanriva-name-selection-${width}dp.png")
        bitmap.recycle()

        val visibleLabels = listOf(
            localizedContext.getString(R.string.chanriva_name_selection_title),
            localizedContext.getString(R.string.chanriva_name_selection_supporting),
            localizedContext.getString(R.string.chanriva_name_selection_reroll_available),
            "やわらかひなた",
            "ほんわか麻衣",
            "のんびりかたつむり",
            localizedContext.getString(R.string.chanriva_name_selection_rare),
            localizedContext.getString(R.string.chanriva_name_selection_reroll),
            localizedContext.getString(R.string.chanriva_name_selection_confirm),
        )
        visibleLabels.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onAllNodesWithText(localizedContext.getString(R.string.chanriva_name_selection_rare))
            .assertCountEquals(1)
        composeRule.onNodeWithText("やわらかひなた").assertHasClickAction()
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_reroll))
            .assertHasClickAction()

        composeRule.onNodeWithText("やわらかひなた").performClick()
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_reroll))
            .performClick()
        composeRule.onNodeWithText("翠玉のほたる").assertIsDisplayed()
        composeRule.onAllNodesWithText(localizedContext.getString(R.string.chanriva_name_selection_rare))
            .assertCountEquals(0)
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_rerolled))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_reroll_used))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_reroll_used))
            .assertIsNotEnabled()
    }

    @Test
    fun renderThreeNormalCandidatesWithoutRareBadge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPAN)
        }
        val localizedContext = context.createConfigurationContext(configuration)

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    ChanrivaNameSelectionScreen(
                        onBack = {},
                        onNameConfirmed = {},
                        initialHasRareCandidate = false,
                    )
                }
            }
        }

        composeRule.onNodeWithText("やわらかひなた").assertIsDisplayed()
        composeRule.onNodeWithText("ほんわか麻衣").assertIsDisplayed()
        composeRule.onNodeWithText("のんびりかたつむり").assertIsDisplayed()
        composeRule.onAllNodesWithText(localizedContext.getString(R.string.chanriva_name_selection_rare))
            .assertCountEquals(0)
    }

    private fun awaitRenderedNameScreen(): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 12_000L
        do {
            composeRule.waitForIdle()
            val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val pixel = frame.getPixel(5, frame.height / 3)
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            val spread = maxOf(red, green, blue) - minOf(red, green, blue)
            if (spread > 10 && (red + green + blue) / 3 < 220) return frame
            frame.recycle()
            Thread.sleep(250)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Chanriva name selection screen was not visible before capture")
    }

    private fun saveScreenshot(context: android.content.Context, bitmap: Bitmap, name: String) {
        val uri = checkNotNull(context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ChanrivaPreviews")
            },
        ))
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Failed to encode Chanriva name selection screenshot: $name"
            }
        }
    }
}
