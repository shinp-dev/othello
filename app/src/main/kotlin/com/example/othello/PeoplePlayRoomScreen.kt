package com.example.othello

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RoomIvory = Color(0xFFFFF4DB)
private val RoomGold = Color(0xFFFFD97B)
private const val RoomReferenceWidth = 864f
private const val RoomReferenceHeight = 1536f
private const val RoomReferenceAspect = RoomReferenceHeight / RoomReferenceWidth

/** The room screen's static UI model. Game/network state is intentionally out of scope. */
internal data class PeoplePlayRoomUiState(
    @StringRes val roomNameRes: Int = R.string.play_lobby_room_10,
    @DrawableRes val leftPlayerAvatarRes: Int = R.drawable.play_lobby_icon_adult_man,
    @DrawableRes val rightPlayerAvatarRes: Int = R.drawable.play_lobby_icon_adult_woman,
    val minutesPerPlayer: Int = 10,
    val watcherAvatarRes: List<Int> = listOf(
        R.drawable.play_lobby_icon_adult_woman,
        R.drawable.play_lobby_icon_girl,
        R.drawable.play_lobby_icon_staff,
    ),
    val additionalWatcherCount: Int = 3,
)

/**
 * Full-screen, local-only people-play room preview. The artwork is placed as supplied;
 * only text, player portraits, discs, and legal-move markers are layered above it.
 */
@Composable
internal fun PeoplePlayRoomScreen(
    state: PeoplePlayRoomUiState = PeoplePlayRoomUiState(),
    onBack: () -> Unit,
    onLeaveSeat: () -> Unit = {},
    onExit: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("people_room_screen")) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val designWidth = minOf(screenWidth, screenHeight / RoomReferenceAspect)
        val designHeight = designWidth * RoomReferenceAspect
        val designLeft = (screenWidth - designWidth) / 2f
        val designTop = (screenHeight - designHeight) / 2f
        val boardSize = designWidth * 0.94f

        Image(
            painter = painterResource(R.drawable.people_room_background_full),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().testTag("people_room_background"),
            contentScale = ContentScale.Crop,
        )

        val backHitSize = maxOf(48.dp, designWidth * 0.12f)
        Box(
            modifier = Modifier
                .offset(
                    x = designLeft + designWidth * 0.023f,
                    y = designTop + designHeight * 0.041f,
                )
                .size(backHitSize)
                .clickable(onClick = onBack)
                .testTag("people_room_back"),
            contentAlignment = Alignment.TopStart,
        ) {
            Image(
                painter = painterResource(R.drawable.people_room_back_button),
                contentDescription = appString(R.string.people_room_back_description),
                modifier = Modifier.size(designWidth * 0.105f),
                contentScale = ContentScale.Fit,
            )
        }

        val titleWidth = designWidth * 0.51f
        Box(
            modifier = Modifier
                .offset(
                    x = designLeft + designWidth * 0.245f,
                    y = designTop + designHeight * 0.061f,
                )
                .size(width = titleWidth, height = designHeight * 0.082f)
                .testTag("people_room_title_banner"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.people_room_title_banner),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Text(
                text = appString(state.roomNameRes),
                color = RoomIvory,
                fontSize = (designWidth.value * 0.048f).sp,
                lineHeight = (designWidth.value * 0.057f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("people_room_title"),
            )
        }

        RoomPlayerInfoBar(
            state = state,
            designWidth = designWidth,
            designHeight = designHeight,
            designLeft = designLeft,
            designTop = designTop,
        )

        PeoplePlayRoomBoard(
            modifier = Modifier
                .offset(
                    x = designLeft + designWidth * 0.03f,
                    y = designTop + designHeight * 0.246f,
                )
                .size(boardSize)
                .testTag("people_room_board"),
        )

        RoomAction(
            imageRes = R.drawable.people_room_action_green,
            labelRes = R.string.people_room_leave_seat,
            icon = { modifier ->
                Icon(
                    imageVector = Icons.Filled.EventSeat,
                    contentDescription = null,
                    modifier = modifier,
                    tint = RoomIvory,
                )
            },
            designWidth = designWidth,
            designHeight = designHeight,
            designLeft = designLeft,
            designTop = designTop,
            xFraction = 0.04f,
            testTag = "people_room_leave_seat",
            onClick = onLeaveSeat,
        )
        RoomAction(
            imageRes = R.drawable.people_room_action_red,
            labelRes = R.string.people_room_exit,
            icon = { modifier ->
                Icon(
                    imageVector = Icons.Filled.Logout,
                    contentDescription = null,
                    modifier = modifier,
                    tint = RoomIvory,
                )
            },
            designWidth = designWidth,
            designHeight = designHeight,
            designLeft = designLeft,
            designTop = designTop,
            xFraction = 0.56f,
            testTag = "people_room_exit",
            onClick = onExit,
        )
    }
}

