package com.example.othello.network.peopleplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PeoplePlayProtocolCodecTest {
    private val board = MutableList(64) { 0 }.apply {
        this[27] = 2; this[28] = 1; this[35] = 1; this[36] = 2
    }
    private val black = PeoplePlayParticipantSummary("black-member", "Sample Black", AvatarId.BOY)
    private val white = PeoplePlayParticipantSummary("white-member", "Sample White", AvatarId.GIRL)
    private val snapshot = PeoplePlayRoomSnapshot(
        roomId = "fixture-room", phase = RoomPhase.PLAYING, timeControl = TimeControl.TEN_MINUTES,
        seats = PeoplePlaySeats(black, white), players = PeoplePlayPlayers(black, white), wirePly = 0,
        board = board, move = null, nextTurn = PlayerColor.BLACK, terminalCandidate = false,
        resultCheckPly = null, spectatorCount = 0, spectatorAvatarPreview = emptyList(),
    )

    @Test fun allSixClientTypesRoundTrip() {
        val messages: List<PeoplePlayClientMessage> = listOf(
            TakeSeat("fixture-room"), LeaveSeat("fixture-room"),
            MoveSnapshot("fixture-room", 1, PeoplePlayMove(2, 3), board, PlayerColor.WHITE, false),
            Desync("fixture-room", 1, DesyncReason.SNAPSHOT_MISMATCH),
            ResultReport("fixture-room", 1, ReportedResult.NOT_FINISHED), TimeoutSelf("fixture-room"),
        )
        assertEquals(6, messages.size)
        messages.forEach { assertEquals(it, PeoplePlayProtocolCodec.decodeClientMessage(PeoplePlayProtocolCodec.encodeClientMessage(it)).getOrThrow()) }
    }

    @Test fun allFourServerTypesRoundTrip() {
        val messages: List<PeoplePlayServerMessage> = listOf(
            snapshot, ResultCheck("fixture-room", 0),
            GameOver("fixture-room", 0, FinishReason.DESYNC, Outcome.NO_CONTEST, null, 1L, snapshot),
            PeoplePlayError("fixture-room", PeoplePlayErrorCode.BAD_PLY, "MOVE_SNAPSHOT", 0),
        )
        assertEquals(4, messages.size)
        messages.forEach { assertEquals(it, PeoplePlayProtocolCodec.decodeServerMessage(PeoplePlayProtocolCodec.encodeServerMessage(it)).getOrThrow()) }
    }

    @Test fun wirePlyUsesSpecifiedJsonNames() {
        val move = Json.parseToJsonElement(PeoplePlayProtocolCodec.encodeClientMessage(
            MoveSnapshot("fixture-room", 1, PeoplePlayMove(2, 3), board, PlayerColor.WHITE, false),
        )).jsonObject
        val room = Json.parseToJsonElement(PeoplePlayProtocolCodec.encodeServerMessage(snapshot)).jsonObject
        assertTrue("ply" in move && "wirePly" !in move)
        assertTrue("currentPly" in room && "wirePly" !in room)
        assertTrue(listOf("protocolVersion", "type", "roomId").all { it in move && it in room })
        assertTrue(listOf("players", "move", "resultCheckPly", "nextTurn").all { it in room })
    }

    @Test fun wrongVersionUnknownTypeUnknownFieldAndMissingEnvelopeAreRejected() {
        val valid = PeoplePlayProtocolCodec.encodeClientMessage(TakeSeat("fixture-room"))
        assertTrue(PeoplePlayProtocolCodec.decodeClientMessage(valid.replace("\"protocolVersion\":1", "\"protocolVersion\":2")).isFailure)
        assertTrue(PeoplePlayProtocolCodec.decodeClientMessage(valid.replace("TAKE_SEAT", "JOIN")).isFailure)
        assertTrue(PeoplePlayProtocolCodec.decodeClientMessage(valid.dropLast(1) + ",\"extra\":1}").isFailure)
        val fields = Json.parseToJsonElement(valid).jsonObject
        assertTrue(PeoplePlayProtocolCodec.decodeClientMessage(JsonObject(fields - "type").toString()).isFailure)
        assertTrue(PeoplePlayProtocolCodec.decodeClientMessage(JsonObject(fields - "roomId").toString()).isFailure)
        assertTrue(PeoplePlayProtocolCodec.decodeClientMessage("not json").isFailure)
        assertFailsWith<IllegalArgumentException> { PeoplePlayProtocolCodec.encodeClientMessage(TakeSeat(" ")) }
    }

    @Test fun directionsNeverCrossDecode() {
        assertTrue(PeoplePlayProtocolCodec.decodeClientMessage(PeoplePlayProtocolCodec.encodeServerMessage(snapshot)).isFailure)
        assertTrue(PeoplePlayProtocolCodec.decodeServerMessage(PeoplePlayProtocolCodec.encodeClientMessage(TakeSeat("fixture-room"))).isFailure)
    }
}
