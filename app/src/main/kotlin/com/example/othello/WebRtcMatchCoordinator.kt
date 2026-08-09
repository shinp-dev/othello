package com.example.othello

import android.content.Context
import com.example.othello.data.supabase.SignalingEnvelope
import com.example.othello.data.supabase.SupabaseSignalingDataSource
import com.example.othello.game.Disc
import com.example.othello.match.OnlineMatchController
import com.example.othello.match.OnlineMatchRepository
import com.example.othello.match.MatchDiagnostics
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.transport.webrtc.AndroidWebRtcTransport
import com.example.othello.transport.webrtc.AndroidWebRtcTransportFactory
import com.example.othello.transport.webrtc.DefaultIceServers
import com.example.othello.transport.webrtc.SessionDescriptionPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the P2P session outside Compose so Activity recreation does not create a second peer. */
class WebRtcMatchCoordinator(
    context: Context,
    private val userId: String,
    private val assignment: MatchAssignment,
    private val signaling: SupabaseSignalingDataSource,
    private val repository: OnlineMatchRepository,
    scope: CoroutineScope,
    private val debugAutoPlay: Boolean = false,
    debugTimeControlMillis: Long? = null,
) : AutoCloseable {
    val matchId: String get() = assignment.matchId
    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
    private val transport = AndroidWebRtcTransportFactory(context.applicationContext)
        .create(assignment.matchId, DefaultIceServers.publicStun) as AndroidWebRtcTransport
    val controller = OnlineMatchController(
        assignment.matchId,
        if (assignment.assignedDisc.name == "BLACK") Disc.BLACK else Disc.WHITE,
        transport,
        repository,
        callbackScope = sessionScope,
        cancelCallbackScopeOnClose = false,
        timeControlMillis = debugTimeControlMillis ?: com.example.othello.match.DEFAULT_TIME_CONTROL_MILLIS,
    )

    fun diagnostics(): MatchDiagnostics = controller.diagnostics(userId, assignment.opponentId)
    private var subscription: AutoCloseable? = null
    private var autoPlaySubscription: AutoCloseable? = null
    private var autoPlayJob: Job? = null
    private var pendingResultRetryJob: Job? = null
    private var dataChannelJob: Job? = null
    private var autoPlayInFlight = false
    private var started = false
    private var closed = false
    private val signalingMutex = Mutex()
    private val handledSignals = mutableSetOf<String>()
    private var remoteOfferApplied = false
    private var remoteAnswerApplied = false
    private var answerPayload: SessionDescriptionPayload? = null

    fun start() {
        if (started) return
        started = true
        autoPlaySubscription = controller.observe { view ->
            if (debugAutoPlay && view.matchState.status == com.example.othello.match.MatchStatus.PLAYING &&
                    view.game.currentPlayer == view.localDisc &&
                    view.game.legalMoves.isNotEmpty() && !autoPlayInFlight
            ) {
                autoPlayInFlight = true
                autoPlayJob = sessionScope.launch {
                    try {
                        while (true) {
                            val next = controller.viewState
                            if (next.matchState.status != com.example.othello.match.MatchStatus.PLAYING ||
                                next.game.currentPlayer != next.localDisc || next.game.legalMoves.isEmpty()
                            ) break
                            if (!controller.play(next.game.legalMoves.first())) break
                        }
                    } finally {
                        autoPlayInFlight = false
                    }
                }
            } else if (view.matchState.status in setOf(
                    com.example.othello.match.MatchStatus.FINISHING,
                    com.example.othello.match.MatchStatus.PENDING_RESULT,
                )
            ) {
                schedulePendingResultRetry()
            } else if (view.matchState.status in setOf(
                    com.example.othello.match.MatchStatus.CONFIRMED,
                    com.example.othello.match.MatchStatus.DISPUTED,
                )
            ) {
                pendingResultRetryJob?.cancel()
            }
        }
        subscription = signaling.subscribe(
            assignment.matchId,
            onEnvelope = { envelope ->
                if (!closed && envelope.senderUserId == assignment.opponentId) {
                    sessionScope.launch { handle(envelope) }
                }
            },
            onError = { error ->
                if (!closed) sessionScope.launch {
                    controller.reportConnectionError(error.message ?: "signaling subscription failed")
                }
            },
        )
        if (assignment.assignedDisc.name == "BLACK") {
            sessionScope.launch {
                try {
                    transport.provideOffererDataChannel()
                    val offer = transport.createOffer()
                    publishWithRetry(SignalingEnvelope(assignment.matchId, userId, "OFFER", offer.sdp))
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!closed) controller.reportConnectionError(error.message ?: "offer failed")
                }
            }
        }
    }

    private fun schedulePendingResultRetry() {
        if (pendingResultRetryJob?.isActive == true || closed) return
        pendingResultRetryJob = sessionScope.launch {
            repeat(PENDING_RESULT_RETRY_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) 750L else PENDING_RESULT_RETRY_MILLIS)
                if (closed || controller.viewState.matchState.status !in setOf(
                        com.example.othello.match.MatchStatus.FINISHING,
                        com.example.othello.match.MatchStatus.PENDING_RESULT,
                    )
                ) {
                    return@launch
                }
                controller.retryFinish()
            }
        }
    }

    private suspend fun handle(envelope: SignalingEnvelope) = signalingMutex.withLock {
        if (closed || envelope.matchId != assignment.matchId) return@withLock
        val signalKey = "${envelope.senderUserId}|${envelope.type}|${envelope.sdp}|${envelope.protocolVersion}"
        if (!handledSignals.add(signalKey)) return@withLock
        try {
            when {
                envelope.type == "OFFER" && assignment.assignedDisc.name == "WHITE" -> {
                    if (!remoteOfferApplied) {
                        transport.setRemoteDescription(SessionDescriptionPayload("OFFER", envelope.sdp))
                        remoteOfferApplied = true
                    }
                    val answer = answerPayload ?: transport.createAnswer().also { answerPayload = it }
                    publishWithRetry(SignalingEnvelope(assignment.matchId, userId, "ANSWER", answer.sdp))
                }
                envelope.type == "ANSWER" && assignment.assignedDisc.name == "BLACK" -> {
                    if (!remoteAnswerApplied) {
                        transport.setRemoteDescription(SessionDescriptionPayload("ANSWER", envelope.sdp))
                        remoteAnswerApplied = true
                    }
                }
                else -> return@withLock
            }
            beginDataChannelHandshake()
        } catch (error: Exception) {
            handledSignals.remove(signalKey)
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (!closed) controller.reportConnectionError(error.message ?: "signaling failed")
        }
    }

    private suspend fun publishWithRetry(envelope: SignalingEnvelope) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                signaling.publish(envelope)
                return
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                if (attempt < 2) delay(500)
            }
        }
        throw lastError ?: IllegalStateException("signaling publish failed")
    }

    private fun beginDataChannelHandshake() {
        if (dataChannelJob?.isActive == true || closed) return
        dataChannelJob = sessionScope.launch {
            try {
                transport.awaitDataChannelOpen()
                repeat(3) { attempt ->
                    if (controller.onDataChannelOpen()) {
                        subscription?.close()
                        return@launch
                    }
                    if (attempt < 2) delay(1_000)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed) controller.reportConnectionError(error.message ?: "DataChannel start failed")
            }
        }
    }

    suspend fun leave() {
        when (controller.viewState.matchState.status) {
            com.example.othello.match.MatchStatus.PLAYING -> controller.finishForDisconnect()
            com.example.othello.match.MatchStatus.CONFIRMED,
            com.example.othello.match.MatchStatus.DISPUTED,
            com.example.othello.match.MatchStatus.PENDING_RESULT -> Unit
            else -> runCatching { repository.abandonMatch(assignment.matchId) }
        }
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        subscription?.close()
        autoPlaySubscription?.close()
        autoPlayJob?.cancel()
        pendingResultRetryJob?.cancel()
        dataChannelJob?.cancel()
        controller.close()
        sessionJob.cancel()
    }

    private companion object {
        const val PENDING_RESULT_RETRY_ATTEMPTS = 61
        const val PENDING_RESULT_RETRY_MILLIS = 5_000L
    }
}
