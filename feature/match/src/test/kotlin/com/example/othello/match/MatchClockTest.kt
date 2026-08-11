package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.network.ClockSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchClockTest {
    @Test
    fun usesMonotonicElapsedTimeAndSwitchesTurnsFromSnapshot() {
        var now = 100L
        val clock = MatchClock(5_000) { now }
        clock.start(Disc.BLACK)
        now += 750
        assertEquals(ClockSnapshot(4_250, 5_000), clock.snapshot())

        clock.adoptAndStart(clock.snapshot(), Disc.WHITE)
        now += 1_250
        assertEquals(ClockSnapshot(4_250, 3_750), clock.snapshot())
    }

    @Test
    fun remainingTimeNeverBecomesNegative() {
        var now = 0L
        val clock = MatchClock(1_000) { now }
        clock.start(Disc.BLACK)
        now = 10_000
        assertEquals(0, clock.snapshot().blackRemainingMillis)
    }
}
