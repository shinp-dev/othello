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
import kotlinx.coroutines.runBlocking

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

    @Test
    fun currentVariationLineTracksThePersistableContentWithoutEndingEditing() {
        val session = ReviewSession(record)
        session.seek(1)
        session.beginVariation()

        assertEquals(null, session.currentVariationLine)
        assertTrue(session.playVariation(session.current.legalMoves.first()))
        val firstDraft = session.currentVariationLine

        assertTrue(session.isInVariation)
        assertEquals(record.moves.take(1), firstDraft?.take(1))

        assertTrue(session.playVariation(session.current.legalMoves.first()))
        assertTrue(session.isInVariation)
        assertFalse(firstDraft == session.currentVariationLine)
    }

    @Test
    fun variationSaveStateFollowsPersistenceResultsAndContentChanges() = runBlocking {
        val tracker = VariationSaveTracker()
        val initialDraft = listOf(Position(2, 3))
        val editedDraft = initialDraft + Position(2, 2)

        assertFalse(tracker.isSaved(initialDraft))

        val firstSave = tracker.save(initialDraft) { }
        assertTrue(firstSave.isSuccess)
        assertTrue(tracker.isSaved(initialDraft))

        assertFalse(tracker.isSaved(editedDraft))
        val failedSave = tracker.save(editedDraft) { error("persistence failed") }
        assertTrue(failedSave.isFailure)
        assertFalse(tracker.isSaved(editedDraft))

        val secondSave = tracker.save(editedDraft) { }
        assertTrue(secondSave.isSuccess)
        assertTrue(tracker.isSaved(editedDraft))
    }
}
