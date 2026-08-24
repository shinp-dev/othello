package com.example.othello

import android.content.Context
import com.example.othello.data.supabase.SignalingEnvelope
import com.example.othello.data.supabase.SupabaseSignalingDataSource
import com.example.othello.game.Disc
import com.example.othello.game.CanonicalMoves
import com.example.othello.match.OnlineMatchController
import com.example.othello.match.OnlineMatchRepository
import com.example.othello.match.MatchDiagnostics
import com.example.othello.match.OnlineMatchViewState
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.transport.webrtc.AndroidWebRtcTransport
import com.example.othello.transport.webrtc.AndroidWebRtcTransportFactory
import com.example.othello.transport.webrtc.DefaultIceServers
import com.example.othello.transport.webrtc.SessionDescriptionPayload
import com.example.othello.network.ClockSnapshot
import com.example.othello.network.TransportState
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
    private val recoveryStore: OnlineMatchRecoveryStore,
    private val recoveredSnapshot: OnlineMatchRecoverySnapshot? = null,
    scope: CoroutineScope,
    private val debugAutoPlay: Boolean = false,
    debugTimeControlMillis: Long? = null,
) : AutoCloseable {
    val matchId: String get() = assignment.matchId
    val opponentRating: Int? get() = assignment.opponentRating
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
        initialMoves = recoveredSnapshot?.canonicalMoves?.let(CanonicalMoves::decode).orEmpty(),
        initialClock = recoveredSnapshot?.adjustedClock(),
        initialPendingFinishReason = recoveredSnapshot?.pendingFinishReason,
        initialPendingLoserDisc = recoveredSnapshot?.pendingLoserDisc,
        initialPendingResultRequestId = recoveredSnapshot?.pendingResultRequestId,
        recoverySynchronizationRequired = recoveredSnapshot != null ||
            assignment.lifecycleStatus in setOf("ACTIVE", "RECONNECTING"),
        durableCheckpoint = ::persistRecoveryCheckpoint,
    )

    fun diagnostics(): MatchDiagnostics = controller.diagnostics(userId, assignment.opponentId)
    private var subscription: AutoCloseable? = null
    private var autoPlaySubscription: AutoCloseable? = null
    private var recoverySubscription: AutoCloseable? = null
    private var transportRecoverySubscription: AutoCloseable? = null
    private var autoPlayJob: Job? = null
    private var pendingResultRetryJob: Job? = null
    private var pendingResultReconciliationJob: Job? = null
    private var dataChannelJob: Job? = null
    private var renegotiationJob: Job? = null
    private var autoPlayInFlight = false
    private var started = false
    private var closed = false
    private val signalingMutex = Mutex()
    private val handledSignals = mutableSetOf<String>()
    private var remoteOfferApplied = false
    private var remoteAnswerApplied = false
    private var answerPayload: SessionDescriptionPayload? = null
    private var lastRecoveryFingerprint: String? = null
    private var currentNegotiationEpoch: Int = assignment.negotiationEpoch
    private var lastOfferEpoch: Int? = null
    private var preparedTransportEpoch: Int? = assignment.negotiationEpoch

    private fun persistRecoveryCheckpoint(view: OnlineMatchViewState): Boolean {
        val canonicalMoves = CanonicalMoves.encode(view.moves)
        val fingerprint = listOf(
            canonicalMoves,
            view.matchState.status.name,
            view.pendingFinishReason?.name,
            view.pendingLoserDisc?.name,
            view.pendingResultRequestId,
            currentNegotiationEpoch,
        ).joinToString("|")
        if (fingerprint == lastRecoveryFingerprint) return true
        val persisted = recoveryStore.save(
            OnlineMatchRecoverySnapshot(
                userId = userId,
                matchId = assignment.matchId,
                opponentId = assignment.opponentId,
                assignedDisc = assignment.assignedDisc,
                opponentRating = assignment.opponentRating,
                negotiationEpoch = currentNegotiationEpoch,
                canonicalMoves = canonicalMoves,
                stateHash = view.game.stateHash(),
                blackRemainingMillis = view.blackRemainingMillis,
                whiteRemainingMillis = view.whiteRemainingMillis,
                runningDisc = view.game.currentPlayer.takeIf {
                    view.matchState.status in setOf(
                        com.example.othello.match.MatchStatus.PLAYING,
                        com.example.othello.match.MatchStatus.MOVE_CONFIRMING,
                        com.example.othello.match.MatchStatus.SYNCHRONIZING,
                    )
                },
                pendingFinishReason = view.pendingFinishReason,
                pendingLoserDisc = view.pendingLoserDisc,
                pendingResultRequestId = view.pendingResultRequestId,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        if (persisted) lastRecoveryFingerprint = fingerprint
        return persisted
    }

    fun start() {
        if (started) return
        started = true
        recoverySubscription = controller.observe { view ->
            val terminal = view.matchState.status in setOf(
                com.example.othello.match.MatchStatus.CONFIRMED,
                com.example.othello.match.MatchStatus.FORFEIT,
                com.example.othello.match.MatchStatus.EXPIRED,
                com.example.othello.match.MatchStatus.ABANDONED,
                com.example.othello.match.MatchStatus.DISPUTED,
            )
            if (terminal) {
                recoveryStore.clear(assignment.matchId)
            } else {
                persistRecoveryCheckpoint(view)
            }
        }
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
            } else if (view.matchState.status == com.example.othello.match.MatchStatus.FINISHING && view.error != null) {
                schedulePendingResultRetry()
            } else if (view.matchState.status == com.example.othello.match.MatchStatus.PENDING_RESULT) {
                schedulePendingResultReconciliation()
            } else if (view.matchState.status in setOf(
                    com.example.othello.match.MatchStatus.CONFIRMED,
                    com.example.othello.match.MatchStatus.FORFEIT,
                    com.example.othello.match.MatchStatus.EXPIRED,
                    com.example.othello.match.MatchStatus.ABANDONED,
                    com.example.othello.match.MatchStatus.DISPUTED,
                )
            ) {
                pendingResultRetryJob?.cancel()
                pendingResultReconciliationJob?.cancel()
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
        transportRecoverySubscription = transport.observeState { next ->
            if (!closed && next in setOf(TransportState.DISCONNECTED, TransportState.FAILED, TransportState.CLOSED)) {
                preparedTransportEpoch = null
                scheduleTransportRenegotiation()
            }
        }
        if (recoveredSnapshot?.pendingFinishReason != null) {
            // The result outbox is server-facing and must not wait for WebRTC to recover.
            sessionScope.launch { controller.retryFinish() }
        } else {
            sessionScope.launch { beginInitialOrRecoveredNegotiation() }
        }
    }

    private suspend fun beginInitialOrRecoveredNegotiation() {
        signalingMutex.withLock {
            try {
                if (assignment.lifecycleStatus == "RESULT_PENDING") {
                    controller.reconcileServerState()
                    return
                }
                val recovering = assignment.lifecycleStatus in setOf("ACTIVE", "RECONNECTING")
                if (recovering) {
                    val state = repository.resumeMatch(assignment.matchId)
                    currentNegotiationEpoch = state.negotiationEpoch
                    if (state.serverStatus !in setOf("ACTIVE", "RECONNECTING")) {
                        controller.reconcileServerState()
                        return
                    }
                }
                if (assignment.assignedDisc.name == "BLACK") {
                    publishOffer(restarting = recovering)
                } else if (recovering) {
                    ensureTransportGeneration(offerer = false)
                    publishWithRetry(signal("RESUME", "resume"))
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed) controller.reportConnectionError(error.message ?: "connection negotiation failed")
            }
        }
    }

    private suspend fun publishOffer(restarting: Boolean) {
        if (restarting) ensureTransportGeneration(offerer = true)
        else transport.provideOffererDataChannel()
        remoteAnswerApplied = false
        val offer = transport.createOffer()
        publishWithRetry(signal("OFFER", offer.sdp))
        lastOfferEpoch = currentNegotiationEpoch
    }

    private fun ensureTransportGeneration(offerer: Boolean) {
        if (preparedTransportEpoch == currentNegotiationEpoch) {
            if (offerer) transport.provideOffererDataChannel()
            return
        }
        dataChannelJob?.cancel()
        dataChannelJob = null
        transport.prepareForRenegotiation(offerer)
        preparedTransportEpoch = currentNegotiationEpoch
    }

    private fun schedulePendingResultRetry() {
        if (pendingResultRetryJob?.isActive == true || closed) return
        pendingResultRetryJob = sessionScope.launch {
            repeat(RESULT_RETRY_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) 750L else PENDING_RESULT_RETRY_MILLIS)
                if (closed || controller.viewState.matchState.status !in setOf(
                        com.example.othello.match.MatchStatus.FINISHING,
                    )
                ) {
                    return@launch
                }
                controller.retryFinish()
            }
        }
    }

    private fun schedulePendingResultReconciliation() {
        if (pendingResultReconciliationJob?.isActive == true || closed) return
        pendingResultReconciliationJob = sessionScope.launch {
            for (reconciliationDelay in RESULT_RECONCILIATION_DELAYS_MILLIS) {
                delay(reconciliationDelay)
                if (closed || controller.viewState.matchState.status != com.example.othello.match.MatchStatus.PENDING_RESULT) {
                    return@launch
                }
                try {
                    controller.reconcileServerState()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    controller.reportConnectionError(error.message ?: "result reconciliation failed")
                    // One final bounded retry below covers a transient deadline read or
                    // network loss without bringing back continuous result polling.
                }
            }
        }
    }

    private fun scheduleTransportRenegotiation(force: Boolean = false) {
        if (renegotiationJob?.isActive == true || closed) return
        renegotiationJob = sessionScope.launch {
            // ICE DISCONNECTED is sometimes transient during network handover. Give the
            // existing candidate pair a short chance to recover before writing signaling.
            delay(1_500)
            if (closed || (!force && transport.diagnostics().state == TransportState.OPEN)) return@launch
            if (controller.viewState.matchState.status !in setOf(
                    com.example.othello.match.MatchStatus.P2P_CONNECTED,
                    com.example.othello.match.MatchStatus.PLAYING,
                    com.example.othello.match.MatchStatus.MOVE_CONFIRMING,
                    com.example.othello.match.MatchStatus.SYNCHRONIZING,
                    com.example.othello.match.MatchStatus.RECONNECTING,
                )
            ) return@launch
            repeat(RENEGOTIATION_ATTEMPTS) { attempt ->
                try {
                    signalingMutex.withLock {
                        val connectingInitially = controller.viewState.matchState.status ==
                            com.example.othello.match.MatchStatus.P2P_CONNECTED
                        if (connectingInitially) {
                            val startState = repository.getMatchStartState(assignment.matchId)
                            currentNegotiationEpoch = startState.negotiationEpoch
                            if (startState.serverStatus in TERMINAL_SERVER_STATUSES) {
                                controller.reconcileServerState()
                                return@launch
                            }
                        } else {
                            val resumed = repository.resumeMatch(assignment.matchId)
                            currentNegotiationEpoch = resumed.negotiationEpoch
                            if (resumed.serverStatus !in setOf("ACTIVE", "RECONNECTING")) {
                                controller.reconcileServerState()
                                return@launch
                            }
                        }
                        handledSignals.clear()
                        remoteOfferApplied = false
                        remoteAnswerApplied = false
                        answerPayload = null
                        if (assignment.assignedDisc.name == "BLACK") {
                            publishOffer(restarting = true)
                        } else {
                            ensureTransportGeneration(offerer = false)
                            publishWithRetry(signal("RESUME", "resume"))
                        }
                    }
                    return@launch
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!closed) controller.reportConnectionError(error.message ?: "connection recovery failed")
                    if (attempt + 1 < RENEGOTIATION_ATTEMPTS) delay(RENEGOTIATION_BACKOFF_MILLIS * (attempt + 1))
                }
            }
        }
    }

    suspend fun retryCurrentOperation() {
        try {
            when (controller.viewState.matchState.status) {
                com.example.othello.match.MatchStatus.FINISHING -> controller.retryFinish()
                com.example.othello.match.MatchStatus.PENDING_RESULT -> controller.reconcileServerState()
                com.example.othello.match.MatchStatus.MOVE_CONFIRMING -> controller.retryPendingMove()
                com.example.othello.match.MatchStatus.SYNCHRONIZING -> controller.retrySynchronization()
                com.example.othello.match.MatchStatus.PLAYING -> controller.retrySynchronization()
                com.example.othello.match.MatchStatus.P2P_CONNECTED -> {
                    if (transport.diagnostics().state == TransportState.OPEN) beginDataChannelHandshake()
                    else {
                        renegotiationJob?.cancel()
                        renegotiationJob = null
                        scheduleTransportRenegotiation(force = true)
                    }
                }
                com.example.othello.match.MatchStatus.RECONNECTING -> {
                    renegotiationJob?.cancel()
                    renegotiationJob = null
                    scheduleTransportRenegotiation(force = true)
                }
                else -> Unit
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            controller.reportConnectionError(error.message ?: "recovery failed")
        }
    }

    private suspend fun handle(envelope: SignalingEnvelope) = signalingMutex.withLock {
        if (closed || envelope.matchId != assignment.matchId) return@withLock
        if (envelope.negotiationEpoch < currentNegotiationEpoch) return@withLock
        val newerEpoch = envelope.negotiationEpoch > currentNegotiationEpoch
        if (newerEpoch) {
            currentNegotiationEpoch = envelope.negotiationEpoch
            handledSignals.clear()
            remoteOfferApplied = false
            remoteAnswerApplied = false
            answerPayload = null
        }
        val signalKey = "${envelope.negotiationEpoch}|${envelope.senderUserId}|${envelope.type}|${envelope.sdp}|${envelope.protocolVersion}"
        if (!handledSignals.add(signalKey)) return@withLock
        try {
            when {
                envelope.type == "RESUME" && assignment.assignedDisc.name == "BLACK" -> {
                    if (lastOfferEpoch != currentNegotiationEpoch) publishOffer(restarting = true)
                }
                envelope.type == "OFFER" && assignment.assignedDisc.name == "WHITE" -> {
                    if (newerEpoch || preparedTransportEpoch != currentNegotiationEpoch) {
                        ensureTransportGeneration(offerer = false)
                    }
                    if (!remoteOfferApplied) {
                        transport.setRemoteDescription(SessionDescriptionPayload("OFFER", envelope.sdp))
                        remoteOfferApplied = true
                    }
                    val answer = answerPayload ?: transport.createAnswer().also { answerPayload = it }
                    publishWithRetry(signal("ANSWER", answer.sdp))
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

    private fun signal(type: String, sdp: String) = SignalingEnvelope(
        matchId = assignment.matchId,
        senderUserId = userId,
        type = type,
        sdp = sdp,
        negotiationEpoch = currentNegotiationEpoch,
    )

    private fun beginDataChannelHandshake() {
        if (dataChannelJob?.isActive == true || closed) return
        dataChannelJob = sessionScope.launch {
            try {
                transport.awaitDataChannelOpen()
                repeat(3) { attempt ->
                    if (controller.onDataChannelOpen()) {
                        return@launch
                    }
                    if (attempt < 2) delay(1_000)
                }
                controller.reportConnectionError("開始ACKの確認期限を超えました")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed) controller.reportConnectionError(error.message ?: "DataChannel start failed")
                if (!closed) {
                    preparedTransportEpoch = null
                    scheduleTransportRenegotiation(force = true)
                }
            }
        }
    }

    suspend fun leave() {
        when (controller.viewState.matchState.status) {
            com.example.othello.match.MatchStatus.PLAYING,
            com.example.othello.match.MatchStatus.MOVE_CONFIRMING,
            com.example.othello.match.MatchStatus.SYNCHRONIZING,
            com.example.othello.match.MatchStatus.RECONNECTING -> controller.leaveActiveMatch()
            com.example.othello.match.MatchStatus.FINISHING,
            com.example.othello.match.MatchStatus.CONFIRMED,
            com.example.othello.match.MatchStatus.FORFEIT,
            com.example.othello.match.MatchStatus.EXPIRED,
            com.example.othello.match.MatchStatus.ABANDONED,
            com.example.othello.match.MatchStatus.DISPUTED,
            com.example.othello.match.MatchStatus.PENDING_RESULT -> Unit
            else -> try {
                repository.abandonMatch(assignment.matchId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                Unit
            }
        }
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        subscription?.close()
        autoPlaySubscription?.close()
        recoverySubscription?.close()
        transportRecoverySubscription?.close()
        autoPlayJob?.cancel()
        pendingResultRetryJob?.cancel()
        pendingResultReconciliationJob?.cancel()
        dataChannelJob?.cancel()
        renegotiationJob?.cancel()
        controller.close()
        sessionJob.cancel()
    }

    private companion object {
        const val RESULT_RETRY_ATTEMPTS = 3
        const val PENDING_RESULT_RETRY_MILLIS = 2_000L
        val RESULT_RECONCILIATION_DELAYS_MILLIS = listOf(2_000L, 43_500L)
        const val RENEGOTIATION_ATTEMPTS = 3
        const val RENEGOTIATION_BACKOFF_MILLIS = 1_000L
        val TERMINAL_SERVER_STATUSES = setOf("CONFIRMED", "FORFEIT", "EXPIRED", "ABANDONED", "DISPUTED")
    }
}
