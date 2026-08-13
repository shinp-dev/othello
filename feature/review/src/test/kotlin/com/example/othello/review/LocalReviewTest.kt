package com.example.othello.review

import com.example.othello.game.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocalReviewTest {
    @Test
    fun savingVariationReturnsCompleteInitialPositionLine() {
        val input = ReviewInput("local", listOf(Position(2, 3), Position(2, 2)))
        val session = ReviewSession(input)
        session.seek(1)
        session.beginVariation()
        val variationMove = session.current.legalMoves.first()
        assertTrue(session.playVariation(variationMove))

        val complete = session.saveVariationAndReturn()

        assertEquals(listOf(Position(2, 3), variationMove), complete)
        assertFalse(session.isInVariation)
    }

    private fun assertTrue(value: Boolean) = kotlin.test.assertTrue(value)
}
