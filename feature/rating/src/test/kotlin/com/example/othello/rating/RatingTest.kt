package com.example.othello.rating

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RatingTest {
    @Test fun peakNeverDecreases() {
        val change = updateRating(1500, 1600, 1800, RatingOutcome.LOSS, EloRatingPolicy())
        assertEquals(1600, change.peak)
    }

    @Test fun insufficientStableDataIsCalculating() {
        assertIs<StableBand.Calculating>(StableRatingPolicy().calculate(RatingSnapshot(1500, 1500, listOf(1500))))
    }

    @Test fun stableBandUsesTwentyToEightyPercentiles() {
        val band = StableRatingPolicy().calculate(RatingSnapshot(1500, 1800, (1000..1800 step 100).toList())) as StableBand.Range
        assertEquals(1200, band.low)
        assertEquals(1600, band.high)
    }
}
