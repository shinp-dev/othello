package com.example.othello.data.supabase

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class DailyRatingSnapshotTest {
    private val overseasDeviceClock = Clock.fixed(
        Instant.parse("2026-08-21T15:30:00Z"),
        ZoneId.of("America/Los_Angeles"),
    )

    @Test
    fun onlyTheRealTokyoYesterdayIsMappedForDisplay() {
        val yesterday = row("2026-08-21").toDomainOrNull(overseasDeviceClock)
        assertEquals("2026-08-21", yesterday?.snapshotDate)
        assertNull(row("2026-08-20").toDomainOrNull(overseasDeviceClock))
        assertNull(row("2026-08-22").toDomainOrNull(overseasDeviceClock))
    }

    private fun row(snapshotDate: String) = YesterdayRankingRow(
        snapshotDate = snapshotDate,
        rank = 312,
        activeUserCount = 4821,
        topPercentile = 6.47,
    )
}
