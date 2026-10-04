package com.example.othello

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
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
        val bitmap = awaitVisibleRealEventsScreen()
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
        StandardRealEvent("12", "千葉県", "2026-11-08", "第19回わんぱくこども祭り", "千葉県立手賀の丘青少年自然の家", "https://example.com/5", "2026-10-03"),
        StandardRealEvent("08", "茨城県", "2026-11-29", "水戸まちなか謎巡り「君色オセロ」", "水戸市中心市街地", "https://example.com/6", "2026-09-19"),
        StandardRealEvent("13", "東京都", "2026-10-11", "中島八段のジュニアオセロ教室 説明会", "品川区こみゅにてぃぷらざ八潮", "https://example.com/7", "2026-09-12"),
        StandardRealEvent("13", "東京都", "2026-10-11", "東京おもちゃまつり2026（オセロの日）", "東京おもちゃ美術館", "https://example.com/8", "2026-09-12"),
        StandardRealEvent("13", "東京都", "2026-10-18", "オセロが映し出す、人間とAIの未来", "立教大学 池袋キャンパス", "https://example.com/9", "2026-09-19"),
        StandardRealEvent("13", "東京都", "2026-11-08", "親子で遊ぼ ボードゲーム", "府中市市民活動センター プラッツ", "https://example.com/10", "2026-10-03"),
        StandardRealEvent("15", "新潟県", "2026-10-31", "長岡高専 学園祭", "長岡工業高等専門学校", "https://example.com/11", "2026-09-19"),
        StandardRealEvent("15", "新潟県", "2026-11-01", "長岡高専 学園祭", "長岡工業高等専門学校", "https://example.com/12", "2026-09-19"),
        StandardRealEvent("33", "岡山県", "2026-10-04", "人権映画会（巨大オセロ遊び）", "真庭市立中央図書館", "https://example.com/13", "2026-09-19"),
        StandardRealEvent("33", "岡山県", "2026-11-22", "THROW THE SPARK 2026 岡山決勝", "能楽堂ホール", "https://example.com/14", "2026-09-20"),
        StandardRealEvent("34", "広島県", "2026-10-11", "THROW THE SPARK 2026 福山予選", "iti SETOUCHI", "https://example.com/15", "2026-09-20"),
        StandardRealEvent("40", "福岡県", "2026-11-22", "食レク健康教室（人間オセロ）", "メイトム宗像", "https://example.com/16", "2026-09-19"),
    )

    private fun englishEvents() = listOf(
        StandardRealEvent("13", "Tokyo", "2026-10-10", "Tokyo Toy Festival 2026 (Othello Day)", "Tokyo Toy Museum", "https://example.com/1", "2026-09-12"),
        StandardRealEvent("34", "Hiroshima", "2026-10-10", "THROW THE SPARK 2026 Fukuyama Qualifier", "iti SETOUCHI", "https://example.com/2", "2026-09-20"),
        StandardRealEvent("27", "Osaka", "2026-10-17", "Let's Play Othello", "Katsura Youth Center", "https://example.com/3", "2026-09-14"),
        StandardRealEvent("08", "Ibaraki", "2026-11-21", "Mito City Othello Day 2026", "Aeon Mall Mito Uchihara 1F Main Court", "https://example.com/4", "2026-09-12"),
        StandardRealEvent("12", "Chiba", "2026-11-08", "Wanpaku Kids Festival", "Teganooka Youth Nature Center", "https://example.com/5", "2026-10-03"),
        StandardRealEvent("08", "Ibaraki", "2026-11-29", "Mito Othello Mystery Walk", "Central Mito", "https://example.com/6", "2026-09-19"),
        StandardRealEvent("13", "Tokyo", "2026-10-11", "Junior Othello Class Information Session", "Yashio Community Plaza", "https://example.com/7", "2026-09-12"),
        StandardRealEvent("13", "Tokyo", "2026-10-11", "Tokyo Toy Festival 2026 (Othello Day)", "Tokyo Toy Museum", "https://example.com/8", "2026-09-12"),
        StandardRealEvent("13", "Tokyo", "2026-10-18", "Othello, Humans, and the Future of AI", "Rikkyo University Ikebukuro Campus", "https://example.com/9", "2026-09-19"),
        StandardRealEvent("13", "Tokyo", "2026-11-08", "Family Board Game Day", "Fuchu Civic Activity Center", "https://example.com/10", "2026-10-03"),
        StandardRealEvent("15", "Niigata", "2026-10-31", "Nagaoka KOSEN Festival", "Nagaoka College", "https://example.com/11", "2026-09-19"),
        StandardRealEvent("15", "Niigata", "2026-11-01", "Nagaoka KOSEN Festival", "Nagaoka College", "https://example.com/12", "2026-09-19"),
        StandardRealEvent("33", "Okayama", "2026-10-04", "Human Rights Film Day & Giant Othello", "Maniwa Central Library", "https://example.com/13", "2026-09-19"),
        StandardRealEvent("33", "Okayama", "2026-11-22", "THROW THE SPARK 2026 Okayama Final", "Noh Theater Hall", "https://example.com/14", "2026-09-20"),
        StandardRealEvent("34", "Hiroshima", "2026-10-11", "THROW THE SPARK 2026 Fukuyama Qualifier", "iti SETOUCHI", "https://example.com/15", "2026-09-20"),
        StandardRealEvent("40", "Fukuoka", "2026-11-22", "Recreation & Wellness Class", "Maitomu Munakata", "https://example.com/16", "2026-09-19"),
    )

    private fun awaitVisibleRealEventsScreen(): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 12_000L
        do {
            composeRule.waitForIdle()
            val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val backgroundPixel = frame.getPixel(5, frame.height / 2)
            val brightness = (
                android.graphics.Color.red(backgroundPixel) +
                    android.graphics.Color.green(backgroundPixel) +
                    android.graphics.Color.blue(backgroundPixel)
                ) / 3
            if (brightness < 180) return frame
            frame.recycle()
            Thread.sleep(250)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("StandardRealEventScreen did not render a dark background before capture")
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