@Composable
private fun RoomPlayerInfoBar(
    state: PeoplePlayRoomUiState,
    designWidth: Dp,
    designHeight: Dp,
    designLeft: Dp,
    designTop: Dp,
) {
    val infoWidth = designWidth * 0.94f
    BoxWithConstraints(
        modifier = Modifier
            .offset(
                x = designLeft + designWidth * 0.03f,
                y = designTop + designHeight * 0.158f,
            )
            .size(width = infoWidth, height = designHeight * 0.076f)
            .testTag("people_room_info_bar"),
    ) {
        val barWidth = maxWidth
        val barHeight = maxHeight
        Image(
            painter = painterResource(R.drawable.people_room_top_info_bar),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )

        val avatarSize = minOf(barWidth * 0.125f, barHeight * 0.82f)
        RoomInfoImage(
            resId = state.leftPlayerAvatarRes,
            descriptionRes = R.string.people_room_black_player,
            tag = "people_room_player_left",
            centerFraction = 0.064f,
            size = avatarSize,
            barWidth = barWidth,
            barHeight = barHeight,
            circular = true,
        )
        RoomInfoImage(
            resId = R.drawable.people_room_black_disc,
            descriptionRes = R.string.people_room_black_disc_description,
            tag = "people_room_disc_black",
            centerFraction = 0.191f,
            size = minOf(barWidth * 0.064f, barHeight * 0.50f),
            barWidth = barWidth,
            barHeight = barHeight,
        )

        val timeTextSize = (designWidth.value * 0.041f).sp
        Row(
            modifier = Modifier
                .offset(
                    x = barWidth * 0.258f,
                    y = barHeight * 0.23f,
                )
                .size(height = barHeight * 0.54f, width = barWidth * 0.26f)
                .testTag("people_room_time"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(minOf(16.dp, barHeight * 0.23f)),
                tint = RoomIvory,
            )
            Text(
                text = appString(R.string.people_room_time_minutes, state.minutesPerPlayer),
                color = RoomIvory,
                fontSize = timeTextSize,
                lineHeight = timeTextSize * 1.1f,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("people_room_time_label"),
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(minOf(16.dp, barHeight * 0.23f)),
                tint = RoomGold,
            )
        }

        state.watcherAvatarRes.take(3).forEachIndexed { index, avatarRes ->
            val size = minOf(barWidth * 0.060f, barHeight * 0.42f)
            RoomInfoImage(
                resId = avatarRes,
                descriptionRes = R.string.people_room_watcher,
                tag = "people_room_watcher_$index",
                centerFraction = 0.548f + index * 0.061f,
                size = size,
                barWidth = barWidth,
                barHeight = barHeight,
                circular = true,
            )
        }

        val countSize = minOf(barWidth * 0.066f, barHeight * 0.45f)
        Box(
            modifier = Modifier
                .offset(
                    x = barWidth * 0.734f - countSize / 2f,
                    y = barHeight * 0.5f - countSize / 2f,
                )
                .size(countSize),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+${state.additionalWatcherCount}",
                color = RoomIvory,
                fontSize = (designWidth.value * 0.039f).sp,
                lineHeight = (designWidth.value * 0.042f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("people_room_watcher_count"),
            )
        }

        RoomInfoImage(
            resId = R.drawable.people_room_white_disc,
            descriptionRes = R.string.people_room_white_disc_description,
            tag = "people_room_disc_white",
            centerFraction = 0.809f,
            size = minOf(barWidth * 0.064f, barHeight * 0.50f),
            barWidth = barWidth,
            barHeight = barHeight,
        )
        RoomInfoImage(
            resId = state.rightPlayerAvatarRes,
            descriptionRes = R.string.people_room_white_player,
            tag = "people_room_player_right",
            centerFraction = 0.936f,
            size = avatarSize,
            barWidth = barWidth,
            barHeight = barHeight,
            circular = true,
        )
    }
}

