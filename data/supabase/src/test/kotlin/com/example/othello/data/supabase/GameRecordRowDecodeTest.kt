package com.example.othello.data.supabase

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class GameRecordRowDecodeTest {
    @Test
    fun nullCanonicalMovesRowDoesNotPoisonValidRows() {
        val records = decodeGameRecordRows(listOf(validRow(), validRow(canonicalMoves = null)))

        assertEquals(1, records.size)
        assertEquals("valid", records.single().matchId)
    }

    @Test
    fun allInvalidRowsBecomeAnExplicitDataError() {
        assertFailsWith<InvalidGameRecordRowsException> {
            decodeGameRecordRows(listOf(validRow(canonicalMoves = null)))
        }
    }

    private fun validRow(canonicalMoves: String? = "d3") = buildJsonObject {
        put("match_id", "valid")
        putJsonArray("players") {
            add(JsonPrimitive("black"))
            add(JsonPrimitive("white"))
        }
        put("canonical_moves", canonicalMoves?.let(::JsonPrimitive) ?: JsonNull)
        put("result", "BLACK_WIN")
        put("started_at", "2026-08-24T00:00:00Z")
        put("finished_at", "2026-08-24T00:01:00Z")
        put("time_control", "5m")
        put("finish_reason", "NORMAL")
    }
}
