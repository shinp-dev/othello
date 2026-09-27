package com.example.othello

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LobbyInk = Color(0xFF071822)
private val LobbyEmerald = Color(0xFF0A463B)
private val LobbyGold = Color(0xFFE8C76D)
private val LobbyIvory = Color(0xFFFFF6DF)
private const val LobbyTableFrameAspectRatio = 1536f / 699f

/** Local-only sample lobby. Room actions intentionally do not start network or game flows yet. */
internal data class PeoplePlayLobbyRoom(
    @StringRes val nameRes: Int,
    @DrawableRes val timeIconRes: Int,
    val isPlaying: Boolean,
    val participantIconRes: List<Int>,
    val watchers: Int,
) {
    val seatedPlayers: Int get() = participantIconRes.size
}

internal fun samplePeoplePlayLobbyRooms() = listOf(
    PeoplePlayLobbyRoom(R.string.play_lobby_room_20, R.drawable.play_lobby_time_20, false, listOf(R.drawable.play_lobby_icon_adult_man), 2),
    PeoplePlayLobbyRoom(R.string.play_lobby_room_15, R.drawable.play_lobby_time_15, true, listOf(R.drawable.play_lobby_icon_adult_woman, R.drawable.play_lobby_icon_girl), 4),
    PeoplePlayLobbyRoom(R.string.play_lobby_room_10, R.drawable.play_lobby_time_10, false, listOf(R.drawable.play_lobby_icon_staff), 1),
    PeoplePlayLobbyRoom(R.string.play_lobby_room_5, R.drawable.play_lobby_time_5, false, listOf(R.drawable.play_lobby_icon_boy), 3),
    PeoplePlayLobbyRoom(R.string.play_lobby_room_3, R.drawable.play_lobby_time_3, true, listOf(R.drawable.play_lobby_icon_book, R.drawable.play_lobby_icon_adult_man), 5),
)

@Composable
internal fun PeoplePlayLobbyScreen(onBack: () -> Unit) {
    val rooms = samplePeoplePlayLobbyRooms()

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.play_lobby_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(LobbyInk.copy(alpha = 0.82f))
                        .border(1.dp, LobbyGold, RoundedCornerShape(24.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                ) {
                    Text("‹  ${appString(R.string.back)}", color = LobbyIvory, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = appString(R.string.people_play_title),
                    color = LobbyIvory,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.testTag("play_lobby_title"),
                )
                Text(
                    text = appString(R.string.play_lobby_supporting),
                    color = LobbyIvory,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.testTag("play_lobby_supporting"),
                )
            }

            Box(Modifier.fillMaxWidth().height(58.dp).padding(bottom = 6.dp)) {
                LobbyAction(
                    modifier = Modifier.fillMaxWidth(),
                    iconRes = R.drawable.play_lobby_round_table,
                    labelRes = R.string.play_lobby_create_table,
                    onClick = { /* Table creation is not part of this UI-only screen. */ },
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("play_lobby_room_list"),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rooms) { room ->
                    LobbyRoomCard(room = room)
                }
            }
        }
    }
}

@Composable
private fun LobbyAction(
    modifier: Modifier,
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(LobbyEmerald.copy(alpha = 0.92f))
            .border(1.2.dp, LobbyGold, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(46.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(5.dp))
        Text(
            text = appString(labelRes),
            color = LobbyIvory,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("play_lobby_action_$labelRes"),
        )
    }
}

@Composable
private fun LobbyRoomCard(room: PeoplePlayLobbyRoom) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(LobbyTableFrameAspectRatio)
            .testTag("play_lobby_room_${room.nameRes}"),
    ) {
        Image(
            painter = painterResource(R.drawable.play_lobby_table_frame_wide),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(room.timeIconRes),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(3.dp))

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = appString(room.nameRes),
                    color = LobbyIvory,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("play_lobby_room_name_${room.nameRes}"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        room.participantIconRes.forEachIndexed { index, iconRes ->
                            Image(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(30.dp).testTag("play_lobby_participant_${room.nameRes}_$index"),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = appString(R.string.play_lobby_seated, room.seatedPlayers),
                        color = LobbyIvory,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("play_lobby_seated_${room.nameRes}"),
                    )
                }
                Text(
                    text = appString(R.string.play_lobby_watching, room.watchers),
                    color = LobbyIvory,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("play_lobby_watching_${room.nameRes}"),
                )
            }

            Spacer(Modifier.width(4.dp))
            Column(
                modifier = Modifier.width(62.dp).fillMaxHeight().padding(vertical = 1.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .width(62.dp)
                        .height(27.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (room.isPlaying) Color(0xFF752D2A).copy(alpha = 0.9f) else LobbyEmerald.copy(alpha = 0.94f))
                        .border(1.dp, LobbyGold, RoundedCornerShape(16.dp))
                        .testTag("play_lobby_status_${room.nameRes}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = appString(if (room.isPlaying) R.string.play_lobby_status_playing else R.string.play_lobby_status_open),
                        color = LobbyIvory,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                Row(
                    modifier = Modifier
                        .width(62.dp)
                        .height(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(LobbyEmerald.copy(alpha = 0.92f))
                        .border(1.dp, LobbyGold, RoundedCornerShape(18.dp))
                        .clickable { /* Joining a room is not part of this UI-only screen. */ }
                        .testTag("play_lobby_enter_${room.nameRes}"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(appString(R.string.play_lobby_enter), color = LobbyIvory, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("›", color = LobbyGold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
