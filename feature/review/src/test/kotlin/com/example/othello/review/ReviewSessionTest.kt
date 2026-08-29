package com.example.othello.review

import com.example.othello.game.GameState
import com.example.othello.game.Position
import com.example.othello.records.FinishReason
import com.example.othello.records.GameRecord
import com.example.othello.records.MatchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewSessionTest {
    private val record = GameRecord(
        matchId = "record",
        players = listOf("black", "white"),
        moves = listOf(Position(2, 3), Position(2, 2)),
        result = MatchResult.BLACK_WIN,
        startedAtEpochMillis = 0,
        finishedAtEpochMillis = 1,
        timeControl = "5m",
        finishReason = FinishReason.NORMAL,
    )

    @Test
    fun navigationAvailabilityTracksInitialMiddleAndFinalPositions() {
        val session = ReviewSession(record)

        assertEquals(
            ReviewNavigationState(
                canGoToFirst = false,
                canGoToPrevious = false,
                canGoToNext = true,
                canGoToLast = true,
            ),
            session.navigationState,
        )
        session.seek(0)
        assertEquals(0, session.cursor)
        session.previous()
        assertEquals(0, session.cursor)

        session.seek(1)
        assertEquals(
            ReviewNavigationState(
                canGoToFirst = true,
                canGoToPrevious = true,
                canGoToNext = true,
                canGoToLast = true,
            ),
            session.navigationState,
        )

        session.seek(session.mainLineLastPly)
        assertEquals(
            ReviewNavigationState(
                canGoToFirst = true,
                canGoToPrevious = true,
                canGoToNext = false,
                canGoToLast = false,
            ),
            session.navigationState,
        )
        session.next()
        assertEquals(session.mainLineLastPly, session.cursor)
        session.seek(session.mainLineLastPly)
        assertEquals(session.mainLineLastPly, session.cursor)
    }

    @Test
    fun mainLineNavigationAndVariationNeverMutateRecord() {
        val session = ReviewSession(record)
        assertEquals(record.result, session.reviewInput.result)
        assertEquals(record.finishReason, session.reviewInput.finishReason)
        assertEquals(record.finishedAtEpochMillis, session.reviewInput.finishedAtEpochMillis)
        session.seek(1)
        val mainLine = session.current
        session.beginVariation()
        val alternative = mainLine.legalMoves.first()
        assertTrue(session.playVariation(alternative))
        assertTrue(session.isInVariation)
        assertFalse(session.current == mainLine)

        session.saveVariationAndReturn()
        assertFalse(session.isInVariation)
        assertEquals(mainLine, session.current)
        assertEquals(1, session.currentVariations.size)
        assertEquals(2, record.moves.size)

        session.seek(0)
        assertEquals(GameState(), session.current)
        session.seek(Int.MAX_VALUE)
        assertEquals(2, session.cursor)
    }
}
