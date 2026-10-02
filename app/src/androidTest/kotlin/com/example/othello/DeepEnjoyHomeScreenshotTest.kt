package com.example.othello

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeepEnjoyHomeScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renderDeepEnjoyHomeAtConfiguredWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPAN)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        val width = checkNotNull(InstrumentationRegistry.getArguments().getString("captureWidth")).toInt()
        require(width in setOf(320, 360, 390)) { "Unsupported DeepEnjoy screenshot width: $width" }

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
                        DeepEnjoyScreen(
                            failedLocalRecordSaveCount = 0,
                            onRetryFailedLocalRecords = {},
                            onLocalAiStart = {},
                            onLocalHumanStart = {},
                            onPositionReview = {},
                            onTheoryExploration = {},
                            onOfflineRecords = {},
                            onMatchSettings = {},
                            onReviewSettings = {},
                            onCommonSettings = {},
                            onMore = {},
                        )
                    }
                }
            }
        }

        listOf(
            R.string.enjoy_deeply,
            R.string.deep_battle_section,
            R.string.play_against_ai,
            R.string.two_player_match,
            R.string.play_online,
            R.string.deep_review_section,
        ).forEach { id ->
            composeRule.onNodeWithText(localizedContext.getString(id)).assertIsDisplayed()
        }
        composeRule.onNodeWithText(localizedContext.getString(R.string.play_against_ai)).assertHasClickAction()
        composeRule.onNodeWithText(localizedContext.getString(R.string.two_player_match)).assertHasClickAction()

        composeRule.waitForIdle()
        val bitmap = awaitVisibleDeepEnjoyScreen()
        assertEquals("Screenshot pixel width for ${width}dp emulator", width, bitmap.width)
        saveScreenshot(context, bitmap, "deep-enjoy-home-${width}dp.png")
        bitmap.recycle()
    }

    private fun awaitVisibleDeepEnjoyScreen(): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 12_000L
        do {
            composeRule.waitForIdle()
            val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
            val brightness = (
                android.graphics.Color.red(pixel) +
                    android.graphics.Color.green(pixel) +
                    android.graphics.Color.blue(pixel)
                ) / 3
            if (brightness < 80) return frame
            frame.recycle()
            Thread.sleep(250)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("DeepEnjoyScreen background was not visible before capture")
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
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
