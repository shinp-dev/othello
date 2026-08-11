package com.example.othello.network

import com.example.othello.game.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoveCommandJsonTest {
    @Test
    fun roundTripPreservesProtocolFields() {
        val command = MoveCommand("match", 4, Position(2, 5), "command", "hash", 1)
        assertEquals(command, MoveCommandJson.decode(MoveCommandJson.encode(command)).getOrThrow())
    }

    @Test
    fun malformedPayloadIsRejectedWithoutThrowingFromDecoder() {
        assertEquals(true, MoveCommandJson.decode("not-json").isFailure)
    }
}
