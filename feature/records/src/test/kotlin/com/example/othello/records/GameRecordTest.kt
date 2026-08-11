package com.example.othello.records

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GameRecordTest {
    @Test
    fun exposesExplicitBlackWhiteOrderAndVerifiedFinalHash() {
        val record = record(finalPositionHash = "0123456789abcdef:0:1:60")

        assertEquals("black", record.blackPlayerId)
        assertEquals("white", record.whitePlayerId)
        assertEquals("0123456789abcdef:0:1:60", record.finalPositionHash)
    }

    @Test
    fun acceptsLegacyRecordWithoutHashButRejectsMalformedOrDuplicatePlayers() {
        record(finalPositionHash = null)
        assertFailsWith<IllegalArgumentException> { record(finalPositionHash = "bad") }
        assertFailsWith<IllegalArgumentException> { record(players = listOf("same", "same")) }
    }

    private fun record(
        players: List<String> = listOf("black", "white"),
        finalPositionHash: String? = null,
    ) = GameRecord(
        matchId = "match",
        players = players,
        moves = emptyList(),
        result = MatchResult.DRAW,
        startedAtEpochMillis = 1,
        finishedAtEpochMillis = 2,
        timeControl = "5m",
        finishReason = FinishReason.NORMAL,
        finalPositionHash = finalPositionHash,
    )
}
