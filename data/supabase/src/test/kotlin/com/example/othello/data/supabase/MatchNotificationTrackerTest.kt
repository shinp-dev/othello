package com.example.othello.data.supabase

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchNotificationTrackerTest {
    @Test
    fun newMatchIsDetectedEvenWhenAnOlderNotificationRemains() {
        val tracker = MatchNotificationTracker()

        assertTrue(tracker.observe(setOf("old-match")))
        assertFalse(tracker.observe(setOf("old-match")))
        assertTrue(tracker.observe(setOf("old-match", "new-match")))
    }

    @Test
    fun reconnectSnapshotDoesNotRepeatAnAlreadyObservedMatch() {
        val tracker = MatchNotificationTracker()

        assertTrue(tracker.observe(setOf("match")))
        assertFalse(tracker.observe(setOf("match")))
    }
}
