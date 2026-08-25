package com.example.othello

import android.content.Context
import com.example.othello.data.supabase.SignalingEnvelope
import com.example.othello.data.supabase.SupabaseSignalingDataSource
import com.example.othello.game.CanonicalMoves
import com.example.othello.game.Disc
import com.example.othello.match.MatchStartAck
import com.example.othello.match.OnlineMatchController
import com.example.othello.match.OnlineMatchRepository
import com.example.othello.match.MatchDiagnostics
import com.example.othello.match.OnlineMatchViewState
import com.example.othello.match.ReconnectEpochProgress
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.transport.webrtc.AndroidWebRtcTransport
import com.example.othello.transport.webrtc.AndroidWebRtcTransportFactory
import com.example.othello.transport.webrtc.DefaultIceServers
import com.example.othello.transport.webrtc.SessionDescriptionPayload
import com.example.othello.network.ClockSnapshot
import com.example.othello.network.MAX_MATCH_NEGOTIATION_EPOCH
import com.example.othello.network.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ReleaseRenegotiationAction {
    SKIP_TRANSIENT_RECOVERY,
    SIGNAL_CURRENT_EPOCH,
    START_NEW_EPOCH,
    SYNCHRONIZE_CURRENT_EPOCH,
    RECONCILE_TERMINAL,
}

internal fun planReleaseRenegotiation(
    force: Boolean,
    transportState: TransportState,
    freshReconnectEpochRequired: Boolean,
    adoptedReconnectEpoch: Int?,
    completedReconnectEpoch: Int?,
    currentEpoch: Int,
    serverStatus: String,
    serverEpoch: Int,
    serverLocalAcked: Boolean,
    serverBothAcked: Boolean,
): ReleaseRenegotiationAction = when {
    serverStatus in setOf("CONFIRMED", "FORFEIT", "EXPIRED", "ABANDONED", "DISPUTED", "RESULT_PENDING") ->
        ReleaseRenegotiationAction.RECONCILE_TERMINAL
    serverStatus == "RECONNECTING" -> ReleaseRenegotiationAction.SIGNAL_CURRENT_EPOCH
    serverStatus == "MATCHED" -> ReleaseRenegotiationAction.SIGNAL_CURRENT_EPOCH
    // A locally observed disconnect that has not yet been consumed by an adopted
    // server epoch is a genuinely fresh reconnect request. It must win-or-join the
    // row-locked ACTIVE -> RECONNECTING transition even if the old channel reopened.
    serverStatus == "ACTIVE" && freshReconnectEpochRequired ->
        ReleaseRenegotiationAction.START_NEW_EPOCH
    // ACTIVE is authoritative proof that both ACKs committed for its current epoch.
    // The explicit ACK columns make that invariant visible to the client and prevent
    // an ambiguous/lost ACK HTTP response or a delayed forced retry from consuming
    // the following epoch. A genuinely fresh disconnect is handled above first.
    serverStatus == "ACTIVE" && !freshReconnectEpochRequired &&
        transportState == TransportState.OPEN &&
        serverLocalAcked && serverBothAcked &&
        (serverEpoch > currentEpoch || adoptedReconnectEpoch == serverEpoch ||
            completedReconnectEpoch == serverEpoch) ->
        ReleaseRenegotiationAction.SYNCHRONIZE_CURRENT_EPOCH
    serverStatus == "ACTIVE" && serverEpoch < currentEpoch ->
        ReleaseRenegotiationAction.RECONCILE_TERMINAL
    serverStatus == "ACTIVE" && transportState == TransportState.OPEN && !force ->
        ReleaseRenegotiationAction.SKIP_TRANSIENT_RECOVERY
    serverStatus == "ACTIVE" -> ReleaseRenegotiationAction.START_NEW_EPOCH
    else -> ReleaseRenegotiationAction.RECONCILE_TERMINAL
}

internal fun shouldAdoptReconnectEpochFromSignal(
    negotiationEpoch: Int,
    progress: ReconnectEpochProgress,
): Boolean = negotiationEpoch > 0 &&
    progress.adoptedEpoch != negotiationEpoch &&
    progress.completedEpoch != negotiationEpoch

