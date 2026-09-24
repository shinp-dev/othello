package com.example.othello

import android.content.ContentValues
import android.content.res.Configuration
import android.os.Environment
import android.provider.MediaStore
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Captures the real Compose layout on the emulator, including system bars. */
class ModeSelectionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun captureJapaneseModeSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.JAPAN)
        }
        val japaneseContext = context.createConfigurationContext(configuration)
        val width = checkNotNull(InstrumentationRegistry.getArguments().getString("captureWidth"))
        val selectedModes = mutableListOf<AppMode>()
        var realEventClicks = 0

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides japaneseContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    ModeSelectionScreen(
                        onSelect = { selectedModes += it },
                        onRealEvent = { realEventClicks++ },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("気軽に遊ぶ。対戦パック・ガチャ・リバーシ図鑑").assertHasClickAction()
        composeRule.onNodeWithContentDescription("人と楽しむ。大会やオセロイベントを探す").assertHasClickAction()
        composeRule.onNodeWithContentDescription("深く楽しむ。オンライン対戦・AI対戦・解析・棋譜レビュー").assertHasClickAction()
        composeRule.waitForIdle()
        saveScreenshot("mode-${width}dp.png")
        composeRule.onNodeWithContentDescription("気軽に遊ぶ。対戦パック・ガチャ・リバーシ図鑑").performClick()
        composeRule.onNodeWithContentDescription("人と楽しむ。大会やオセロイベントを探す").performClick()
        composeRule.onNodeWithContentDescription("深く楽しむ。オンライン対戦・AI対戦・解析・棋譜レビュー").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(AppMode.STANDARD, AppMode.ADVANCED), selectedModes)
            assertEquals(1, realEventClicks)
        }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        // Compose semantics may be ready a frame before the emulator display buffer updates.
        for (attempt in 0 until 20) {
            val pixel = bitmap.getPixel(5, bitmap.height / 2)
            if (android.graphics.Color.red(pixel) < 100 &&
                android.graphics.Color.green(pixel) < 100 &&
                android.graphics.Color.blue(pixel) < 130
            ) break
            bitmap.recycle()
            Thread.sleep(100)
            bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        }
        check(android.graphics.Color.red(bitmap.getPixel(5, bitmap.height / 2)) < 100) {
            "Emulator screenshot is still blank"
        }
        val context = instrumentation.targetContext
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
        bitmap.recycle()
    }
}
