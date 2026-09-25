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
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Renders and captures the production StandardHomeScreen on the configured emulator display. */
class StandardHomeScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun renderStandardHomeAtConfiguredWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPAN)
        }
        val localizedContext = context.createConfigurationContext(configuration)
        val width = checkNotNull(InstrumentationRegistry.getArguments().getString("captureWidth"))
            .toInt()
        require(width in setOf(320, 360, 390)) { "Unsupported StandardHome screenshot width: $width" }

        val animals = opponentUiFixture("animal")
        val king = opponentUiFixture("lione-boss")
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    StandardHomeScreen(
                        opponents = OpponentPackSnapshot(listOf(animals, king)),
                        onPackSelected = {},
                        onWinningTips = {},
                    )
                }
            }
        }

        val expectedTitles = listOf(
            localizedContext.getString(R.string.standard_home_animals_title),
            localizedContext.getString(R.string.standard_home_king_title),
            localizedContext.getString(R.string.standard_winning_tips_title),
        )
        expectedTitles.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        // Give Coil time to decode the real transparent opponent banners from the bundled pack files.
        Thread.sleep(500)
        composeRule.waitForIdle()

        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals("Screenshot pixel width for ${width}dp emulator", width, bitmap.width)
        val name = "standard-home-${width}dp.png"
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
                "Failed to encode StandardHome screenshot for ${width}dp"
            }
        }
        bitmap.recycle()
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("standardHomeScreenshot", name)
        })
        println("Rendered StandardHomeScreen at ${width}dp; saved $name")
    }
}
