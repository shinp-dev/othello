package com.example.othello.review

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisRequestGuardTest {
    @Test
    fun staleResultIsRejectedAfterPlyChange() {
        val guard = AnalysisRequestGuard()
        val first = guard.begin("position-a")
        val second = guard.begin("position-b")

        assertFalse(guard.isCurrent(first, "position-a"))
        assertTrue(guard.isCurrent(second, "position-b"))
    }

    @Test
    fun olderResultIsRejectedEvenAfterReturningToSamePosition() {
        val guard = AnalysisRequestGuard()
        val first = guard.begin("position-a")
        guard.begin("position-b")
        val latest = guard.begin("position-a")

        assertFalse(guard.isCurrent(first, "position-a"))
        assertTrue(guard.isCurrent(latest, "position-a"))
        guard.invalidate()
        assertFalse(guard.isCurrent(latest, "position-a"))
    }
}
