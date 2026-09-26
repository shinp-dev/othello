package com.example.othello

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    ChanrivaNameSelectionScreen(onBack = {}, onNameConfirmed = {})
                }
            }
        }

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
        composeRule.onNodeWithText("やわらかひなた").assertHasClickAction()
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_reroll))
            .assertHasClickAction()

        composeRule.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals("Screenshot pixel width for ${width}dp emulator", width, bitmap.width)
        saveScreenshot(context, bitmap, "chanriva-name-selection-${width}dp.png")
        bitmap.recycle()

        composeRule.onNodeWithText("やわらかひなた").performClick()
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_reroll))
            .performClick()
        composeRule.onNodeWithText("翠玉のほたる").assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_rerolled))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_reroll_used))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.chanriva_name_selection_reroll_used))
            .assertIsNotEnabled()
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
