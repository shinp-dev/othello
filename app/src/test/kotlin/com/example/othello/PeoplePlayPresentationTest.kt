package com.example.othello

import com.example.othello.game.GameState
import com.example.othello.network.peopleplay.AvatarId
import com.example.othello.network.peopleplay.PeoplePlayParticipantSummary
import com.example.othello.network.peopleplay.PeoplePlayPlayers
import com.example.othello.network.peopleplay.PeoplePlayRoomSnapshot
import com.example.othello.network.peopleplay.PeoplePlaySeats
import com.example.othello.network.peopleplay.PlayerColor
import com.example.othello.network.peopleplay.RoomPhase
import com.example.othello.network.peopleplay.TimeControl
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeoplePlayPresentationTest {
    @Test
    fun serverTimeControlsMapToAllExistingLobbyLabels() {
        val expectations = mapOf(
            TimeControl.TWENTY_MINUTES to R.string.play_lobby_room_20,
            TimeControl.FIFTEEN_MINUTES to R.string.play_lobby_room_15,
            TimeControl.TEN_MINUTES to R.string.play_lobby_room_10,
            TimeControl.FIVE_MINUTES to R.string.play_lobby_room_5,
            TimeControl.THREE_MINUTES to R.string.play_lobby_room_3,
        )
        expectations.forEach { (timeControl, resource) ->
            val room = PeoplePlayLobbyEntry(
                roomId = "room-${timeControl.name}",
                timeControl = timeControl,
                phase = RoomPhase.WAITING,
                seats = PeoplePlaySeats(null, null),
                players = null,
                spectatorCount = 0,
            ).toLobbyRoom()
            assertEquals(resource, room.nameRes)
        }
    }

    @Test
    fun waitingUsesSeatOrderWithoutShowingBlackOrWhiteAndOffersOnlyValidAction() {
        val snapshot = PeoplePlayRoomSnapshot(
            roomId = "room-ui",
            phase = RoomPhase.WAITING,
            timeControl = TimeControl.FIVE_MINUTES,
            seats = PeoplePlaySeats(participant("seat-a", AvatarId.MAGIC_WAND), null),
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
        val state = PeoplePlayRoomUiState(
            status = PeoplePlayConnectionStatus.CONNECTED,
            roomId = "room-ui",
            memberId = "watcher",
            snapshot = snapshot,
            isSpectator = true,
        ).toPresentationState()

        assertEquals(R.string.play_lobby_room_5, state.roomNameRes)
        assertEquals(R.drawable.play_lobby_icon_staff, state.leftPlayerAvatarRes)
        assertNull(state.rightPlayerAvatarRes)
        assertFalse(state.hasColorAssignment)
        assertTrue(state.showSeatAction)
        assertEquals(R.string.people_room_take_seat, state.seatActionLabelRes)
        assertFalse(state.canPlay)
    }

    @Test
    fun waitingSeatAvatarsMatchOnlyOccupiedServerSeats() {
        val empty = waitingPresentation(PeoplePlaySeats(null, null))
        assertNull(empty.leftPlayerAvatarRes)
        assertNull(empty.rightPlayerAvatarRes)
        assertFalse(empty.hasColorAssignment)

        val onlyA = waitingPresentation(PeoplePlaySeats(participant("seat-a", AvatarId.MAGIC_WAND), null))
        assertEquals(R.drawable.play_lobby_icon_staff, onlyA.leftPlayerAvatarRes)
        assertNull(onlyA.rightPlayerAvatarRes)

        val onlyB = waitingPresentation(PeoplePlaySeats(null, participant("seat-b", AvatarId.GIRL)))
        assertNull(onlyB.leftPlayerAvatarRes)
        assertEquals(R.drawable.play_lobby_icon_girl, onlyB.rightPlayerAvatarRes)

        val both = waitingPresentation(
            PeoplePlaySeats(participant("seat-a", AvatarId.BOY), participant("seat-b", AvatarId.MAGIC_BOOK)),
        )
        assertEquals(R.drawable.play_lobby_icon_boy, both.leftPlayerAvatarRes)
        assertEquals(R.drawable.play_lobby_icon_book, both.rightPlayerAvatarRes)
    }

    @Test
    fun playingMapsServerColorsAndComputesOnlyRemainingSpectatorCount() {
        val preview = listOf(AvatarId.BOY, AvatarId.GIRL, AvatarId.MAGIC_BOOK)
        val snapshot = PeoplePlayRoomSnapshot(
            roomId = "room-ui",
            phase = RoomPhase.PLAYING,
            timeControl = TimeControl.THREE_MINUTES,
            seats = PeoplePlaySeats(participant("member-a", AvatarId.ADULT_MAN), participant("member-b", AvatarId.ADULT_WOMAN)),
            players = PeoplePlayPlayers(participant("member-b", AvatarId.MAGIC_BOOK), participant("member-a", AvatarId.GIRL)),
            wirePly = 0,
            board = GameState().board.toWireBoard(),
            move = null,
            nextTurn = PlayerColor.BLACK,
            terminalCandidate = false,
            resultCheckPly = null,
            spectatorCount = 5,
            spectatorAvatarPreview = preview,
        )
        val state = PeoplePlayRoomUiState(
            status = PeoplePlayConnectionStatus.CONNECTED,
            roomId = "room-ui",
            memberId = "member-b",
            snapshot = snapshot,
            selfColor = PlayerColor.BLACK,
            isSpectator = false,
            acceptedCoreState = GameState(),
            remainingMillis = 1_000,
        ).toPresentationState()

        assertEquals(R.drawable.play_lobby_icon_book, state.leftPlayerAvatarRes)
        assertEquals(R.drawable.play_lobby_icon_girl, state.rightPlayerAvatarRes)
        assertEquals(listOf(R.drawable.play_lobby_icon_boy, R.drawable.play_lobby_icon_girl, R.drawable.play_lobby_icon_book), state.watcherAvatarRes)
        assertEquals(2, state.additionalWatcherCount)
        assertTrue(state.hasColorAssignment)
        assertFalse(state.showSeatAction)
        assertTrue(state.canPlay)
        assertEquals(GameState().legalMoves, state.legalMoves)
    }

    @Test
    fun terminalCandidateDoesNotExposeLegalMovesEvenBeforeResultCheckArrives() {
        val snapshot = PeoplePlayRoomSnapshot(
            roomId = "room-ui",
            phase = RoomPhase.PLAYING,
            timeControl = TimeControl.TEN_MINUTES,
            seats = PeoplePlaySeats(participant("member-b", AvatarId.BOY), participant("member-a", AvatarId.GIRL)),
            players = PeoplePlayPlayers(participant("member-b", AvatarId.BOY), participant("member-a", AvatarId.GIRL)),
            wirePly = 1,
            board = GameState().board.toWireBoard(),
            move = com.example.othello.network.peopleplay.PeoplePlayMove(2, 3),
            nextTurn = null,
            terminalCandidate = true,
            resultCheckPly = null,
            spectatorCount = 0,
            spectatorAvatarPreview = emptyList(),
        )
        val state = PeoplePlayRoomUiState(
            status = PeoplePlayConnectionStatus.CONNECTED,
            roomId = "room-ui",
            memberId = "member-b",
            snapshot = snapshot,
            selfColor = PlayerColor.BLACK,
            isSpectator = false,
            acceptedCoreState = GameState(),
            remainingMillis = 1_000,
        ).toPresentationState()

        assertFalse(state.canPlay)
        assertTrue(state.legalMoves.isEmpty())
    }

    @Test
    fun avatarIdsMapToExistingArtResources() {
        assertEquals(
            listOf(
                R.drawable.play_lobby_icon_adult_man,
                R.drawable.play_lobby_icon_adult_woman,
                R.drawable.play_lobby_icon_boy,
                R.drawable.play_lobby_icon_girl,
                R.drawable.play_lobby_icon_staff,
                R.drawable.play_lobby_icon_book,
            ),
            AvatarId.entries.map(AvatarId::toPeoplePlayAvatarDrawable),
        )
    }

    private fun participant(id: String, avatar: AvatarId) = PeoplePlayParticipantSummary(id, "Player $id", avatar)

    private fun waitingPresentation(seats: PeoplePlaySeats) = PeoplePlayRoomUiState(
        snapshot = PeoplePlayRoomSnapshot(
            roomId = "room-waiting",
            phase = RoomPhase.WAITING,
            timeControl = TimeControl.TEN_MINUTES,
            seats = seats,
            players = null,
            wirePly = 0,
            board = GameState().board.toWireBoard(),
            move = null,
            nextTurn = PlayerColor.BLACK,
            terminalCandidate = false,
            resultCheckPly = null,
            spectatorCount = 0,
            spectatorAvatarPreview = emptyList(),
        ),
    ).toPresentationState()
}
