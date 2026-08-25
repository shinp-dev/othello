package com.example.othello.data.supabase

import com.example.othello.network.MAX_MATCH_NEGOTIATION_EPOCH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.format.DateTimeParseException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseNetworkAndSignalingTest {
    @Test
    fun postgrestOffsetTimestampsAreParsedAcrossSupportedIsoForms() {
        val expectedMillis = 1_787_627_021_225L
        assertEquals(
            expectedMillis,
            parsePostgrestTimestamp("2026-08-25T03:03:41.225848+00:00").toEpochMilli(),
        )
        assertEquals(
            expectedMillis,
            parsePostgrestTimestamp("2026-08-25T03:03:41.225848Z").toEpochMilli(),
        )
        assertEquals(
            expectedMillis,
            parsePostgrestTimestamp("2026-08-25T03:03:41.225Z").toEpochMilli(),
        )
        assertEquals(
            expectedMillis,
            parsePostgrestTimestamp("2026-08-25T03:03:41.225848999Z").toEpochMilli(),
        )
        assertEquals(
            1_787_627_021_000L,
            parsePostgrestTimestamp("2026-08-25T03:03:41Z").toEpochMilli(),
        )
        assertEquals(
            expectedMillis,
            parsePostgrestTimestamp("2026-08-25T12:03:41.225848+09:00").toEpochMilli(),
        )
    }

    @Test
    fun invalidPostgrestTimestampIsRejected() {
        assertFailsWith<DateTimeParseException> {
            parsePostgrestTimestamp("2026-08-25 03:03:41+00:00")
        }
    }

    @Test
    fun zeroRowRpcIsDistinctFromAMalformedMultiRowResponse() {
        assertEquals(null, emptyList<Int>().singleOrNullForRpc("claim_active_match_v2"))
        assertEquals(7, listOf(7).singleOrNullForRpc("claim_active_match_v2"))
        val malformed = assertFailsWith<IllegalStateException> {
            listOf(7, 8).singleOrNullForRpc("claim_active_match_v2")
        }
        assertEquals("claim_active_match_v2 returned 2 rows", malformed.message)
    }

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
        validateSignalingEnvelope(SignalingEnvelope(
            "match", "user", "RESUME", "resume", negotiationEpoch = MAX_MATCH_NEGOTIATION_EPOCH,
        ))
        assertFailsWith<IllegalArgumentException> {
            validateSignalingEnvelope(SignalingEnvelope("match", "user", "RESUME", "resume", negotiationEpoch = -1))
        }
        assertFailsWith<IllegalArgumentException> {
            validateSignalingEnvelope(SignalingEnvelope(
                "match", "user", "RESUME", "resume",
                negotiationEpoch = MAX_MATCH_NEGOTIATION_EPOCH + 1,
            ))
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
