package com.example.othello

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PeoplePlayLobbyScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renderLobbyAtConfiguredWidthAndLocale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val width = checkNotNull(InstrumentationRegistry.getArguments().getString("captureWidth")).toInt()
        val language = InstrumentationRegistry.getArguments().getString("captureLanguage") ?: "ja"
        require(width in setOf(320, 360, 390)) { "Unsupported lobby screenshot width: $width" }
        require(language in setOf("ja", "en")) { "Unsupported lobby screenshot language: $language" }

        val locale = if (language == "ja") Locale.JAPAN else Locale.US
        val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localizedContext = context.createConfigurationContext(configuration)
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration) {
                OthelloTheme { PeoplePlayLobbyScreen(onBack = {}) }
            }
        }

        val labels = listOf(
            R.string.people_play_title,
            R.string.play_lobby_table_list,
            R.string.play_lobby_create_table,
        )
        labels.forEach { id -> composeRule.onNodeWithText(localizedContext.getString(id)).assertExists() }
        composeRule.onNodeWithText(localizedContext.getString(R.string.play_lobby_create_table)).assertHasClickAction()

        val roomList = composeRule.onNodeWithTag("play_lobby_room_list")
        val roomIds = listOf(
            R.string.play_lobby_room_20,
            R.string.play_lobby_room_15,
            R.string.play_lobby_room_10,
            R.string.play_lobby_room_5,
            R.string.play_lobby_room_3,
        )
        for ((index, roomId) in roomIds.withIndex()) {
            roomList.performScrollToIndex(index)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("play_lobby_room_$roomId").assertIsDisplayed()
            composeRule.onNodeWithText(localizedContext.getString(roomId)).assertIsDisplayed()
            composeRule.onNodeWithTag("play_lobby_enter_$roomId").assertHasClickAction()
            composeRule.onNodeWithTag("play_lobby_status_$roomId").assertExists()
        }
        roomList.performScrollToIndex(0)
        composeRule.waitForIdle()

        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertEquals("Screenshot pixel width for ${width}dp emulator", width, bitmap.width)
        saveScreenshot(context, bitmap, "people-play-lobby-$language-${width}dp.png")
        bitmap.recycle()
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
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Failed to encode lobby screenshot: $name" }
        }
    }
}
