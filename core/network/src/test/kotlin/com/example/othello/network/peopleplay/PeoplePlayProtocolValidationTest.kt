package com.example.othello.network.peopleplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PeoplePlayProtocolValidationTest {
    private val board = List(64) { 0 }
    private val a = PeoplePlayParticipantSummary("member-a", "Sample A", AvatarId.ADULT_MAN)
    private val b = PeoplePlayParticipantSummary("member-b", "Sample B", AvatarId.ADULT_WOMAN)
    private val initial = PeoplePlayRoomSnapshot(
        roomId = "room", phase = RoomPhase.WAITING, timeControl = TimeControl.FIVE_MINUTES,
        seats = PeoplePlaySeats(a, null), players = null, wirePly = 0, board = board, move = null,
        nextTurn = PlayerColor.BLACK, terminalCandidate = false, resultCheckPly = null,
        spectatorCount = 0, spectatorAvatarPreview = emptyList(),
    )
    private fun valid(message: PeoplePlayMessage) = PeoplePlayProtocolValidator.validate(message)
    private fun invalid(message: PeoplePlayMessage) { assertFailsWith<IllegalArgumentException> { valid(message) } }

    @Test fun timeControlsAreExactMilliseconds() {
        assertEquals(listOf(1_200_000L, 900_000L, 600_000L, 300_000L, 180_000L), TimeControl.entries.map { it.initialMillis })
    }

    @Test fun boardSizeCellsAndMoveBounds() {
        val move = MoveSnapshot("room", 1, PeoplePlayMove(0, 7), board, PlayerColor.WHITE, false)
        valid(move)
        listOf(board.dropLast(1), board + 0, board.toMutableList().apply { this[0] = -1 }, board.toMutableList().apply { this[0] = 3 })
            .forEach { invalid(move.copy(board = it)) }
        listOf(PeoplePlayMove(-1, 0), PeoplePlayMove(8, 0), PeoplePlayMove(0, -1), PeoplePlayMove(0, 8))
            .forEach { invalid(move.copy(move = it)) }
        invalid(initial.copy(board = board.dropLast(1)))
    }

    @Test fun wirePlyIsPlacementCountAndHasNoCorePlyMapper() {
        valid(initial)
        invalid(initial.copy(wirePly = -1))
        invalid(initial.copy(move = PeoplePlayMove(2, 3)))
        val played = initial.copy(wirePly = 1, move = PeoplePlayMove(2, 3), phase = RoomPhase.PLAYING, players = PeoplePlayPlayers(a, b))
        valid(played)
        invalid(played.copy(move = null))
        invalid(MoveSnapshot("room", 0, PeoplePlayMove(2, 3), board, PlayerColor.WHITE, false))
        invalid(Desync("room", 0, DesyncReason.SNAPSHOT_MISMATCH))
        val protocolClasses = listOf(MoveSnapshot::class.java, PeoplePlayRoomSnapshot::class.java, ResultReport::class.java, GameOver::class.java)
        assertFalse(protocolClasses.flatMap { it.methods.toList() }.flatMap { it.parameterTypes.toList() }.any { it.name == "com.example.othello.game.GameState" })
        assertEquals("wirePly", MoveSnapshot::class.java.declaredFields.first { it.name == "wirePly" }.name)
    }

    @Test fun snapshotPhasePlayersTurnAndResultCheckAreConsistent() {
        valid(initial)
        invalid(initial.copy(players = PeoplePlayPlayers(a, b)))
        invalid(initial.copy(wirePly = 1, move = PeoplePlayMove(2, 3)))
        invalid(initial.copy(resultCheckPly = 0))
        invalid(initial.copy(seats = PeoplePlaySeats(a, a)))
        invalid(initial.copy(phase = RoomPhase.PLAYING))
        invalid(initial.copy(phase = RoomPhase.CLOSED))
        invalid(initial.copy(phase = RoomPhase.PLAYING, players = PeoplePlayPlayers(a, a)))
        val playing = initial.copy(phase = RoomPhase.PLAYING, players = PeoplePlayPlayers(a, b), wirePly = 1, move = PeoplePlayMove(2, 3))
        valid(playing)
        valid(playing.copy(terminalCandidate = true, nextTurn = null, resultCheckPly = 1))
        invalid(playing.copy(terminalCandidate = true, nextTurn = PlayerColor.BLACK))
        invalid(playing.copy(terminalCandidate = false, nextTurn = null))
        invalid(playing.copy(resultCheckPly = 2))
        invalid(initial.copy(nextTurn = PlayerColor.WHITE))
    }

    @Test fun spectatorPreviewZeroOneThreeAndLimits() {
        valid(initial)
        valid(initial.copy(spectatorCount = 1, spectatorAvatarPreview = listOf(AvatarId.BOY)))
        valid(initial.copy(spectatorCount = 3, spectatorAvatarPreview = listOf(AvatarId.BOY, AvatarId.GIRL, AvatarId.MAGIC_BOOK)))
        invalid(initial.copy(spectatorCount = -1))
        invalid(initial.copy(spectatorCount = 4, spectatorAvatarPreview = listOf(AvatarId.BOY, AvatarId.GIRL, AvatarId.MAGIC_BOOK, AvatarId.ADULT_MAN)))
        invalid(initial.copy(spectatorCount = 1, spectatorAvatarPreview = listOf(AvatarId.BOY, AvatarId.GIRL)))
    }

    @Test fun allAndOnlyAllowedGameResultCombinations() {
        val allowed = setOf(
            PeoplePlayGameResult(FinishReason.NORMAL, Outcome.BLACK_WIN, PlayerColor.BLACK),
            PeoplePlayGameResult(FinishReason.NORMAL, Outcome.WHITE_WIN, PlayerColor.WHITE),
            PeoplePlayGameResult(FinishReason.NORMAL, Outcome.DRAW, null),
            PeoplePlayGameResult(FinishReason.TIMEOUT, Outcome.BLACK_WIN, PlayerColor.BLACK),
            PeoplePlayGameResult(FinishReason.TIMEOUT, Outcome.WHITE_WIN, PlayerColor.WHITE),
            PeoplePlayGameResult(FinishReason.DISCONNECT, Outcome.BLACK_WIN, PlayerColor.BLACK),
            PeoplePlayGameResult(FinishReason.DISCONNECT, Outcome.WHITE_WIN, PlayerColor.WHITE),
            PeoplePlayGameResult(FinishReason.DESYNC, Outcome.NO_CONTEST, null),
        )
        for (reason in FinishReason.entries) for (outcome in Outcome.entries) for (winner in listOf(null, PlayerColor.BLACK, PlayerColor.WHITE)) {
            val result = PeoplePlayGameResult(reason, outcome, winner)
            if (result in allowed) PeoplePlayProtocolValidator.validate(result)
            else assertFailsWith<IllegalArgumentException> { PeoplePlayProtocolValidator.validate(result) }
        }
        assertEquals(8, allowed.size)
    }

    @Test fun finalSnapshotIsPreservedAndMustMatchEnvelope() {
        val playing = initial.copy(phase = RoomPhase.PLAYING, players = PeoplePlayPlayers(a, b))
        val end = GameOver("room", 0, FinishReason.DESYNC, Outcome.NO_CONTEST, null, 1000L, playing)
        valid(end)
        invalid(end.copy(finalSnapshot = playing.copy(roomId = "other")))
        invalid(end.copy(finalSnapshot = initial))
        invalid(end.copy(wirePly = 1))
        invalid(end.copy(outcome = Outcome.DRAW))
    }
}
