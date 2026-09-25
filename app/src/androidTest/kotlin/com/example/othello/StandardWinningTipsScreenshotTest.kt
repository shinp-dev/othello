package com.example.othello

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renders the production tips list route at the configured emulator width and saves a PNG. */
class StandardWinningTipsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun renderWinningTipsListAtConfiguredWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPAN)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        val width = checkNotNull(InstrumentationRegistry.getArguments().getString("captureWidth")).toInt()
        require(width in setOf(320, 360, 390)) { "Unsupported WinningTips screenshot width: $width" }

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    StandardWinningTipsRoute(onBack = {})
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText(localizedContext.getString(R.string.standard_winning_tips_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.standard_winning_tips_intro))
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.standard_winning_tips_tier_basic))
            .assertIsDisplayed()
        val firstTip = localizedContext.getString(R.string.standard_winning_tip_take_less_title)
        composeRule.onNodeWithText(firstTip).assertIsDisplayed().assertHasClickAction()

        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals("Screenshot pixel width for ${width}dp emulator", width, bitmap.width)
        val name = "standard-winning-tips-${width}dp.png"
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
                "Failed to encode WinningTips screenshot for ${width}dp"
            }
        }
        bitmap.recycle()
        println("Rendered StandardWinningTipsRoute at ${width}dp; saved $name")

        composeRule.onNodeWithText(localizedContext.getString(R.string.standard_winning_tips_tier_step_up))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.standard_winning_tips_tier_expert))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(firstTip).performScrollTo().performClick()
        composeRule.onNodeWithText(localizedContext.getString(R.string.standard_winning_tips_progress, 1, standardWinningTips.size))
            .assertIsDisplayed()
    }
}
