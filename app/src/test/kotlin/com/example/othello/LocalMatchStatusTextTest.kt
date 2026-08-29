package com.example.othello

import com.example.othello.game.Disc
import com.example.othello.match.LocalMatchStatusMessage
import com.example.othello.records.MatchResult
import kotlin.test.assertEquals
import org.junit.Test

class LocalMatchStatusTextTest {
    @Test
    fun everyLocalMatchStatusMapsToAppStringResources() {
        assertEquals(
            LocalMatchStatusTextSpec(R.string.local_match_to_move, R.string.black),
            LocalMatchStatusMessage.Turn(Disc.BLACK).textSpec(),
        )
        assertEquals(
            LocalMatchStatusTextSpec(R.string.local_match_to_move, R.string.white),
            LocalMatchStatusMessage.Turn(Disc.WHITE).textSpec(),
        )
        assertEquals(
            LocalMatchStatusTextSpec(R.string.local_match_forced_pass, R.string.black),
            LocalMatchStatusMessage.Turn(Disc.BLACK, forcedPass = true).textSpec(),
        )
        assertEquals(
            LocalMatchStatusTextSpec(R.string.local_match_forced_pass, R.string.white),
            LocalMatchStatusMessage.Turn(Disc.WHITE, forcedPass = true).textSpec(),
        )
        assertEquals(
            LocalMatchStatusTextSpec(R.string.local_match_resigned, R.string.black),
            LocalMatchStatusMessage.Resigned(Disc.BLACK).textSpec(),
        )
        assertEquals(
            LocalMatchStatusTextSpec(R.string.local_match_resigned, R.string.white),
            LocalMatchStatusMessage.Resigned(Disc.WHITE).textSpec(),
        )
        assertEquals(
            LocalMatchStatusTextSpec(R.string.local_match_wins, R.string.black),
            LocalMatchStatusMessage.GameResult(MatchResult.BLACK_WIN).textSpec(),
        )
        assertEquals(
            LocalMatchStatusTextSpec(R.string.local_match_wins, R.string.white),
            LocalMatchStatusMessage.GameResult(MatchResult.WHITE_WIN).textSpec(),
        )
        assertEquals(
            LocalMatchStatusTextSpec(R.string.result_draw),
            LocalMatchStatusMessage.GameResult(MatchResult.DRAW).textSpec(),
        )
        assertEquals(
            LocalMatchStatusTextSpec(R.string.local_match_ai_cannot_move),
            LocalMatchStatusMessage.AiCannotMove.textSpec(),
        )
    }
}
