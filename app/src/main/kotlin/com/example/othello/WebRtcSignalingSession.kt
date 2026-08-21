package com.example.othello

import com.example.othello.data.supabase.SignalingEnvelope
import com.example.othello.data.supabase.SignalingPayload
import com.example.othello.data.supabase.SupabaseSignalingDataSource
import com.example.othello.transport.webrtc.IceCandidatePayload
import com.example.othello.transport.webrtc.SessionDescriptionPayload
import com.example.othello.transport.webrtc.WebRtcSignalingTransport
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates the finite Supabase signaling phase; game traffic remains on DataChannel. */
internal class WebRtcSignalingSession(
    private val matchId: String,
    private val userId: String,
    private val opponentId: String,
    private val offerer: Boolean,
    private val transport: WebRtcSignalingTransport,
    private val signaling: SupabaseSignalingDataSource,
    parentScope: CoroutineScope,
    private val onDataChannelOpen: suspend () -> Boolean,
    private val onError: (Throwable) -> Unit,
    private val onEvent: (String) -> Unit = {},
    private val publishRetryAttempts: Int = 3,
    private val publishRetryDelayMillis: Long = 500L,
    private val startAcknowledgementAttempts: Int = 3,
    private val startAcknowledgementRetryMillis: Long = 1_000L,
) : AutoCloseable {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val closed = AtomicBoolean(false)
    private val signalingStopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val signalingMutex = Mutex()
    private val handledSignals = mutableSetOf<SignalingEnvelope>()
    private val queuedRemoteCandidates = ArrayDeque<Pair<SignalingEnvelope, IceCandidatePayload>>()
    private val pendingRemoteAnswers = linkedSetOf<SignalingEnvelope>()
    private val localDescriptionPublished = CompletableDeferred<Unit>()
    private val localCandidates = Channel<IceCandidatePayload>(Channel.UNLIMITED)
    private var subscription: AutoCloseable? = null
    private var localCandidateSubscription: AutoCloseable? = null
    private var localCandidateJob: Job? = null
    private var dataChannelJob: Job? = null
    private var remoteOfferApplied = false
    private var remoteAnswerApplied = false
    private var answerPayload: SessionDescriptionPayload? = null

    fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) return
        onEvent("SIGNALING_STARTED")
        localCandidateSubscription = transport.observeLocalIceCandidates { candidate ->
            if (!closed.get() && !signalingStopped.get()) {
                onEvent("LOCAL_CANDIDATE_GENERATED")
                localCandidates.trySend(candidate)
            }
        }
        localCandidateJob = scope.launch {
            for (candidate in localCandidates) {
                localDescriptionPublished.await()
                if (closed.get() || signalingStopped.get()) continue
                runCatching {
                    publishWithRetry(
                        SignalingEnvelope(
                            matchId,
                            userId,
                            SignalingPayload.IceCandidate(
                                candidate.candidate,
                                candidate.sdpMid,
                                candidate.sdpMLineIndex,
                            ),
                        ),
                    )
                    onEvent("LOCAL_CANDIDATE_PUBLISHED")
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    if (!closed.get()) onError(error)
                }
            }
        }
        subscription = signaling.subscribe(
            matchId,
            onEnvelope = { envelope ->
                if (!closed.get() && envelope.senderUserId == opponentId) {
                    scope.launch { handle(envelope) }
                }
            },
            onError = { error -> if (!closed.get()) onError(error) },
        )
        if (offerer) scope.launch { createAndPublishOffer() }
    }

    private suspend fun createAndPublishOffer() {
        try {
            transport.provideOffererDataChannel()
            val offer = transport.createOffer()
            onEvent("LOCAL_OFFER_SET")
            publishWithRetry(SignalingEnvelope(matchId, userId, SignalingPayload.Offer(offer.sdp)))
            onEvent("OFFER_PUBLISHED")
            localDescriptionPublished.complete(Unit)
            val pending = signalingMutex.withLock {
                pendingRemoteAnswers.toList().also { pendingRemoteAnswers.clear() }
            }
            pending.forEach { handle(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!closed.get()) onError(error)
        }
    }

    private suspend fun handle(envelope: SignalingEnvelope) = signalingMutex.withLock {
        if (closed.get() || envelope.matchId != matchId) return@withLock
        if (envelope.payload is SignalingPayload.Answer && offerer && !localDescriptionPublished.isCompleted) {
            pendingRemoteAnswers += envelope
            return@withLock
        }
        if (!handledSignals.add(envelope)) return@withLock
        try {
            when (val payload = envelope.payload) {
                is SignalingPayload.Offer -> handleOffer(payload)
                is SignalingPayload.Answer -> handleAnswer(payload)
                is SignalingPayload.IceCandidate -> handleRemoteCandidate(envelope, payload)
            }
        } catch (error: CancellationException) {
            handledSignals.remove(envelope)
            throw error
        } catch (error: Exception) {
            handledSignals.remove(envelope)
            if (!closed.get()) onError(error)
        }
    }

    private suspend fun handleOffer(payload: SignalingPayload.Offer) {
        if (offerer) return
        if (!remoteOfferApplied) {
            transport.setRemoteDescription(SessionDescriptionPayload("OFFER", payload.sdp))
            remoteOfferApplied = true
            onEvent("REMOTE_OFFER_SET")
            drainRemoteCandidates()
        }
        val answer = answerPayload ?: transport.createAnswer().also { answerPayload = it }
        onEvent("LOCAL_ANSWER_SET")
        publishWithRetry(SignalingEnvelope(matchId, userId, SignalingPayload.Answer(answer.sdp)))
        onEvent("ANSWER_PUBLISHED")
        localDescriptionPublished.complete(Unit)
        beginDataChannelHandshake()
    }

    private suspend fun handleAnswer(payload: SignalingPayload.Answer) {
        if (!offerer) return
        if (!remoteAnswerApplied) {
            transport.setRemoteDescription(SessionDescriptionPayload("ANSWER", payload.sdp))
            remoteAnswerApplied = true
            onEvent("REMOTE_ANSWER_SET")
            drainRemoteCandidates()
        }
        beginDataChannelHandshake()
    }

    private suspend fun handleRemoteCandidate(
        envelope: SignalingEnvelope,
        payload: SignalingPayload.IceCandidate,
    ) {
        val candidate = IceCandidatePayload(payload.candidate, payload.sdpMid, payload.sdpMLineIndex)
        if (remoteDescriptionApplied()) {
            transport.addRemoteIceCandidate(candidate)
            onEvent("REMOTE_CANDIDATE_APPLIED")
        } else {
            queuedRemoteCandidates.addLast(envelope to candidate)
            onEvent("REMOTE_CANDIDATE_QUEUED")
        }
    }

    private suspend fun drainRemoteCandidates() {
        while (queuedRemoteCandidates.isNotEmpty()) {
            val (envelope, candidate) = queuedRemoteCandidates.removeFirst()
            try {
                transport.addRemoteIceCandidate(candidate)
                onEvent("QUEUED_REMOTE_CANDIDATE_APPLIED")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handledSignals.remove(envelope)
                if (!closed.get()) onError(error)
            }
        }
    }

    private fun remoteDescriptionApplied(): Boolean = if (offerer) remoteAnswerApplied else remoteOfferApplied

    private fun beginDataChannelHandshake() {
        if (dataChannelJob?.isActive == true || closed.get()) return
        dataChannelJob = scope.launch {
            try {
                transport.awaitDataChannelOpen()
                onEvent("DATA_CHANNEL_OPEN")
                repeat(startAcknowledgementAttempts) { attempt ->
                    if (onDataChannelOpen()) {
                        onEvent("START_ACK_CONFIRMED")
                        onEvent("PLAYING")
                        stopSignaling()
                        return@launch
                    }
                    if (attempt < startAcknowledgementAttempts - 1) delay(startAcknowledgementRetryMillis)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed.get()) onError(error)
            }
        }
    }

    private suspend fun publishWithRetry(envelope: SignalingEnvelope) {
        var lastError: Exception? = null
        repeat(publishRetryAttempts) { attempt ->
            if (closed.get() || signalingStopped.get()) throw CancellationException("signaling session closed")
            try {
                signaling.publish(envelope)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                if (attempt < publishRetryAttempts - 1) delay(publishRetryDelayMillis)
            }
        }
        throw lastError ?: IllegalStateException("signaling publish failed")
    }

    private fun stopSignaling() {
        if (!signalingStopped.compareAndSet(false, true)) return
        subscription?.close()
        subscription = null
        localCandidateSubscription?.close()
        localCandidateSubscription = null
        localCandidates.close()
        localCandidateJob?.cancel()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopSignaling()
        dataChannelJob?.cancel()
        sessionJob.cancel()
    }
}
