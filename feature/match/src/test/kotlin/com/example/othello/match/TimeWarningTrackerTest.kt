package com.example.othello.match

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeWarningTrackerTest {
    @Test
    fun emitsEachWarningOnlyWhenCrossingBelowItsThreshold() {
        val tracker = TimeWarningTracker()
        tracker.reset(120_000L)

        assertEquals(emptyList(), tracker.onRemainingChanged(60_000L))
        assertEquals(listOf(TimeWarning.ONE_MINUTE), tracker.onRemainingChanged(59_999L))
        assertEquals(emptyList(), tracker.onRemainingChanged(45_000L))
        assertEquals(emptyList(), tracker.onRemainingChanged(30_000L))
        assertEquals(listOf(TimeWarning.THIRTY_SECONDS), tracker.onRemainingChanged(29_999L))
        assertEquals(emptyList(), tracker.onRemainingChanged(10_000L))
    }

    @Test
    fun doesNotReplayWarningsWhenMatchStartsBelowThreshold() {
        val tracker = TimeWarningTracker()
        tracker.reset(20_000L)

        assertEquals(emptyList(), tracker.onRemainingChanged(19_000L))
    }

    @Test
    fun resetArmsWarningsForTheNextMatch() {
        val tracker = TimeWarningTracker()
        tracker.reset(120_000L)
        tracker.onRemainingChanged(59_999L)

        tracker.reset(120_000L)

        assertEquals(listOf(TimeWarning.ONE_MINUTE), tracker.onRemainingChanged(59_999L))
    }
}
