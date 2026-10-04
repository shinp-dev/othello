package com.example.othello

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StandardRealEventsScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renderRealEventsInConfiguredLocale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val width = checkNotNull(args.getString("captureWidth")).toInt()
        val localeTag = args.getString("captureLocale") ?: "ja"
        require(width == 390) { "Unsupported real-events screenshot width: $width" }

        val locale = if (localeTag == "en") Locale.ENGLISH else Locale.JAPAN
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localizedContext = context.createConfigurationContext(configuration)

        val events = if (localeTag == "en") englishEvents() else japaneseEvents()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
            ) {
                OthelloTheme {
                    StandardRealEventScreen(
                        state = StandardRealEventUiState.Loaded(events),
                        onBack = {},
                        onRetry = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText(
            localizedContext.getString(R.string.standard_real_event_discovered_title),
        ).assertExists()
        composeRule.onNodeWithText(
            localizedContext.getString(R.string.standard_real_event_official_title),
        ).assertExists()

        composeRule.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals(width, bitmap.width)
        val name = "standard-real-events-${localeTag}-${width}dp.png"
        saveScreenshot(context, bitmap, name)
        bitmap.recycle()
        println("Rendered StandardRealEventScreen: $name")
    }

    private fun japaneseEvents() = listOf(
        StandardRealEvent("13", "東京都", "2026-10-10", "東京おもちゃまつり2026（オセロの日）", "東京おもちゃ美術館", "https://example.com/1", "2026-09-12"),
        StandardRealEvent("34", "広島県", "2026-10-10", "THROW THE SPARK 2026 福山予選", "iti SETOUCHI", "https://example.com/2", "2026-09-20"),
        StandardRealEvent("27", "大阪府", "2026-10-17", "オセロをしよう", "桂青少年会館", "https://example.com/3", "2026-09-14"),
        StandardRealEvent("08", "茨城県", "2026-11-21", "水戸市オセロデー2026", "イオンモール水戸内原 1F メインコート", "https://example.com/4", "2026-09-12"),
        StandardRealEvent("12", "千葉県", "2026-11-08", "オセロ体験イベント", "青少年自然の家", "https://example.com/5", "2026-10-03"),
    )

    private fun englishEvents() = listOf(
        StandardRealEvent("13", "Tokyo", "2026-10-10", "Tokyo Toy Festival 2026 (Othello Day)", "Tokyo Toy Museum", "https://example.com/1", "2026-09-12"),
        StandardRealEvent("34", "Hiroshima", "2026-10-10", "THROW THE SPARK 2026 Fukuyama Qualifier", "iti SETOUCHI", "https://example.com/2", "2026-09-20"),
        StandardRealEvent("27", "Osaka", "2026-10-17", "Let's Play Othello", "Katsura Youth Center", "https://example.com/3", "2026-09-14"),
        StandardRealEvent("08", "Ibaraki", "2026-11-21", "Mito City Othello Day 2026", "Aeon Mall Mito Uchihara 1F Main Court", "https://example.com/4", "2026-09-12"),
        StandardRealEvent("12", "Chiba", "2026-11-08", "Othello Experience Event", "Youth Nature Center", "https://example.com/5", "2026-10-03"),
    )

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
