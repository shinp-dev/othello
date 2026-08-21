package com.example.othello

import com.example.othello.data.supabase.SignalingEnvelope
import com.example.othello.data.supabase.SignalingPayload
import com.example.othello.data.supabase.SupabaseSignalingDataSource
import com.example.othello.network.FinishCommand
import com.example.othello.network.MoveCommand
import com.example.othello.network.TransportState
import com.example.othello.transport.webrtc.IceCandidatePayload
import com.example.othello.transport.webrtc.SessionDescriptionPayload
import com.example.othello.transport.webrtc.WebRtcSignalingTransport
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeWebRtcSignalingTransport : WebRtcSignalingTransport {
    private val localCandidateListeners = CopyOnWriteArraySet<(IceCandidatePayload) -> Unit>()
    val remoteDescriptions = mutableListOf<SessionDescriptionPayload>()
    val remoteCandidates = mutableListOf<IceCandidatePayload>()
    var createOfferGate: CompletableDeferred<Unit>? = null
    var candidateDuringOffer: IceCandidatePayload? = null
    var candidateDuringAnswer: IceCandidatePayload? = null
    var offererDataChannelCreated = false
    val dataChannelOpen = CompletableDeferred<Unit>()

    override suspend fun createOffer(): SessionDescriptionPayload {
        createOfferGate?.await()
        candidateDuringOffer?.let(::emitLocalCandidate)
        return SessionDescriptionPayload("OFFER", "offer-sdp")
    }

    override suspend fun createAnswer(): SessionDescriptionPayload {
        candidateDuringAnswer?.let(::emitLocalCandidate)
        return SessionDescriptionPayload("ANSWER", "answer-sdp")
    }

    override suspend fun setRemoteDescription(description: SessionDescriptionPayload) {
        remoteDescriptions += description
    }

    override suspend fun addRemoteIceCandidate(candidate: IceCandidatePayload) {
        remoteCandidates += candidate
    }

    override fun observeLocalIceCandidates(onCandidate: (IceCandidatePayload) -> Unit): AutoCloseable {
        localCandidateListeners += onCandidate
        return AutoCloseable { localCandidateListeners -= onCandidate }
    }

    fun emitLocalCandidate(candidate: IceCandidatePayload) {
        localCandidateListeners.toList().forEach { it(candidate) }
    }

    override suspend fun awaitDataChannelOpen() = dataChannelOpen.await()
    override fun provideOffererDataChannel() { offererDataChannelCreated = true }
    override suspend fun send(command: MoveCommand) = Unit
    override suspend fun sendFinish(command: FinishCommand) = Unit
    override fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable = AutoCloseable { }
    override fun observeFinish(onCommand: (FinishCommand) -> Unit): AutoCloseable = AutoCloseable { }
    override fun observeState(onState: (TransportState) -> Unit): AutoCloseable = AutoCloseable { }
}

private class FakeSignalingDataSource : SupabaseSignalingDataSource {
    val published = mutableListOf<SignalingEnvelope>()
    val publishAttempts = mutableMapOf<String, Int>()
    val failuresRemaining = mutableMapOf<String, Int>()
    private var onEnvelope: ((SignalingEnvelope) -> Unit)? = null
    var subscriptionClosed = false

    override suspend fun publish(envelope: SignalingEnvelope) {
        val type = envelope.payload.signalType
        publishAttempts[type] = publishAttempts.getOrDefault(type, 0) + 1
        val remaining = failuresRemaining.getOrDefault(type, 0)
        if (remaining > 0) {
            failuresRemaining[type] = remaining - 1
            error("publish failed")
        }
        published += envelope
    }

    override fun subscribe(
        matchId: String,
        onEnvelope: (SignalingEnvelope) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable {
        this.onEnvelope = onEnvelope
        return AutoCloseable {
            subscriptionClosed = true
            this.onEnvelope = null
        }
    }

    fun emit(envelope: SignalingEnvelope) {
        onEnvelope?.invoke(envelope)
    }
}

class WebRtcSignalingSessionTest {
    @Test
    fun offerPublishesImmediatelyThenLocalCandidateRetriesAndPublishes() = runBlocking {
        val transport = FakeWebRtcSignalingTransport().apply {
            candidateDuringOffer = IceCandidatePayload("local-candidate", "data", 0)
        }
        val signaling = FakeSignalingDataSource().apply {
            failuresRemaining["OFFER"] = 1
            failuresRemaining["ICE_CANDIDATE"] = 2
        }
        val session = session(offerer = true, transport = transport, signaling = signaling)

        session.start()
        eventually { signaling.published.size == 2 }

        assertTrue(transport.offererDataChannelCreated)
        assertIs<SignalingPayload.Offer>(signaling.published[0].payload)
        assertIs<SignalingPayload.IceCandidate>(signaling.published[1].payload)
        assertEquals(2, signaling.publishAttempts["OFFER"])
        assertEquals(3, signaling.publishAttempts["ICE_CANDIDATE"])
        session.close()
    }

