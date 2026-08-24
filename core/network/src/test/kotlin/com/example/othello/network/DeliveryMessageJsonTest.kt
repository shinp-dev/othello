package com.example.othello.network

import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeliveryMessageJsonTest {
    private val initial = GameState()
    private val afterOpeningMove = (initial.play(Position(2, 3)) as MoveOutcome.Played).state

    @Test
    fun moveAckRoundTripsWithProtocolV2() {
        val ack = MoveAck(
            matchId = "match-1",
            commandId = "command-1",
            acknowledgedPly = afterOpeningMove.ply,
            stateHash = afterOpeningMove.stateHash(),
        )

        assertEquals(CURRENT_PROTOCOL_VERSION, ack.protocolVersion)
        assertEquals(ack, MoveAckJson.decode(MoveAckJson.encode(ack)).getOrThrow())
    }

    @Test
    fun invalidProtocolAndUnknownAckFieldsAreRejected() {
        val ack = MoveAck("match-1", "command-1", initial.ply, initial.stateHash())
        val encoded = MoveAckJson.encode(ack)

        assertTrue(MoveAckJson.decode(encoded.replace("\"protocolVersion\":2", "\"protocolVersion\":1")).isFailure)
        assertTrue(MoveAckJson.decode(encoded.dropLast(1) + ",\"unexpected\":true}").isFailure)
        assertFailsWith<IllegalArgumentException> { MoveAckJson.encode(ack.copy(protocolVersion = 1)) }
    }

    @Test
    fun duplicateMoveAckPayloadsDecodeToTheSameIdentity() {
        val ack = MoveAck("match-1", "command-1", initial.ply, initial.stateHash())
        val payload = MoveAckJson.encode(ack)
        val first = MoveAckJson.decode(payload).getOrThrow()
        val duplicate = MoveAckJson.decode(payload).getOrThrow()

        assertEquals(first, duplicate)
        assertEquals(1, setOf(first, duplicate).size)
    }

    @Test
    fun syncRequestAndSnapshotRoundTrip() {
        val request = SyncMessage(
            matchId = "match-1",
            requestId = "sync-1",
            type = SyncMessageType.REQUEST,
            ply = initial.ply,
            stateHash = initial.stateHash(),
        )
        val snapshot = SyncMessage(
            matchId = "match-1",
            requestId = "sync-1",
            type = SyncMessageType.SNAPSHOT,
            ply = afterOpeningMove.ply,
            stateHash = afterOpeningMove.stateHash(),
            transcript = "d3",
        )

        assertEquals(request, SyncMessageJson.decode(SyncMessageJson.encode(request)).getOrThrow())
        assertEquals(snapshot, SyncMessageJson.decode(SyncMessageJson.encode(snapshot)).getOrThrow())
    }

    @Test
    fun syncRejectsInvalidProtocolAndTranscriptShape() {
        val request = SyncMessage("match-1", "sync-1", SyncMessageType.REQUEST, initial.ply, initial.stateHash())
        val encoded = SyncMessageJson.encode(request)
        assertTrue(SyncMessageJson.decode(encoded.replace("\"protocolVersion\":2", "\"protocolVersion\":1")).isFailure)

        assertFailsWith<IllegalArgumentException> {
            SyncMessageJson.encode(request.copy(transcript = "d3"))
        }
        assertFailsWith<IllegalArgumentException> {
            SyncMessageJson.encode(request.copy(type = SyncMessageType.SNAPSHOT))
        }
        assertFailsWith<IllegalArgumentException> {
            SyncMessageJson.encode(
                request.copy(type = SyncMessageType.SNAPSHOT, ply = 1, stateHash = afterOpeningMove.stateHash(), transcript = "z9"),
            )
        }
    }

    @Test
    fun syncTranscriptAccepts240CharactersAndRejectsAnythingLarger() {
        val atLimit = SyncMessage(
            matchId = "match-1",
            requestId = "sync-limit",
            type = SyncMessageType.SNAPSHOT,
            ply = MAX_SYNC_TRANSCRIPT_CHARS / 2,
            stateHash = "0123456789abcdef:1:0:${MAX_SYNC_TRANSCRIPT_CHARS / 2}",
            transcript = "--".repeat(MAX_SYNC_TRANSCRIPT_CHARS / 2),
        )
        assertEquals(atLimit, SyncMessageJson.decode(SyncMessageJson.encode(atLimit)).getOrThrow())

        val tooLarge = atLimit.copy(
            ply = atLimit.ply + 1,
            stateHash = "0123456789abcdef:1:0:${atLimit.ply + 1}",
            transcript = atLimit.transcript + "--",
        )
        assertFailsWith<IllegalArgumentException> { SyncMessageJson.encode(tooLarge) }
    }
}
