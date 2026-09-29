package com.example.othello

import com.example.othello.network.peopleplay.AvatarId
import com.example.othello.network.peopleplay.PlayerColor
import com.example.othello.network.peopleplay.RoomPhase
import com.example.othello.network.peopleplay.TimeControl

internal fun PeoplePlayRoomUiState.toPresentationState(): PeoplePlayRoomPresentationState {
    val room = snapshot
    val playing = room?.phase == RoomPhase.PLAYING
    val leftAvatar = if (playing) room?.players?.black?.avatarId else room?.seats?.a?.avatarId
    val rightAvatar = if (playing) room?.players?.white?.avatarId else room?.seats?.b?.avatarId
    val watcherIcons = room?.spectatorAvatarPreview.orEmpty().map(AvatarId::toPeoplePlayAvatarDrawable)
    val titleAndMinutes = room?.timeControl?.roomTitleAndMinutes()
    val actionLabel = when {
        !playing && canTakeSeat -> R.string.people_room_take_seat
        !playing && canLeaveSeat -> R.string.people_room_leave_seat
        else -> R.string.people_room_leave_seat
    }
    return PeoplePlayRoomPresentationState(
        roomNameRes = titleAndMinutes?.first ?: R.string.play_lobby_room_10,
        leftPlayerAvatarRes = leftAvatar?.toPeoplePlayAvatarDrawable() ?: R.drawable.play_lobby_icon_adult_man,
        rightPlayerAvatarRes = rightAvatar?.toPeoplePlayAvatarDrawable() ?: R.drawable.play_lobby_icon_adult_woman,
        minutesPerPlayer = titleAndMinutes?.second ?: 10,
        watcherAvatarRes = watcherIcons,
        additionalWatcherCount = room?.let { (it.spectatorCount - it.spectatorAvatarPreview.size).coerceAtLeast(0) } ?: 0,
        boardCells = room?.board ?: List(64) { 0 },
        legalMoves = legalMoves,
        canPlay = canMove,
        hasColorAssignment = playing,
        showSeatAction = room?.phase == RoomPhase.WAITING && (canTakeSeat || canLeaveSeat),
        seatActionLabelRes = actionLabel,
        leftPlayerDescriptionRes = if (playing) R.string.people_room_black_player else R.string.people_room_seat_a,
        rightPlayerDescriptionRes = if (playing) R.string.people_room_white_player else R.string.people_room_seat_b,
    )
}

internal fun TimeControl.roomTitleAndMinutes(): Pair<Int, Int> = when (this) {
    TimeControl.TWENTY_MINUTES -> R.string.play_lobby_room_20 to 20
    TimeControl.FIFTEEN_MINUTES -> R.string.play_lobby_room_15 to 15
    TimeControl.TEN_MINUTES -> R.string.play_lobby_room_10 to 10
    TimeControl.FIVE_MINUTES -> R.string.play_lobby_room_5 to 5
    TimeControl.THREE_MINUTES -> R.string.play_lobby_room_3 to 3
}

internal fun PlayerColor?.isBlack(): Boolean = this == PlayerColor.BLACK
