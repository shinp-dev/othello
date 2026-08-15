package com.example.othello

import kotlin.test.assertEquals
import org.junit.Test

class OpponentRatingLabelTest {
    @Test
    fun displaysOnlyServerRating() {
        assertEquals("相手　レート 1520", opponentRatingLabel(1520))
    }

    @Test
    fun missingRatingNeverFallsBackToAnIdentifier() {
        assertEquals("相手　レート ---", opponentRatingLabel(null))
    }
}
