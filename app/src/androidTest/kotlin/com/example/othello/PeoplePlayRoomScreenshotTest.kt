package com.example.othello

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.designsystem.OthelloTheme
import com.example.othello.game.GameState
import com.example.othello.network.peopleplay.PeoplePlayRoomSnapshot
import com.example.othello.network.peopleplay.PeoplePlaySeats
import com.example.othello.network.peopleplay.PlayerColor
import com.example.othello.network.peopleplay.RoomPhase
import com.example.othello.network.peopleplay.TimeControl
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PeoplePlayRoomScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renderRoomAtConfiguredWidthAndLocale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val width = checkNotNull(InstrumentationRegistry.getArguments().getString("captureWidth")).toInt()
        val language = InstrumentationRegistry.getArguments().getString("captureLanguage") ?: "ja"
        require(width in setOf(320, 360, 390)) { "Unsupported room screenshot width: $width" }
        require(language in setOf("ja", "en")) { "Unsupported room screenshot language: $language" }

        val locale = if (language == "ja") Locale.JAPAN else Locale.US
        val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) }
        val localizedContext = context.createConfigurationContext(configuration)
        val presentationState = mutableStateOf(PeoplePlayRoomPresentationState())
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration) {
                OthelloTheme {
                    PeoplePlayRoomScreen(
                        state = presentationState.value,
                        onBack = {},
                        onLeaveSeat = {},
                        onExit = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("people_room_title")
            .assertIsDisplayed()
            .assertTextEquals(localizedContext.getString(R.string.play_lobby_room_10))
        composeRule.onNodeWithTag("people_room_player_left").assertIsDisplayed()
        composeRule.onNodeWithTag("people_room_player_right").assertIsDisplayed()
        composeRule.onNodeWithTag("people_room_disc_black").assertIsDisplayed()
        composeRule.onNodeWithTag("people_room_disc_white").assertIsDisplayed()
        composeRule.onNodeWithTag("people_room_time").assertIsDisplayed()
        composeRule.onNodeWithText(localizedContext.getString(R.string.people_room_time_minutes, 10)).assertIsDisplayed()
        repeat(3) { index -> composeRule.onNodeWithTag("people_room_watcher_$index").assertIsDisplayed() }
        composeRule.onNodeWithTag("people_room_watcher_count")
            .assertIsDisplayed()
            .assertTextEquals("+3")

        listOf(
            "people_room_black_2_4", "people_room_black_3_3", "people_room_black_4_4",
            "people_room_black_4_5", "people_room_black_4_8", "people_room_black_5_6",
            "people_room_black_6_4", "people_room_black_6_5",
        ).forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        listOf(
            "people_room_white_3_4", "people_room_white_3_5", "people_room_white_4_3",
            "people_room_white_4_6", "people_room_white_5_4", "people_room_white_5_5",
            "people_room_white_6_6", "people_room_white_7_7",
        ).forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        listOf(
            "people_room_move_2_3", "people_room_move_3_6", "people_room_move_4_2",
            "people_room_move_5_7", "people_room_move_7_4", "people_room_move_7_6",
        ).forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }

        listOf(R.string.people_room_leave_seat, R.string.people_room_exit).forEach { id ->
            composeRule.onNodeWithText(localizedContext.getString(id)).assertIsDisplayed()
        }
        listOf("people_room_back", "people_room_leave_seat", "people_room_exit").forEach {
            composeRule.onNodeWithTag(it).assertHasClickAction()
        }
        composeRule.onNodeWithTag("people_room_cell_1_1").assertHasClickAction()
        composeRule.onNodeWithTag("people_room_board").assertIsDisplayed()
        for (row in 1..8) {
            for (column in 1..8) {
                composeRule.onNodeWithTag("people_room_cell_${row}_$column").assertHasClickAction()
            }
        }

        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertEquals("Compose root width for ${width}dp emulator", width.toFloat(), rootWidth, 1f)
        val boardBounds = composeRule.onNodeWithTag("people_room_board").fetchSemanticsNode().boundsInRoot
        assertEquals("Room board must start at the left edge", 0f, boardBounds.left, 1f)
        assertEquals("Room board must use the full screen width", rootWidth, boardBounds.right, 1f)
        assertEquals("Room board must remain square", boardBounds.width, boardBounds.height, 1f)
        val leaveSeatBounds = composeRule.onNodeWithTag("people_room_leave_seat").fetchSemanticsNode().boundsInRoot
        assertTrue("Room actions must remain below the enlarged board: board=$boardBounds action=$leaveSeatBounds", leaveSeatBounds.top >= boardBounds.bottom)
        listOf(
            "people_room_title_banner", "people_room_info_bar", "people_room_board",
            "people_room_leave_seat", "people_room_exit",
        ).forEach { assertWithinScreen(it, rootWidth) }
        assertContained("people_room_title", "people_room_title_banner")
        assertContained("people_room_time_label", "people_room_time")
        assertContained("people_room_leave_seat_label", "people_room_leave_seat")
        assertContained("people_room_exit_label", "people_room_exit")

        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        val renderedRoot = composeRule.onRoot().captureToImage()
        val pixels = renderedRoot.toPixelMap()
        val sampledColors = buildSet {
            for (y in 0 until renderedRoot.height step 8) {
                for (x in 0 until renderedRoot.width step 8) add(pixels[x, y])
            }
        }
        assertTrue("Room screenshot appears blank at ${width}dp / $language", sampledColors.size > 64)
        val bitmap = renderedRoot.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        assertEquals("Screenshot pixel width for ${width}dp emulator", width, bitmap.width)
        saveScreenshot(context, bitmap, "people-play-room-$language-${width}dp.png")
        bitmap.recycle()

        val originalBounds = listOf(
            "people_room_info_bar", "people_room_time", "people_room_board", "people_room_exit",
        ).associateWith { composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val waitingSnapshot = PeoplePlayRoomSnapshot(
            roomId = "room-empty-seats",
            phase = RoomPhase.WAITING,
            timeControl = TimeControl.TEN_MINUTES,
            seats = PeoplePlaySeats(null, null),
            players = null,
            wirePly = 0,
            board = GameState().board.toWireBoard(),
            move = null,
            nextTurn = PlayerColor.BLACK,
            terminalCandidate = false,
            resultCheckPly = null,
            spectatorCount = 0,
            spectatorAvatarPreview = emptyList(),
        )
        composeRule.runOnIdle {
            presentationState.value = PeoplePlayRoomUiState(
                status = PeoplePlayConnectionStatus.CONNECTED,
                roomId = waitingSnapshot.roomId,
                memberId = "member-watcher",
                snapshot = waitingSnapshot,
                isSpectator = true,
            ).toPresentationState()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("people_room_player_left").assertCountEquals(0)
        composeRule.onAllNodesWithTag("people_room_player_right").assertCountEquals(0)
        composeRule.onNodeWithText(localizedContext.getString(R.string.people_room_take_seat)).assertIsDisplayed()
        composeRule.onNodeWithTag("people_room_board").assertIsDisplayed()
        originalBounds.forEach { (tag, bounds) ->
            assertEquals("$tag layout changed when waiting seats are empty", bounds, composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        }
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        val waitingBitmap = composeRule.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        saveScreenshot(context, waitingBitmap, "people-play-room-waiting-empty-$language-${width}dp.png")
        waitingBitmap.recycle()
    }

    private fun assertWithinScreen(tag: String, screenWidth: Float) {
        val bounds = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag extends past the left edge: $bounds", bounds.left >= 0f)
        assertTrue("$tag extends past the right edge: $bounds", bounds.right <= screenWidth)
    }

    private fun assertContained(childTag: String, parentTag: String) {
        val child = composeRule.onNodeWithTag(childTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val parent = composeRule.onNodeWithTag(parentTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$childTag exceeds $parentTag horizontally: child=$child parent=$parent", child.left >= parent.left && child.right <= parent.right)
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
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Failed to encode room screenshot: $name" }
        }
    }
}