@Composable
private fun RoomInfoImage(
    @DrawableRes resId: Int,
    @StringRes descriptionRes: Int,
    tag: String,
    centerFraction: Float,
    size: Dp,
    barWidth: Dp,
    barHeight: Dp,
    circular: Boolean = false,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = appString(descriptionRes),
        modifier = Modifier
            .offset(
                x = barWidth * centerFraction - size / 2f,
                y = barHeight / 2f - size / 2f,
            )
            .size(size)
            .then(if (circular) Modifier.clip(CircleShape) else Modifier)
            .testTag(tag),
        contentScale = ContentScale.Fit,
    )
}

private const val GridLeft = 0.1515f
private const val GridRight = 0.8509f
private const val GridTop = 0.1135f
private const val GridBottom = 0.7974f

private const val RoomReferenceBoard = """
........
..MB....
..BWWM..
.MWBBW.B
...WWBM.
...BBW..
...M.MW.
........
"""

/** Uses one normalized grid rectangle for every cell, stone, and marker. */
@Composable
internal fun PeoplePlayRoomBoard(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        Image(
            painter = painterResource(R.drawable.people_room_board),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().testTag("people_room_board_art"),
            contentScale = ContentScale.Fit,
        )

        val gridLeft = maxWidth * GridLeft
        val gridTop = maxHeight * GridTop
        val cellWidth = maxWidth * (GridRight - GridLeft) / 8f
        val cellHeight = maxHeight * (GridBottom - GridTop) / 8f
        val markerSize = minOf(cellWidth, cellHeight) * 0.46f
        val discSize = minOf(cellWidth, cellHeight) * 0.68f
        val rows = RoomReferenceBoard.trimIndent().lines()

        for (row in 0 until 8) {
            for (column in 0 until 8) {
                val cellX = gridLeft + cellWidth * column.toFloat()
                val cellY = gridTop + cellHeight * row.toFloat()
                Box(
                    modifier = Modifier
                        .offset(x = cellX, y = cellY)
                        .size(width = cellWidth, height = cellHeight)
                        .clickable(onClick = {})
                        .testTag("people_room_cell_${row + 1}_${column + 1}"),
                )

                val piece = rows[row][column]
                if (piece != '.') {
                    val imageSize = if (piece == 'M') markerSize else discSize
                    val resource = when (piece) {
                        'B' -> R.drawable.people_room_black_disc
                        'W' -> R.drawable.people_room_white_disc
                        else -> R.drawable.people_room_move_marker
                    }
                    val kind = when (piece) {
                        'B' -> "black"
                        'W' -> "white"
                        else -> "move"
                    }
                    Image(
                        painter = painterResource(resource),
                        contentDescription = null,
                        modifier = Modifier
                            .offset(
                                x = cellX + (cellWidth - imageSize) / 2f,
                                y = cellY + (cellHeight - imageSize) / 2f,
                            )
                            .size(imageSize)
                            .testTag("people_room_${kind}_${row + 1}_${column + 1}"),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomAction(
    @DrawableRes imageRes: Int,
    @StringRes labelRes: Int,
    icon: @Composable (Modifier) -> Unit,
    designWidth: Dp,
    designHeight: Dp,
    designLeft: Dp,
    designTop: Dp,
    xFraction: Float,
    testTag: String,
    onClick: () -> Unit,
) {
    val buttonWidth = designWidth * 0.40f
    Box(
        modifier = Modifier
            .offset(
                x = designLeft + designWidth * xFraction,
                y = designTop + designHeight * 0.842f,
            )
            .size(width = buttonWidth, height = designHeight * 0.070f)
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon(Modifier.size(minOf(24.dp, designWidth * 0.07f)))
            Spacer(Modifier.width(8.dp))
            Text(
                text = appString(labelRes),
                color = RoomIvory,
                fontSize = (designWidth.value * 0.043f).sp,
                lineHeight = (designWidth.value * 0.050f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("${testTag}_label"),
            )
        }
    }
}