    @Test
    fun earlyRemoteCandidatesQueueDrainInOrderAndDuplicatesApplyOnce() = runBlocking {
        val transport = FakeWebRtcSignalingTransport().apply {
            candidateDuringAnswer = IceCandidatePayload("local-answer-candidate", null, 0)
        }
        val signaling = FakeSignalingDataSource()
        val session = session(offerer = false, transport = transport, signaling = signaling)
        val first = remote(SignalingPayload.IceCandidate("candidate-1", "0", 0))
        val second = remote(SignalingPayload.IceCandidate("candidate-2", "0", 0))

        session.start()
        signaling.emit(first)
        signaling.emit(first)
        signaling.emit(second)
        delay(20)
        assertTrue(transport.remoteCandidates.isEmpty())

        signaling.emit(remote(SignalingPayload.Offer("offer-sdp")))
        eventually { transport.remoteCandidates.size == 2 && signaling.published.size == 2 }

        assertEquals(listOf("candidate-1", "candidate-2"), transport.remoteCandidates.map { it.candidate })
        assertIs<SignalingPayload.Answer>(signaling.published[0].payload)
        assertIs<SignalingPayload.IceCandidate>(signaling.published[1].payload)
        session.close()
    }

    @Test
    fun offererQueuesCandidateBeforeAnswerThenAppliesLaterCandidateDirectly() = runBlocking {
        val transport = FakeWebRtcSignalingTransport()
        val signaling = FakeSignalingDataSource()
        val session = session(offerer = true, transport = transport, signaling = signaling)
        val earlyCandidate = remote(SignalingPayload.IceCandidate("candidate-early", "data", 1))
        val laterCandidate = remote(SignalingPayload.IceCandidate("candidate-later", "data", 1))

        session.start()
        eventually { signaling.published.any { it.payload is SignalingPayload.Offer } }
        signaling.emit(earlyCandidate)
        signaling.emit(earlyCandidate)
        delay(20)
        assertTrue(transport.remoteCandidates.isEmpty())

        signaling.emit(remote(SignalingPayload.Answer("answer-sdp")))
        eventually { transport.remoteCandidates.size == 1 }
        signaling.emit(laterCandidate)
        signaling.emit(laterCandidate)
        eventually { transport.remoteCandidates.size == 2 }

        assertEquals(listOf("candidate-early", "candidate-later"), transport.remoteCandidates.map { it.candidate })
        session.close()
    }

    @Test
    fun answerArrivingBeforeLocalOfferIsHeldUntilOfferPublishCompletes() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeWebRtcSignalingTransport().apply { createOfferGate = gate }
        val signaling = FakeSignalingDataSource()
        val session = session(offerer = true, transport = transport, signaling = signaling)

        session.start()
        signaling.emit(remote(SignalingPayload.Answer("answer-sdp")))
        delay(20)
        assertTrue(transport.remoteDescriptions.isEmpty())

        gate.complete(Unit)
        eventually { transport.remoteDescriptions.size == 1 }
        assertEquals("ANSWER", transport.remoteDescriptions.single().type)
        session.close()
    }

    @Test
    fun callbacksDuringAndAfterCloseAreIgnored() = runBlocking {
        val transport = FakeWebRtcSignalingTransport()
        val signaling = FakeSignalingDataSource()
        val session = session(offerer = false, transport = transport, signaling = signaling)

        session.start()
        session.close()
        transport.emitLocalCandidate(IceCandidatePayload("late-local", null, 0))
        signaling.emit(remote(SignalingPayload.IceCandidate("late-remote", null, 0)))
        delay(20)

        assertTrue(signaling.published.isEmpty())
        assertTrue(transport.remoteCandidates.isEmpty())
        assertTrue(signaling.subscriptionClosed)
    }

    private fun CoroutineScope.session(
        offerer: Boolean,
        transport: FakeWebRtcSignalingTransport,
        signaling: FakeSignalingDataSource,
    ) = WebRtcSignalingSession(
        matchId = "match",
        userId = "local",
        opponentId = "remote",
        offerer = offerer,
        transport = transport,
        signaling = signaling,
        parentScope = this,
        onDataChannelOpen = { true },
        onError = { throw AssertionError(it) },
        publishRetryDelayMillis = 0,
        startAcknowledgementRetryMillis = 0,
    )

    private fun remote(payload: SignalingPayload) = SignalingEnvelope("match", "remote", payload)

    private suspend fun eventually(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            delay(5)
        }
        assertTrue(predicate(), "condition was not met")
    }
}
