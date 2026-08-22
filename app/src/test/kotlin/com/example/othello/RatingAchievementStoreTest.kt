package com.example.othello

import com.example.othello.profile.YesterdayRanking
import com.example.othello.profile.isTokyoYesterday
import com.example.othello.profile.tokyoYesterday
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class RatingAchievementStoreTest {
    @Test
    fun firstPercentileBecomesTheDeviceBest() {
        val candidate = LocalBestRating(6.5, "2026-08-21")
        assertEquals(candidate, betterLocalBest(null, candidate))
    }

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

    @Test
    fun achievementPreferenceKeysAreSeparatedByAuthUserId() {
        val userA = ratingAchievementPreferenceKeys("00000000-0000-0000-0000-000000000001")
        val userB = ratingAchievementPreferenceKeys("00000000-0000-0000-0000-000000000002")
        assertNotEquals(userA.percentile, userB.percentile)
        assertNotEquals(userA.date, userB.date)
    }

    @Test
    fun yesterdayUsesTokyoEvenWhenTheDeviceClockHasAnotherZone() {
        val overseasDeviceClock = Clock.fixed(
            Instant.parse("2026-08-21T15:30:00Z"),
            ZoneId.of("America/Los_Angeles"),
        )
        assertEquals(LocalDate.parse("2026-08-21"), tokyoYesterday(overseasDeviceClock))
        assertTrue(YesterdayRanking("2026-08-21", 1, 10, 10.0).isTokyoYesterday(overseasDeviceClock))
    }

    @Test
    fun staleAndCurrentTokyoDatesAreNotAcceptedAsYesterday() {
        val clock = Clock.fixed(Instant.parse("2026-08-21T15:30:00Z"), ZoneId.of("UTC"))
        assertFalse(YesterdayRanking("2026-08-20", 1, 10, 10.0).isTokyoYesterday(clock))
        assertFalse(YesterdayRanking("2026-08-22", 1, 10, 10.0).isTokyoYesterday(clock))
        assertTrue(YesterdayRanking("2026-08-21", 1, 10, 10.0).isTokyoYesterday(clock))
    }
}
