package com.example.othello.data.supabase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseNetworkAndSignalingTest {
    @Test
    fun boundedTimeoutBecomesAUserRecoverableException(): Unit = runBlocking {
        assertFailsWith<ReleaseNetworkTimeoutException> {
            runBlocking { boundedReleaseNetwork(timeoutMillis = 5) { delay(50) } }
        }
    }

    @Test
    fun ordinaryCoroutineCancellationIsNotConvertedToNetworkFailure(): Unit = runBlocking {
        val cancellation = assertFailsWith<CancellationException> {
            runBlocking {
                boundedReleaseNetwork(timeoutMillis = 1_000) {
                    throw CancellationException("screen left")
                }
            }
        }
        assertEquals("screen left", cancellation.message)
    }

    @Test
    fun resumeAndEpochArePartOfTheValidatedSignalingContract() {
        validateSignalingEnvelope(SignalingEnvelope("match", "user", "RESUME", "resume", negotiationEpoch = 3))
        assertFailsWith<IllegalArgumentException> {
            validateSignalingEnvelope(SignalingEnvelope("match", "user", "RESUME", "resume", negotiationEpoch = -1))
        }
        assertFailsWith<IllegalArgumentException> {
            validateSignalingEnvelope(SignalingEnvelope("match", "user", "CANDIDATE", "candidate", negotiationEpoch = 3))
        }
    }

    @Test
    fun deliveryDeduplicationIsEpochAwareAndMemoryBounded() {
        val tracker = SignalingDeliveryTracker(capacity = 2)
        val first = SignalingDeliveryKey(1, "peer", "OFFER", "sdp", 2, 1)
        val nextEpoch = first.copy(id = 2, negotiationEpoch = 2)
        val third = first.copy(id = 3, sdp = "new", negotiationEpoch = 2)

        assertTrue(tracker.observe(first))
        assertFalse(tracker.observe(first))
        assertTrue(tracker.observe(nextEpoch))
        assertTrue(tracker.observe(third))
        assertEquals(2, tracker.size)
        assertTrue(tracker.observe(first), "the evicted oldest key may be delivered again after a bounded catch-up")
    }
}
