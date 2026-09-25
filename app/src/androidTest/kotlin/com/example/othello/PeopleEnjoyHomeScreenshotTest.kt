package com.example.othello

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
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

/** Captures the production PeopleEnjoyHomeScreen at the three supported phone widths. */
class PeopleEnjoyHomeScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renderPeopleEnjoyHomeAtConfiguredWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPAN)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        val width = checkNotNull(InstrumentationRegistry.getArguments().getString("captureWidth")).toInt()
        require(width in setOf(320, 360, 390)) { "Unsupported PeopleEnjoyHome screenshot width: $width" }

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    PeopleEnjoyHomeScreen(onBack = {}, onPlay = {}, onEvents = {}, onCommunity = {})
                }
            }
        }

        val visibleLabels = listOf(
            localizedContext.getString(R.string.people_home_title),
            localizedContext.getString(R.string.people_play_title),
            localizedContext.getString(R.string.people_play_supporting),
            localizedContext.getString(R.string.people_events_title),
            localizedContext.getString(R.string.people_events_supporting),
            localizedContext.getString(R.string.people_social_title),
            localizedContext.getString(R.string.people_social_supporting),
        )
        visibleLabels.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        listOf(
            R.string.people_play_title,
            R.string.people_events_title,
            R.string.people_social_title,
        ).forEach { id ->
            composeRule.onNodeWithText(localizedContext.getString(id)).assertHasClickAction()
        }
        composeRule.waitForIdle()
        val bitmap = awaitVisiblePeopleHomeScreen()
        assertEquals("Screenshot pixel width for ${width}dp emulator", width, bitmap.width)
        saveScreenshot(context, bitmap, "people-enjoy-home-${width}dp.png")
        bitmap.recycle()
        println("Rendered PeopleEnjoyHomeScreen at ${width}dp: people-enjoy-home-${width}dp.png")
    }

    private fun awaitVisiblePeopleHomeScreen(): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 12_000L
        do {
            composeRule.waitForIdle()
            val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val backgroundPixel = frame.getPixel(5, frame.height / 2)
            val channelSpread = maxOf(
                android.graphics.Color.red(backgroundPixel),
                android.graphics.Color.green(backgroundPixel),
                android.graphics.Color.blue(backgroundPixel),
            ) - minOf(
                android.graphics.Color.red(backgroundPixel),
                android.graphics.Color.green(backgroundPixel),
                android.graphics.Color.blue(backgroundPixel),
            )
            if (channelSpread > 20) return frame
            frame.recycle()
            Thread.sleep(250)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("PeopleEnjoyHomeScreen background was not visible before capture")
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
                "Failed to encode PeopleEnjoyHome screenshot: $name"
            }
        }
    }
}
