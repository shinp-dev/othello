package com.example.othello

import com.example.othello.profile.YesterdayRanking
import kotlin.test.assertEquals
import org.junit.Test

class RatingAchievementStoreTest {
    @Test
    fun betterPercentileReplacesTheDeviceBest() {
        val current = LocalBestRating(6.5, "2026-08-20")
        assertEquals(
            LocalBestRating(3.8, "2026-08-21"),
            betterLocalBest(current, LocalBestRating(3.8, "2026-08-21")),
        )
    }

    @Test
    fun worseOrEqualPercentileKeepsTheExistingDate() {
        val current = LocalBestRating(3.8, "2026-07-14")
        assertEquals(current, betterLocalBest(current, LocalBestRating(6.5, "2026-08-21")))
        assertEquals(current, betterLocalBest(current, LocalBestRating(3.8, "2026-08-21")))
    }

    @Test
    fun rankingFormattingUsesThousandsSeparatorsAndOneDecimalPercent() {
        assertEquals(
            "上位 6.5%　312 / 4,821位",
            formatYesterdayRanking(YesterdayRanking("2026-08-21", 312, 4821, 6.47)),
        )
        assertEquals("2026/07/14", formatAchievementDate("2026-07-14"))
    }
}