internal fun shouldRequestReconnectEpoch(
    action: ReleaseRenegotiationAction,
    serverStatus: String,
): Boolean = action == ReleaseRenegotiationAction.START_NEW_EPOCH ||
    (action == ReleaseRenegotiationAction.SIGNAL_CURRENT_EPOCH && serverStatus == "RECONNECTING")

internal suspend fun requestReconnectEpochIfRequired(
    action: ReleaseRenegotiationAction,
    serverStatus: String,
    request: suspend () -> MatchStartAck,
): MatchStartAck? = if (shouldRequestReconnectEpoch(action, serverStatus)) request() else null

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
        initialNegotiationEpoch = assignment.negotiationEpoch,
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
            view.toRecoverySnapshot(
                userId = userId,
                assignment = assignment,
                negotiationEpoch = currentNegotiationEpoch,
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
                    val state = requestReconnectEpoch(
                        expectedEpoch = currentNegotiationEpoch,
                        acceptCompletedEpochWhenTransportOpen = false,
                    )
                    currentNegotiationEpoch = state.negotiationEpoch
                    if (state.serverStatus !in setOf("ACTIVE", "RECONNECTING")) {
                        controller.reconcileServerState()
                        return
                    }
                    if (state.serverStatus == "ACTIVE" && state.localAcked && state.bothAcked) {
                        controller.adoptAuthoritativeReconnectEpoch(
                            state.negotiationEpoch,
                            state.localAcked,
                            state.bothAcked,
                        )
                        controller.reconcileAuthoritativeStartState(state)
                        return
                    }
                    controller.adoptAuthoritativeReconnectEpoch(
                        state.negotiationEpoch,
                        state.localAcked,
                        state.bothAcked,
                    )
                }
                if (assignment.assignedDisc.name == "BLACK") {
                    publishOffer(restarting = recovering)
                } else if (recovering) {
                    ensureTransportGeneration(offerer = false)
                    controller.markReconnectSignalingStarted(currentNegotiationEpoch)
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
        if (restarting) {
            ensureTransportGeneration(offerer = true)
            controller.markReconnectSignalingStarted(currentNegotiationEpoch)
        } else {
            transport.provideOffererDataChannel()
        }
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

    /**
     * Resume is conditional on the epoch that was read before the RPC. If another
     * participant advances and fully ACKs an epoch before this request obtains the
     * row lock, the server returns that newer ACTIVE epoch without mutating it. An
     * already-open channel can synchronize it; otherwise this process still needs a
     * genuinely fresh reconnect and retries from the newly authoritative epoch.
     */
    private suspend fun requestReconnectEpoch(
        expectedEpoch: Int,
        acceptCompletedEpochWhenTransportOpen: Boolean,
    ): MatchStartAck {
        var expected = expectedEpoch
        var latest: MatchStartAck? = null
        repeat(MAX_MATCH_NEGOTIATION_EPOCH + 2) {
            val state = repository.resumeMatch(assignment.matchId, expected)
            latest = state
            if (state.serverStatus != "ACTIVE" || state.negotiationEpoch == expected ||
                (acceptCompletedEpochWhenTransportOpen &&
                    transport.diagnostics().state == TransportState.OPEN)
            ) {
                return state
            }
            expected = state.negotiationEpoch
        }
        return checkNotNull(latest)
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
            // existing candidate pair a short chance to recover, then consult the server
            // before deciding whether this was transient. OPEN alone is not authoritative:
            // another callback may already have advanced the server negotiation epoch.
            delay(1_500)
            if (closed) return@launch
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
                        currentNegotiationEpoch = maxOf(
                            currentNegotiationEpoch,
                            controller.reconnectEpochProgress.authoritativeEpoch,
                        )
                        val observedState = repository.getMatchStartState(assignment.matchId)
                        val progress = controller.reconnectEpochProgress
                        val action = planReleaseRenegotiation(
                            force = force,
                            transportState = transport.diagnostics().state,
                            freshReconnectEpochRequired = progress.freshEpochRequired,
                            adoptedReconnectEpoch = progress.adoptedEpoch,
                            completedReconnectEpoch = progress.completedEpoch,
                            currentEpoch = currentNegotiationEpoch,
                            serverStatus = observedState.serverStatus,
                            serverEpoch = observedState.negotiationEpoch,
                            serverLocalAcked = observedState.localAcked,
                            serverBothAcked = observedState.bothAcked,
                        )
                        currentNegotiationEpoch = observedState.negotiationEpoch
                        when (action) {
                            ReleaseRenegotiationAction.SKIP_TRANSIENT_RECOVERY -> return@launch
                            ReleaseRenegotiationAction.SYNCHRONIZE_CURRENT_EPOCH -> {
                                controller.adoptAuthoritativeReconnectEpoch(
                                    observedState.negotiationEpoch,
                                    observedState.localAcked,
                                    observedState.bothAcked,
                                )
                                controller.reconcileAuthoritativeStartState(observedState)
                                return@launch
                            }
                            ReleaseRenegotiationAction.RECONCILE_TERMINAL -> {
                                controller.reconcileServerState()
                                return@launch
                            }
                            ReleaseRenegotiationAction.START_NEW_EPOCH,
                            ReleaseRenegotiationAction.SIGNAL_CURRENT_EPOCH,
                            -> Unit
                        }
                        val resumedState = requestReconnectEpochIfRequired(
                            action,
                            observedState.serverStatus,
                        ) {
                            requestReconnectEpoch(
                                expectedEpoch = observedState.negotiationEpoch,
                                acceptCompletedEpochWhenTransportOpen = true,
                            )
                        }
                        if (resumedState != null) {
                            currentNegotiationEpoch = resumedState.negotiationEpoch
                            if (resumedState.serverStatus !in setOf("ACTIVE", "RECONNECTING")) {
                                controller.reconcileServerState()
                                return@launch
                            }
                            controller.adoptAuthoritativeReconnectEpoch(
                                resumedState.negotiationEpoch,
                                resumedState.localAcked,
                                resumedState.bothAcked,
                            )
                            if (resumedState.serverStatus == "ACTIVE" &&
                                resumedState.localAcked && resumedState.bothAcked
                            ) {
                                controller.reconcileAuthoritativeStartState(resumedState)
                                return@launch
                            }
                        }
                        handledSignals.clear()
                        remoteOfferApplied = false
                        remoteAnswerApplied = false
                        answerPayload = null
                        controller.markReconnectSignalingStarted(currentNegotiationEpoch)
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
        currentNegotiationEpoch = maxOf(
            currentNegotiationEpoch,
            controller.reconnectEpochProgress.authoritativeEpoch,
        )
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
            val acceptedForRole =
                (envelope.type == "RESUME" && assignment.assignedDisc.name == "BLACK") ||
                    (envelope.type == "OFFER" && assignment.assignedDisc.name == "WHITE") ||
                    (envelope.type == "ANSWER" && assignment.assignedDisc.name == "BLACK")
            if (!acceptedForRole) return@withLock
            val progress = controller.reconnectEpochProgress
            if (progress.adoptedEpoch == null &&
                progress.completedEpoch == currentNegotiationEpoch &&
                !progress.freshEpochRequired
            ) {
                return@withLock
            }
            if (shouldAdoptReconnectEpochFromSignal(currentNegotiationEpoch, progress)) {
                // A peer can legitimately be the only side that observed DISCONNECTED.
                // Receiving current-epoch signaling adopts that server-authoritative
                // negotiation without manufacturing a local disconnect claim.
                controller.adoptAuthoritativeReconnectEpoch(currentNegotiationEpoch)
            }
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
            controller.markReconnectSignalingStarted(currentNegotiationEpoch)
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
        val handshakeEpoch = currentNegotiationEpoch
        dataChannelJob = sessionScope.launch {
            try {
                transport.awaitDataChannelOpen()
                repeat(3) { attempt ->
                    if (controller.onDataChannelOpen(handshakeEpoch)) {
                        return@launch
                    }
                    if (attempt < 2) delay(1_000)
                }
                if (controller.reconnectEpochProgress.authoritativeEpoch > handshakeEpoch) {
                    currentNegotiationEpoch = controller.reconnectEpochProgress.authoritativeEpoch
                    preparedTransportEpoch = null
                    scheduleTransportRenegotiation(force = true)
                    return@launch
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
