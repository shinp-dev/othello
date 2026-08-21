package com.example.othello.data.supabase

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalingContractTest {
    @Test
    fun candidateRoundTripPreservesAllAndroidWebRtcFields() {
        val original = SignalingPayload.IceCandidate(
            candidate = "candidate:1 1 UDP 2122252543 192.0.2.1 5000 typ host",
            sdpMid = "data",
            sdpMLineIndex = 3,
        )

        val decoded = SignalingContract.decode(
            matchId = "match",
            senderUserId = "sender",
            type = original.signalType,
            payload = SignalingContract.encode(original),
            protocolVersion = CURRENT_SIGNALING_PROTOCOL_VERSION,
        ).getOrThrow().payload

        assertEquals(original, decoded)
    }

    @Test
    fun nullSdpMidIsExplicitAndRoundTrips() {
        val original = SignalingPayload.IceCandidate("candidate", null, 0)
        val encoded = SignalingContract.encode(original)

        assertTrue(encoded.containsKey("sdpMid"))
        assertEquals(JsonNull, encoded["sdpMid"])
        assertNull((SignalingContract.decode("match", "sender", "ICE_CANDIDATE", encoded, 2).getOrThrow().payload as SignalingPayload.IceCandidate).sdpMid)
    }

    @Test
    fun offerAndAnswerUseTypedSdpPayloads() {
        val offer = SignalingContract.decode(
            "match",
            "sender",
            "OFFER",
            buildJsonObject { put("sdp", JsonPrimitive("offer-sdp")) },
            2,
        ).getOrThrow()
        val answer = SignalingContract.decode(
            "match",
            "sender",
            "ANSWER",
            buildJsonObject { put("sdp", JsonPrimitive("answer-sdp")) },
            2,
        ).getOrThrow()

        assertIs<SignalingPayload.Offer>(offer.payload)
        assertIs<SignalingPayload.Answer>(answer.payload)
    }

    @Test
    fun validatorRejectsGameProtocolVersionAndIncompleteCandidate() {
        assertFailsWith<IllegalArgumentException> {
            SignalingContract.validate(SignalingEnvelope("match", "sender", SignalingPayload.Offer("sdp"), protocolVersion = 1))
        }
        assertTrue(
            SignalingContract.decode(
                "match",
                "sender",
                "ICE_CANDIDATE",
                buildJsonObject {
                    put("candidate", JsonPrimitive("candidate"))
                    put("sdpMLineIndex", JsonPrimitive(0))
                },
                2,
            ).isFailure,
        )
    }
}
