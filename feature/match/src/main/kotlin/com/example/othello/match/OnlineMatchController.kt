package com.example.othello.match

import com.example.othello.game.CanonicalMoves
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.TurnResolver
import com.example.othello.network.MatchTransport
import com.example.othello.network.MAX_MATCH_NEGOTIATION_EPOCH
import com.example.othello.network.MoveAck
import com.example.othello.network.MoveCommand
import com.example.othello.network.MoveCommandValidator
import com.example.othello.network.CommandValidation
import com.example.othello.network.ClockSnapshot
import com.example.othello.network.FinishCommand
import com.example.othello.network.FinishCommandValidation
import com.example.othello.network.FinishCommandValidator
import com.example.othello.network.FinishSignalReason
import com.example.othello.network.ProtocolViolation
import com.example.othello.network.TransportState
import com.example.othello.network.SyncMessage
import com.example.othello.network.SyncMessageType
import com.example.othello.records.FinishReason
import com.example.othello.records.MatchResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val DEFAULT_TIME_CONTROL_MILLIS: Long = 5 * 60 * 1_000L

data class OnlineMatchViewState(
    val matchState: MatchState = MatchState(),
    val game: GameState = GameState(),
    val localDisc: Disc = Disc.BLACK,
    val opponentId: String? = null,
    val moves: List<Position?> = emptyList(),
    val message: String = "接続待ち",
    val error: String? = null,
    val commandCountSent: Int = 0,
    val commandCountReceived: Int = 0,
    val localStartAcked: Boolean = false,
    val bothStartAcked: Boolean = false,
    val finishResult: MatchFinishResult? = null,
    val blackRemainingMillis: Long = DEFAULT_TIME_CONTROL_MILLIS,
    val whiteRemainingMillis: Long = DEFAULT_TIME_CONTROL_MILLIS,
    val awaitingMoveAck: Boolean = false,
    val pendingFinishReason: FinishReason? = null,
    val pendingLoserDisc: Disc? = null,
    val pendingResultRequestId: String? = null,
)

data class ReconnectEpochProgress(
    val authoritativeEpoch: Int = 0,
    val adoptedEpoch: Int? = null,
    val completedEpoch: Int? = null,
    val signalingStarted: Boolean = false,
    val ackRequestSent: Boolean = false,
    val serverLocalAcked: Boolean = false,
    val serverBothAcked: Boolean = false,
    val localDisconnectObserved: Boolean = false,
    val freshEpochRequired: Boolean = false,
) {
    init {
        require(authoritativeEpoch in 0..MAX_MATCH_NEGOTIATION_EPOCH)
        require(adoptedEpoch == null || adoptedEpoch in 0..MAX_MATCH_NEGOTIATION_EPOCH)
        require(completedEpoch == null || completedEpoch in 0..MAX_MATCH_NEGOTIATION_EPOCH)
    }

    val passiveParticipation: Boolean
        get() = adoptedEpoch != null && !localDisconnectObserved
}

/** Coordinates local game rules, DataChannel validation and result submission. */
class OnlineMatchController(
    private val matchId: String,
    private val localDisc: Disc,
    private val transport: MatchTransport,
    private val repository: OnlineMatchRepository,
    private val ackAttempts: Int = 3,
    private val startConfirmationAttempts: Int = 3,
    private val startConfirmationDelayMillis: Long = 250,
    private val timeControlMillis: Long = DEFAULT_TIME_CONTROL_MILLIS,
    initialMoves: List<Position?> = emptyList(),
    private val initialClock: ClockSnapshot? = null,
    initialPendingFinishReason: FinishReason? = null,
    initialPendingLoserDisc: Disc? = null,
    initialPendingResultRequestId: String? = null,
    recoverySynchronizationRequired: Boolean = false,
    initialNegotiationEpoch: Int = 0,
    private val deliveryRetryAttempts: Int = 3,
    private val deliveryAckTimeoutMillis: Long = 750,
    private val synchronizationTimeoutMillis: Long = 3_000,
    private val reconnectGraceMillis: Long = 45_000,
    private val disconnectDebounceMillis: Long = 1_500,
    private val disconnectReportRetryMillis: Long = 2_000,
    monotonicNowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val durableCheckpoint: (OnlineMatchViewState) -> Boolean = { true },
    private val callbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val cancelCallbackScopeOnClose: Boolean = true,
) : AutoCloseable {
    private val recoveredGame = replay(initialMoves)
    private var state = OnlineMatchViewState(
        matchState = MatchState(
            if (initialPendingFinishReason == null) MatchStatus.P2P_CONNECTED else MatchStatus.FINISHING,
        ),
        game = recoveredGame,
        localDisc = localDisc,
        moves = initialMoves,
        blackRemainingMillis = initialClock?.blackRemainingMillis ?: timeControlMillis,
        whiteRemainingMillis = initialClock?.whiteRemainingMillis ?: timeControlMillis,
        pendingFinishReason = initialPendingFinishReason,
        pendingLoserDisc = initialPendingLoserDisc,
        pendingResultRequestId = initialPendingResultRequestId,
        message = if (initialPendingFinishReason == null) "接続待ち" else "結果を送信中",
    )
    private val listeners = mutableSetOf<(OnlineMatchViewState) -> Unit>()
    private val validator = MoveCommandValidator(matchId, localDisc.opponent())
    private val finishValidator = FinishCommandValidator(matchId, localDisc.opponent())
    private val transportSubscription: AutoCloseable
    private val finishSubscription: AutoCloseable
    private val moveAckSubscription: AutoCloseable
    private val syncSubscription: AutoCloseable
    private val stateSubscription: AutoCloseable
    private val startMutex = Mutex()
    private val finishMutex = Mutex()
    private val actionMutex = Mutex()
    private val matchClock = MatchClock(timeControlMillis, monotonicNowMillis)
    private var timeoutJob: Job? = null
    private var deliveryRetryJob: Job? = null
    private var synchronizationJob: Job? = null
    private var reconnectJob: Job? = null
    private var disconnectDebounceJob: Job? = null
    private var disconnectReportRetryJob: Job? = null
    private var localAckRecorded = false
    private var activeSubmission: MatchSubmission? = initialPendingFinishReason?.let { reason ->
        val loser = initialPendingLoserDisc
        MatchSubmission(
            matchId = matchId,
            canonicalMoves = CanonicalMoves.encode(initialMoves),
            result = loser?.let(::winnerForLoser) ?: resultForGame(recoveredGame),
            finalPositionHash = recoveredGame.stateHash(),
            finishReason = reason,
            loserDisc = loser,
            clockPayload = initialClock?.let {
                "{\"blackRemainingMillis\":${it.blackRemainingMillis},\"whiteRemainingMillis\":${it.whiteRemainingMillis}}"
            },
            requestId = initialPendingResultRequestId ?: UUID.randomUUID().toString(),
        )
    }
    private var lastFinishResult: MatchFinishResult? = null
    private var started = false
    private var closed = false
    private var transportClosed = false
    private var lastTransportState = TransportState.NEW
    private var pendingMove: PendingMove? = null
    private var pendingDisconnectClaim: MatchSubmission? = null
    private var recoverySyncPending = recoverySynchronizationRequired
    private var reconnectProgress = ReconnectEpochProgress(authoritativeEpoch = initialNegotiationEpoch)
    private var pendingSyncRequestId: String? = null
    private val peerSyncRequestIds = LinkedHashSet<String>()
    private val receivedMoveAcks = mutableMapOf<String, MoveAck>()
    private val pendingRemoteCommands = ArrayDeque<MoveCommand>()
    private val pendingRemoteFinishes = ArrayDeque<FinishCommand>()

    init {
        transportSubscription = transport.observe { command ->
            callbackScope.launch { onRemoteCommand(command) }
        }
        finishSubscription = transport.observeFinish { command ->
            callbackScope.launch { onRemoteFinish(command) }
        }
        moveAckSubscription = transport.observeMoveAck { ack ->
            callbackScope.launch { onMoveAck(ack) }
        }
        syncSubscription = transport.observeSync { message ->
            callbackScope.launch { onSyncMessage(message) }
        }
        stateSubscription = transport.observeState { next ->
            callbackScope.launch {
                lastTransportState = next
                when (next) {
                    TransportState.DISCONNECTED -> scheduleDisconnectDebounce()
                    TransportState.FAILED, TransportState.CLOSED -> {
                        disconnectDebounceJob?.cancel()
                        disconnectDebounceJob = null
                        handleTransportTermination()
                    }
                    TransportState.OPEN -> {
                        disconnectDebounceJob?.cancel()
                        disconnectDebounceJob = null
                        val adoptedEpoch = reconnectProgress.adoptedEpoch
                        if (started && state.matchState.status == MatchStatus.RECONNECTING && adoptedEpoch != null) {
                            handleTransportRecovered(adoptedEpoch)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    val viewState: OnlineMatchViewState get() = state
    val reconnectEpochProgress: ReconnectEpochProgress get() = reconnectProgress

    fun observe(listener: (OnlineMatchViewState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    suspend fun adoptAuthoritativeReconnectEpoch(
        negotiationEpoch: Int,
        serverLocalAcked: Boolean = false,
        serverBothAcked: Boolean = false,
    ) = actionMutex.withLock {
        require(negotiationEpoch in 0..MAX_MATCH_NEGOTIATION_EPOCH)
        if (closed || state.matchState.status.isTerminal()) return@withLock
        if (started && state.matchState.status !in ACTIVE_SYNC_STATUSES &&
            state.matchState.status != MatchStatus.RECONNECTING
        ) {
            return@withLock
        }
        require(negotiationEpoch >= reconnectProgress.authoritativeEpoch) {
            "authoritative reconnect epoch cannot move backwards"
        }
        if (started && state.matchState.status == MatchStatus.PLAYING &&
            reconnectProgress.completedEpoch == negotiationEpoch &&
            !reconnectProgress.freshEpochRequired
        ) {
            return@withLock
        }
        val sameAdoption = reconnectProgress.adoptedEpoch == negotiationEpoch
        val freshEpochConsumed = !sameAdoption && reconnectProgress.freshEpochRequired
        val localDisconnectObserved = if (sameAdoption) {
            reconnectProgress.localDisconnectObserved
        } else {
            reconnectProgress.freshEpochRequired
        }
        reconnectProgress = ReconnectEpochProgress(
            authoritativeEpoch = negotiationEpoch,
            adoptedEpoch = negotiationEpoch,
            completedEpoch = reconnectProgress.completedEpoch,
            signalingStarted = sameAdoption && reconnectProgress.signalingStarted,
            ackRequestSent = sameAdoption && reconnectProgress.ackRequestSent,
            serverLocalAcked = serverLocalAcked || (sameAdoption && reconnectProgress.serverLocalAcked),
            serverBothAcked = serverBothAcked || (sameAdoption && reconnectProgress.serverBothAcked),
            localDisconnectObserved = localDisconnectObserved,
            freshEpochRequired = false,
        )
        if (freshEpochConsumed) {
            disconnectReportRetryJob?.cancel()
            disconnectReportRetryJob = null
            pendingDisconnectClaim = null
        }
        if (!started) {
            recoverySyncPending = true
            return@withLock
        }

        deliveryRetryJob?.cancel()
        synchronizationJob?.cancel()
        pendingSyncRequestId = null
        peerSyncRequestIds.clear()
        timeoutJob?.cancel()
        matchClock.stop()
        publishClockState()
        if (state.matchState.status in ACTIVE_SYNC_STATUSES) {
            transition(MatchCommand.Reconnect)
        }
        update {
            copy(
                localStartAcked = reconnectProgress.serverLocalAcked,
                bothStartAcked = reconnectProgress.serverBothAcked,
                message = "再接続中",
                error = null,
            )
        }
        scheduleReconnectReconciliation()
    }

    suspend fun markReconnectSignalingStarted(negotiationEpoch: Int) = actionMutex.withLock {
        if (reconnectProgress.adoptedEpoch == negotiationEpoch) {
            reconnectProgress = reconnectProgress.copy(signalingStarted = true)
        }
    }

    suspend fun reconcileAuthoritativeStartState(authoritativeState: MatchStartAck): Boolean =
        actionMutex.withLock {
            reconcileAuthoritativeStartStateLocked(authoritativeState)
        }

    suspend fun onDataChannelOpen(
        negotiationEpoch: Int = reconnectProgress.authoritativeEpoch,
    ): Boolean {
        if (closed) return false
        if (started) {
            return when {
                state.matchState.status == MatchStatus.RECONNECTING &&
                    reconnectProgress.adoptedEpoch == negotiationEpoch ->
                    handleTransportRecovered(negotiationEpoch)
                state.matchState.status in ACTIVE_SYNC_STATUSES -> true
                else -> false
            }
        }
        return handleInitialDataChannelOpen(negotiationEpoch)
    }

    private suspend fun handleInitialDataChannelOpen(
        negotiationEpoch: Int,
    ): Boolean = startMutex.withLock {
        if (closed) return@withLock false
        if (started) return@withLock true
        transition(MatchCommand.DataChannelOpened)

        var startState: MatchStartAck? = null
        var failure: Exception? = null
        if (!localAckRecorded) {
            for (attempt in 0 until ackAttempts.coerceAtLeast(1)) {
                try {
                    startState = repository.ackMatchStarted(matchId, negotiationEpoch)
                    startState?.let(::recordAuthoritativeStartState)
                    localAckRecorded = startState?.localAcked == true &&
                        startState?.negotiationEpoch == negotiationEpoch
                    if (localAckRecorded) break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure = error
                }
                if (attempt + 1 < ackAttempts) delay(startConfirmationDelayMillis)
            }
            if (!localAckRecorded) {
                startState = try {
                    repository.getMatchStartState(matchId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure = error
                    null
                }
                startState?.let(::recordAuthoritativeStartState)
                localAckRecorded = startState?.localAcked == true &&
                    startState?.negotiationEpoch == negotiationEpoch
            }
            if (!localAckRecorded) {
                update {
                    copy(
                        matchState = MatchState(MatchStatus.P2P_CONNECTED, failure?.message),
                        error = failure?.message ?: "開始ACKを確認できませんでした",
                        message = "開始確認を再試行できます",
                    )
                }
                return@withLock false
            }
            update { copy(localStartAcked = true, error = null, message = "相手の開始確認待ち") }
        }

        if (startState?.serverStatus !in setOf("ACTIVE", "RECONNECTING") && startState?.bothAcked != true) {
            for (attempt in 0 until startConfirmationAttempts.coerceAtLeast(1)) {
                if (attempt > 0) delay(startConfirmationDelayMillis)
                if (closed) return@withLock false
                startState = try {
                    repository.getMatchStartState(matchId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure = error
                    null
                }
                startState?.let(::recordAuthoritativeStartState)
                if (startState?.serverStatus == "ACTIVE" || startState?.bothAcked == true) break
            }
        }

        val confirmed = startState
        val ready = confirmed?.negotiationEpoch == negotiationEpoch &&
            (confirmed.serverStatus == "ACTIVE" ||
                (confirmed.serverStatus == "CREATED" && confirmed.bothAcked))
        if (!ready) {
            update {
                copy(
                    matchState = MatchState(MatchStatus.P2P_CONNECTED, failure?.message),
                    error = failure?.message ?: "相手の開始ACKが未確認です",
                    message = "相手の開始確認待ち",
                )
            }
            return@withLock false
        }
        started = true
        transition(MatchCommand.StartConfirmed)
        initialClock?.let { matchClock.adoptAndStart(it, state.game.currentPlayer) }
            ?: matchClock.start(state.game.currentPlayer)
        update {
            copy(
                message = "対局中",
                error = null,
                localStartAcked = true,
                bothStartAcked = confirmed?.bothAcked == true,
            )
        }
        publishClockState()
        drainPendingRemoteMessages()
        if (activeSubmission != null) {
            update { copy(matchState = MatchState(MatchStatus.FINISHING), message = "結果を送信中") }
            callbackScope.launch { retryFinish() }
        } else if (recoverySyncPending) {
            recoverySyncPending = false
            actionMutex.withLock { requestSynchronization() }
        } else {
            scheduleLocalTimeout()
        }
        true
    }

    suspend fun play(position: Position): Boolean = actionMutex.withLock {
        if (!started || state.matchState.status != MatchStatus.PLAYING || pendingMove != null) return false
        if (state.game.currentPlayer != localDisc) return false
        val clockSnapshot = matchClock.snapshot()
        if (clockSnapshot.remaining(localDisc) == 0L) {
            finishLocallyLocked(FinishReason.TIMEOUT)
            return false
        }
        val command = MoveCommand(
            matchId,
            state.game.ply,
            position,
            UUID.randomUUID().toString(),
            state.game.stateHash(),
            clockSnapshot = clockSnapshot,
        )
        return when (val outcome = state.game.play(position)) {
            is MoveOutcome.Played -> {
                val resolution = TurnResolver.resolveForcedPasses(outcome.state)
                val expected = PendingMove(command, resolution.state.ply, resolution.state.stateHash())
                pendingMove = expected
                // Commit the deterministic local move (and synchronously notify the
                // app-private recovery checkpoint) before the network send. A process
                // cannot leave the peer with a move that this side forgot on restart.
                applyNext(resolution, position, clockSnapshot, finishWhenTerminal = false)
                transition(MatchCommand.MoveSent)
                update {
                    copy(
                        commandCountSent = commandCountSent + 1,
                        awaitingMoveAck = true,
                        message = "着手確認待ち",
                    )
                }
                if (!checkpointBeforeExternalEffect()) return@withLock true
                scheduleDeliveryRetry()
                try {
                    transport.send(command)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    update {
                        copy(
                            error = error.message ?: "着手を送信できませんでした",
                            message = "着手を再送しています",
                        )
                    }
                }
                true
            }
            else -> false
        }
    }

    suspend fun resign(): MatchFinishResult? = actionMutex.withLock { finishLocallyLocked(FinishReason.RESIGNATION) }

    suspend fun finishNormally(): MatchFinishResult? = actionMutex.withLock { finish(FinishReason.NORMAL) }

    suspend fun retryFinish(): MatchFinishResult? = actionMutex.withLock {
        val submission = activeSubmission ?: return null
        return finish(submission.finishReason, submission.result, submission.loserDisc)
    }

    suspend fun retryPendingMove(): Boolean = actionMutex.withLock {
        val command = pendingMove?.command ?: return@withLock false
        if (state.matchState.status != MatchStatus.MOVE_CONFIRMING || !checkpointBeforeExternalEffect()) {
            return@withLock false
        }
        scheduleDeliveryRetry()
        try {
            transport.send(command)
            update { copy(error = null, message = "着手確認待ち") }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update { copy(error = error.message ?: "着手を送信できませんでした", message = "着手を再送しています") }
            false
        }
    }

    suspend fun retrySynchronization(): Boolean = actionMutex.withLock {
        if (state.matchState.status !in setOf(MatchStatus.PLAYING, MatchStatus.SYNCHRONIZING) ||
            !checkpointBeforeExternalEffect()
        ) {
            return@withLock false
        }
        requestSynchronization()
        true
    }

    suspend fun onTimeout(): MatchFinishResult? = actionMutex.withLock { finishLocallyLocked(FinishReason.TIMEOUT) }

    suspend fun finishForDisconnect(): MatchFinishResult? = actionMutex.withLock { finishLocallyLocked(FinishReason.DISCONNECT) }

    suspend fun leaveActiveMatch(): MatchFinishResult? = actionMutex.withLock {
        if (!started || state.matchState.status.isTerminal()) return@withLock lastFinishResult
        finish(FinishReason.DISCONNECT, winnerForLoser(localDisc), localDisc)
    }

    suspend fun reconcileServerState(): MatchFinishResult = actionMutex.withLock {
        val result = repository.reconcileMatch(matchId)
        applyReconciledServerResult(result)
        result
    }

    private suspend fun handleTransportTermination() = actionMutex.withLock {
        if (closed || state.matchState.status in setOf(
                MatchStatus.CONFIRMED,
                MatchStatus.FORFEIT,
                MatchStatus.EXPIRED,
                MatchStatus.ABANDONED,
                MatchStatus.DISPUTED,
                MatchStatus.PENDING_RESULT,
                MatchStatus.FINISHING,
                MatchStatus.RECONNECTING,
            )
        ) return@withLock
        if (started && state.matchState.status in setOf(
                MatchStatus.PLAYING,
                MatchStatus.MOVE_CONFIRMING,
                MatchStatus.SYNCHRONIZING,
            )
        ) {
            beginReconnectGrace()
            return@withLock
        }
        val abandonError = try {
            repository.abandonMatch(matchId)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error.message ?: "対局予約を解放できませんでした"
        }
        timeoutJob?.cancel()
        timeoutJob = null
        matchClock.stop()
        publishClockState()
        update {
            copy(
                matchState = MatchState(MatchStatus.DISCONNECTED, abandonError),
                message = "接続が切断されました",
                error = abandonError,
            )
        }
    }

    private fun scheduleDisconnectDebounce() {
        if (disconnectDebounceJob?.isActive == true || closed) return
        disconnectDebounceJob = callbackScope.launch {
            if (disconnectDebounceMillis > 0) delay(disconnectDebounceMillis)
            disconnectDebounceJob = null
            if (!closed && lastTransportState == TransportState.DISCONNECTED) {
                handleTransportTermination()
            }
        }
    }

    private suspend fun handleTransportRecovered(expectedEpoch: Int): Boolean = actionMutex.withLock {
        if (closed || !started || state.matchState.status != MatchStatus.RECONNECTING ||
            reconnectProgress.adoptedEpoch != expectedEpoch
        ) {
            return@withLock false
        }
        reconnectProgress = reconnectProgress.copy(ackRequestSent = true)
        var acknowledged = try {
            repository.ackMatchStarted(matchId, expectedEpoch)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The request may have committed even when its HTTP response was lost.
            // Resolve the ambiguity from the authoritative match row before allowing
            // the Coordinator to consider spending another reconnect epoch.
            try {
                repository.getMatchStartState(matchId)
            } catch (stateError: CancellationException) {
                throw stateError
            } catch (stateError: Exception) {
                update { copy(error = stateError.message, message = "再接続を確認できませんでした") }
                return@withLock false
            }
        }
        recordAuthoritativeStartState(acknowledged)
        for (attempt in 0 until startConfirmationAttempts.coerceAtLeast(1)) {
            if (acknowledged.negotiationEpoch == expectedEpoch &&
                acknowledged.serverStatus == "ACTIVE" && acknowledged.bothAcked
            ) {
                break
            }
            if (acknowledged.negotiationEpoch != expectedEpoch ||
                acknowledged.serverStatus in TERMINAL_SERVER_STATUSES
            ) {
                break
            }
            if (attempt > 0) delay(startConfirmationDelayMillis)
            acknowledged = try {
                repository.getMatchStartState(matchId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { copy(error = error.message, message = "再接続を確認できませんでした") }
                return@withLock false
            }
            recordAuthoritativeStartState(acknowledged)
        }
        reconcileAuthoritativeStartStateLocked(acknowledged)
    }

    private suspend fun reconcileAuthoritativeStartStateLocked(authoritativeState: MatchStartAck): Boolean {
        recordAuthoritativeStartState(authoritativeState)
        if (authoritativeState.serverStatus in TERMINAL_SERVER_STATUSES) {
            applyServerResult(authoritativeState.toFinishResult())
            return true
        }
        if (authoritativeState.serverStatus == "ACTIVE" && authoritativeState.localAcked &&
            authoritativeState.bothAcked &&
            authoritativeState.negotiationEpoch == reconnectProgress.completedEpoch &&
            reconnectProgress.adoptedEpoch == null && !reconnectProgress.freshEpochRequired &&
            state.matchState.status == MatchStatus.PLAYING && lastTransportState == TransportState.OPEN
        ) {
            // A delayed forced retry can arrive after this epoch already completed
            // synchronization. Treat the authoritative row as an idempotent no-op;
            // reopening the epoch would make the retry look like a fresh reconnect.
            update {
                copy(localStartAcked = true, bothStartAcked = true, error = null, message = "対局中")
            }
            return true
        }
        if (authoritativeState.negotiationEpoch != reconnectProgress.adoptedEpoch) {
            update { copy(error = "新しい再接続epochを確認しました", message = "再接続状態を更新しています") }
            return false
        }
        if (authoritativeState.serverStatus == "ACTIVE" && authoritativeState.localAcked &&
            authoritativeState.bothAcked && lastTransportState == TransportState.OPEN
        ) {
            beginRecoveredSynchronization()
            return true
        }
        update {
            copy(
                localStartAcked = authoritativeState.localAcked,
                bothStartAcked = authoritativeState.bothAcked,
                error = "相手の再接続ACKが未確認です",
                message = "相手の復帰を待っています",
            )
        }
        return false
    }

    private fun recordAuthoritativeStartState(authoritativeState: MatchStartAck) {
        if (authoritativeState.negotiationEpoch < reconnectProgress.authoritativeEpoch) return
        val appliesToAdoptedEpoch = authoritativeState.negotiationEpoch == reconnectProgress.adoptedEpoch
        reconnectProgress = reconnectProgress.copy(
            authoritativeEpoch = authoritativeState.negotiationEpoch,
            serverLocalAcked = if (appliesToAdoptedEpoch) authoritativeState.localAcked else false,
            serverBothAcked = if (appliesToAdoptedEpoch) authoritativeState.bothAcked else false,
        )
        if (appliesToAdoptedEpoch) {
            update {
                copy(
                    localStartAcked = authoritativeState.localAcked,
                    bothStartAcked = authoritativeState.bothAcked,
                )
            }
        }
    }

    private suspend fun beginRecoveredSynchronization() {
        reconnectJob?.cancel()
        disconnectReportRetryJob?.cancel()
        disconnectReportRetryJob = null
        pendingDisconnectClaim = null
        if (state.matchState.status == MatchStatus.RECONNECTING) transition(MatchCommand.Reconnected)
        else update { copy(matchState = MatchState(MatchStatus.SYNCHRONIZING)) }
        update { copy(error = null, message = "対局を同期しています") }
        requestSynchronization()
    }

    private suspend fun beginReconnectGrace() {
        if (closed || !started || state.matchState.status !in ACTIVE_SYNC_STATUSES) return
        deliveryRetryJob?.cancel()
        synchronizationJob?.cancel()
        pendingSyncRequestId = null
        peerSyncRequestIds.clear()
        timeoutJob?.cancel()
        matchClock.stop()
        publishClockState()
        reconnectProgress = ReconnectEpochProgress(
            authoritativeEpoch = reconnectProgress.authoritativeEpoch,
            completedEpoch = reconnectProgress.completedEpoch,
            localDisconnectObserved = true,
            freshEpochRequired = true,
        )
        transition(MatchCommand.Reconnect)
        update { copy(message = "再接続中", error = null) }
        val claim = pendingDisconnectClaim ?: MatchSubmission(
            matchId = matchId,
            canonicalMoves = CanonicalMoves.encode(state.moves),
            result = winnerForLoser(localDisc.opponent()),
            finalPositionHash = state.game.stateHash(),
            finishReason = FinishReason.DISCONNECT,
            loserDisc = localDisc.opponent(),
            clockPayload = clockPayload(),
        ).also { pendingDisconnectClaim = it }
        val reported = try {
            repository.submitMatchResult(claim)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update {
                copy(
                    matchState = MatchState(MatchStatus.RECONNECTING, error.message),
                    error = error.message ?: "切断状態を保存できませんでした",
                    message = "再接続中",
                )
            }
            scheduleDisconnectReportRetry()
            scheduleReconnectReconciliation()
            return
        }
        pendingDisconnectClaim = null
        applyDisconnectReport(reported)
    }

    private fun applyDisconnectReport(reported: MatchFinishResult) {
        if (reported.serverStatus == "RECONNECTING" || reported.serverStatus == "RESULT_PENDING") {
            update {
                copy(
                    matchState = MatchState(MatchStatus.RECONNECTING),
                    message = "相手の復帰を待っています",
                    error = null,
                    finishResult = reported,
                )
            }
            scheduleReconnectReconciliation()
        } else {
            applyServerResult(reported)
        }
    }

    private fun scheduleDisconnectReportRetry() {
        if (disconnectReportRetryJob?.isActive == true || closed) return
        disconnectReportRetryJob = callbackScope.launch {
            repeat(2) {
                delay(disconnectReportRetryMillis)
                val reported = actionMutex.withLock {
                    if (closed || state.matchState.status != MatchStatus.RECONNECTING) return@launch
                    val claim = pendingDisconnectClaim ?: return@launch
                    try {
                        repository.submitMatchResult(claim)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        update { copy(error = error.message, message = "切断状態の保存を再試行しています") }
                        null
                    }
                }
                if (reported != null) {
                    actionMutex.withLock {
                        disconnectReportRetryJob = null
                        pendingDisconnectClaim = null
                        applyDisconnectReport(reported)
                    }
                    return@launch
                }
            }
            disconnectReportRetryJob = null
        }
    }

    private fun scheduleReconnectReconciliation() {
        reconnectJob?.cancel()
        reconnectJob = callbackScope.launch {
            delay(reconnectGraceMillis)
            actionMutex.withLock {
                reconnectJob = null
                if (closed || state.matchState.status != MatchStatus.RECONNECTING) return@withLock
                val result = try {
                    repository.reconcileMatch(matchId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    update { copy(error = error.message, message = "対局状態を再確認できます") }
                    return@withLock
                }
                applyReconciledServerResult(result)
            }
        }
    }

    private suspend fun applyReconciledServerResult(result: MatchFinishResult) {
        if (result.serverStatus == "ACTIVE" && state.matchState.status == MatchStatus.RECONNECTING &&
            started && lastTransportState == TransportState.OPEN
        ) {
            beginRecoveredSynchronization()
        } else {
            applyServerResult(result)
        }
    }

    fun reportConnectionError(message: String) {
        if (closed || state.matchState.status !in setOf(
                MatchStatus.P2P_CONNECTED,
                MatchStatus.RECONNECTING,
                MatchStatus.PENDING_RESULT,
            )
        ) return
        val recoveryMessage = when (state.matchState.status) {
            MatchStatus.PENDING_RESULT -> "対局状態を再確認できます"
            MatchStatus.RECONNECTING -> "再接続を再試行できます"
            else -> "P2P接続を再試行できます"
        }
        update { copy(error = message, message = recoveryMessage) }
    }

    /** UI ticker hook. Official timeout scheduling remains inside this controller. */
    fun refreshClock() {
        if (state.matchState.status == MatchStatus.PLAYING) publishClockState()
    }

    private suspend fun onMoveAck(ack: MoveAck) = actionMutex.withLock {
        if (closed || ack.matchId != matchId) return@withLock
        val pending = pendingMove ?: return@withLock
        if (state.matchState.status !in setOf(
                MatchStatus.PLAYING,
                MatchStatus.MOVE_CONFIRMING,
                MatchStatus.SYNCHRONIZING,
                MatchStatus.RECONNECTING,
            )
        ) return@withLock
        if (state.matchState.status == MatchStatus.RECONNECTING) {
            // A late exact ACK is useful local evidence, but no DataChannel message may
            // bypass the server resume/epoch/start-ACK handshake. Divergence is reconciled
            // only after the Coordinator has restored the current negotiation epoch.
            if (ack.commandId == pending.command.commandId &&
                ack.acknowledgedPly == pending.expectedPly && ack.stateHash == pending.expectedStateHash
            ) {
                pendingMove = null
                deliveryRetryJob?.cancel()
                update { copy(awaitingMoveAck = false) }
            }
            return@withLock
        }
        if (ack.commandId != pending.command.commandId ||
            ack.acknowledgedPly != pending.expectedPly || ack.stateHash != pending.expectedStateHash
        ) {
            requestSynchronization()
            return@withLock
        }
        pendingMove = null
        deliveryRetryJob?.cancel()
        if (state.matchState.status == MatchStatus.MOVE_CONFIRMING) transition(MatchCommand.MoveAcknowledged)
        update { copy(awaitingMoveAck = false, error = null, message = "対局中") }
        finishAfterDeliveryIfTerminal()
    }

    private fun scheduleDeliveryRetry() {
        deliveryRetryJob?.cancel()
        deliveryRetryJob = callbackScope.launch {
            repeat((deliveryRetryAttempts - 1).coerceAtLeast(0)) {
                delay(deliveryAckTimeoutMillis)
                val command = actionMutex.withLock { pendingMove?.command } ?: return@launch
                try {
                    transport.send(command)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The connection supervisor owns the bounded recovery decision.
                }
            }
            delay(deliveryAckTimeoutMillis)
            actionMutex.withLock {
                deliveryRetryJob = null
                if (!closed && pendingMove != null) requestSynchronization()
            }
        }
    }

    private suspend fun requestSynchronization() {
        if (closed || !started) return
        if (state.matchState.status == MatchStatus.PLAYING || state.matchState.status == MatchStatus.MOVE_CONFIRMING) {
            transition(MatchCommand.Synchronize)
        } else if (state.matchState.status != MatchStatus.SYNCHRONIZING) {
            update { copy(matchState = MatchState(MatchStatus.SYNCHRONIZING)) }
        }
        update { copy(awaitingMoveAck = pendingMove != null, message = "対局を同期しています") }
        val request = SyncMessage(
            matchId = matchId,
            requestId = UUID.randomUUID().toString(),
            type = SyncMessageType.REQUEST,
            ply = state.game.ply,
            stateHash = state.game.stateHash(),
        )
        pendingSyncRequestId = request.requestId
        try {
            transport.sendSync(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            beginReconnectGrace()
            return
        }
        synchronizationJob?.cancel()
        synchronizationJob = callbackScope.launch {
            delay(synchronizationTimeoutMillis)
            actionMutex.withLock {
                synchronizationJob = null
                if (!closed && state.matchState.status == MatchStatus.SYNCHRONIZING) beginReconnectGrace()
            }
        }
    }

    private suspend fun onSyncMessage(message: SyncMessage) = actionMutex.withLock {
        if (closed || !started || message.matchId != matchId || state.matchState.status !in ACTIVE_SYNC_STATUSES) {
            return@withLock
        }
        when (message.type) {
            SyncMessageType.REQUEST -> {
                if (peerSyncRequestIds.size >= MAX_SYNC_REQUESTS) {
                    peerSyncRequestIds.remove(peerSyncRequestIds.first())
                }
                peerSyncRequestIds += message.requestId
                sendSyncSnapshot(message.requestId)
            }
            SyncMessageType.SNAPSHOT -> {
                val answersOurRequest = message.requestId == pendingSyncRequestId
                val answersPeerRequest = peerSyncRequestIds.remove(message.requestId)
                if (answersOurRequest || answersPeerRequest) applySyncSnapshot(message)
            }
        }
    }

    private suspend fun sendSyncSnapshot(requestId: String) {
        if (!checkpointBeforeExternalEffect()) return
        try {
            transport.sendSync(
                SyncMessage(
                    matchId = matchId,
                    requestId = requestId,
                    type = SyncMessageType.SNAPSHOT,
                    ply = state.game.ply,
                    stateHash = state.game.stateHash(),
                    transcript = CanonicalMoves.encode(state.moves),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (state.matchState.status in ACTIVE_SYNC_STATUSES) beginReconnectGrace()
        }
    }

    private suspend fun applySyncSnapshot(message: SyncMessage) {
        val remoteEncoded = message.transcript ?: return
        val remoteMoves = try {
            CanonicalMoves.decode(remoteEncoded)
        } catch (_: IllegalArgumentException) {
            beginReconnectGrace()
            return
        }
        val remoteGame = try {
            replay(remoteMoves)
        } catch (_: IllegalArgumentException) {
            beginReconnectGrace()
            return
        }
        if (remoteGame.ply != message.ply || remoteGame.stateHash() != message.stateHash) {
            beginReconnectGrace()
            return
        }
        val localEncoded = CanonicalMoves.encode(state.moves)
        var adoptedRemoteTranscript = false
        when {
            remoteEncoded == localEncoded -> Unit
            remoteEncoded.startsWith(localEncoded) -> {
                if (!isSinglePeerTurnExtension(remoteMoves, remoteGame)) {
                    beginReconnectGrace()
                    return
                }
                update { copy(game = remoteGame, moves = remoteMoves) }
                adoptedRemoteTranscript = true
            }
            localEncoded.startsWith(remoteEncoded) -> {
                sendSyncSnapshot(message.requestId)
                return
            }
            else -> {
                beginReconnectGrace()
                return
            }
        }
        if (adoptedRemoteTranscript && !checkpointBeforeExternalEffect()) return
        pendingMove = null
        if (message.requestId == pendingSyncRequestId) pendingSyncRequestId = null
        deliveryRetryJob?.cancel()
        synchronizationJob?.cancel()
        if (state.matchState.status == MatchStatus.SYNCHRONIZING) transition(MatchCommand.Synchronized)
        reconnectProgress = ReconnectEpochProgress(
            authoritativeEpoch = reconnectProgress.authoritativeEpoch,
            completedEpoch = reconnectProgress.authoritativeEpoch,
            serverLocalAcked = true,
            serverBothAcked = true,
        )
        if (state.game.status is GameStatus.InProgress) {
            matchClock.adoptAndStart(matchClock.snapshot(), state.game.currentPlayer)
        } else {
            matchClock.stop()
        }
        update { copy(awaitingMoveAck = false, error = null, message = "対局中") }
        scheduleLocalTimeout()
        if (adoptedRemoteTranscript) {
            // Echo the converged state once. The peer that sent the longer transcript
            // uses this as the sync-level acknowledgement for a lost move command.
            sendSyncSnapshot(message.requestId)
        }
        finishAfterDeliveryIfTerminal()
    }

    /**
     * A healthy turn-based session can diverge by at most the peer's one real move plus
     * deterministic forced passes. Accepting an arbitrary longer legal transcript would let
     * one client manufacture both players' moves and make the honest peer submit it.
     */
    private fun isSinglePeerTurnExtension(remoteMoves: List<Position?>, remoteGame: GameState): Boolean {
        if (pendingMove != null || state.game.currentPlayer != localDisc.opponent()) return false
        val tail = remoteMoves.drop(state.moves.size)
        val move = tail.firstOrNull() ?: return false
        val played = state.game.play(move) as? MoveOutcome.Played ?: return false
        val resolution = TurnResolver.resolveForcedPasses(played.state)
        val expectedTail = listOf(move) + List(resolution.forcedPasses) { null }
        return tail == expectedTail && resolution.state == remoteGame
    }

    private suspend fun onRemoteCommand(command: MoveCommand) = actionMutex.withLock {
        if (closed) return@withLock
        if (state.matchState.status == MatchStatus.P2P_CONNECTED) {
            if (pendingRemoteCommands.size < MAX_PENDING_REMOTE_MESSAGES) pendingRemoteCommands.addLast(command)
            return@withLock
        }
        if (state.matchState.status != MatchStatus.PLAYING) return@withLock
        applyRemoteCommand(command)
    }

    private suspend fun applyRemoteCommand(command: MoveCommand) {
        when (val validation = validator.validate(state.game, command)) {
            is CommandValidation.Accepted -> {
                applyNext(
                    TurnResolver.resolveForcedPasses(validation.state),
                    command.move,
                    command.clockSnapshot?.let(::mergeRemoteClock) ?: matchClock.snapshot(),
                    finishWhenTerminal = true,
                )
                val ack = MoveAck(matchId, command.commandId, state.game.ply, state.game.stateHash())
                receivedMoveAcks[command.commandId] = ack
                if (!checkpointBeforeExternalEffect()) return
                try {
                    transport.sendMoveAck(ack)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    beginReconnectGrace()
                }
                update { copy(commandCountReceived = commandCountReceived + 1) }
            }
            is CommandValidation.Duplicate -> receivedMoveAcks[command.commandId]?.let { ack ->
                if (!checkpointBeforeExternalEffect()) return
                try {
                    transport.sendMoveAck(ack)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    beginReconnectGrace()
                }
            }
            is CommandValidation.Rejected -> {
                update {
                    copy(error = validation.violation.name, message = "対局を同期しています")
                }
                requestSynchronization()
            }
        }
    }

    private suspend fun onRemoteFinish(command: FinishCommand) = actionMutex.withLock {
        if (closed) return@withLock
        if (state.matchState.status == MatchStatus.P2P_CONNECTED) {
            if (pendingRemoteFinishes.size < MAX_PENDING_REMOTE_MESSAGES) pendingRemoteFinishes.addLast(command)
            return@withLock
        }
        if (state.matchState.status != MatchStatus.PLAYING) return@withLock
        applyRemoteFinish(command)
    }

    private suspend fun applyRemoteFinish(command: FinishCommand) {
        when (val validation = finishValidator.validate(state.game, command)) {
            is FinishCommandValidation.Accepted -> {
                command.clockSnapshot?.let { matchClock.adoptAndStart(mergeRemoteClock(it), state.game.currentPlayer) }
                finish(
                    validation.command.reason.toFinishReason(),
                    winnerForLoser(validation.command.loserDisc),
                    validation.command.loserDisc,
                )
            }
            is FinishCommandValidation.Duplicate -> Unit
            is FinishCommandValidation.Rejected -> update {
                copy(error = validation.violation.name, message = "対局プロトコルエラー: ${validation.violation.name}")
            }
        }
    }

    private suspend fun drainPendingRemoteMessages() {
        while (state.matchState.status == MatchStatus.PLAYING && pendingRemoteCommands.isNotEmpty()) {
            onRemoteCommand(pendingRemoteCommands.removeFirst())
        }
        while (state.matchState.status == MatchStatus.PLAYING && pendingRemoteFinishes.isNotEmpty()) {
            onRemoteFinish(pendingRemoteFinishes.removeFirst())
        }
    }

    private fun applyNext(
        resolution: com.example.othello.game.TurnResolution,
        move: Position,
        clockSnapshot: ClockSnapshot,
        finishWhenTerminal: Boolean,
    ) {
        matchClock.adoptAndStart(clockSnapshot, resolution.state.currentPlayer)
        val currentClock = matchClock.snapshot()
        update {
            copy(
                game = resolution.state,
                moves = moves + move + List(resolution.forcedPasses) { null },
                blackRemainingMillis = currentClock.blackRemainingMillis,
                whiteRemainingMillis = currentClock.whiteRemainingMillis,
            )
        }
        scheduleLocalTimeout()
        if (finishWhenTerminal) finishAfterDeliveryIfTerminal()
    }

    private fun finishAfterDeliveryIfTerminal() {
        if (pendingMove != null || state.game.status !is GameStatus.Finished ||
            state.matchState.status !in setOf(MatchStatus.PLAYING, MatchStatus.SYNCHRONIZING)
        ) return
        if (activeSubmission == null) {
            activeSubmission = MatchSubmission(
                matchId = matchId,
                canonicalMoves = CanonicalMoves.encode(state.moves),
                result = resultForGame(state.game),
                finalPositionHash = state.game.stateHash(),
                finishReason = FinishReason.NORMAL,
                loserDisc = null,
                clockPayload = clockPayload(),
            )
        }
        update {
            copy(
                matchState = MatchState(MatchStatus.FINISHING),
                message = "結果を送信中",
                pendingFinishReason = FinishReason.NORMAL,
                pendingLoserDisc = null,
                pendingResultRequestId = activeSubmission?.requestId,
            )
        }
        if (!checkpointBeforeExternalEffect()) return
        callbackScope.launch { finishNormally() }
    }

    private suspend fun finishLocallyLocked(reason: FinishReason): MatchFinishResult? {
        if (!started || state.matchState.status != MatchStatus.PLAYING) return lastFinishResult
        val signalReason = reason.toSignalReason() ?: return null
        val clockSnapshot = matchClock.snapshot()
        val command = FinishCommand(
            matchId = matchId,
            ply = state.game.ply,
            commandId = UUID.randomUUID().toString(),
            stateHash = state.game.stateHash(),
            loserDisc = localDisc,
            reason = signalReason,
            clockSnapshot = clockSnapshot,
        )
        val localResult = winnerForLoser(localDisc)
        if (activeSubmission == null) {
            activeSubmission = MatchSubmission(
                matchId = matchId,
                canonicalMoves = CanonicalMoves.encode(state.moves),
                result = localResult,
                finalPositionHash = state.game.stateHash(),
                finishReason = reason,
                loserDisc = localDisc,
                clockPayload = clockPayload(),
            )
        }
        // Publish the outbox state to the app-private recovery store before a peer send or
        // server call can be followed by abrupt process death.
        update {
            copy(
                matchState = MatchState(MatchStatus.FINISHING),
                message = "結果を送信中",
                pendingFinishReason = reason,
                pendingLoserDisc = localDisc,
                pendingResultRequestId = activeSubmission?.requestId,
            )
        }
        if (!checkpointBeforeExternalEffect()) return null
        var peerNoticeError: String? = null
        try {
            transport.sendFinish(command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The server submission is authoritative. A failed best-effort peer notice must
            // never discard a resignation/timeout outbox just before process death.
            peerNoticeError = error.message ?: "終局通知を送信できませんでした"
        }
        val result = finish(reason, localResult, localDisc)
        if (result == null && peerNoticeError != null) update { copy(error = peerNoticeError) }
        return result
    }

    private suspend fun finish(
        reason: FinishReason,
        explicitResult: MatchResult? = null,
        loserDisc: Disc? = null,
    ): MatchFinishResult? = finishMutex.withLock {
        if (state.matchState.status in setOf(
                MatchStatus.CONFIRMED,
                MatchStatus.FORFEIT,
                MatchStatus.EXPIRED,
                MatchStatus.ABANDONED,
                MatchStatus.DISPUTED,
            )
        ) return@withLock lastFinishResult
        val result = explicitResult ?: resultForGame(state.game)
        val submission = activeSubmission ?: MatchSubmission(
            matchId = matchId,
            canonicalMoves = CanonicalMoves.encode(state.moves),
            result = result,
            finalPositionHash = state.game.stateHash(),
            finishReason = reason,
            loserDisc = loserDisc,
            clockPayload = clockPayload(),
        ).also { activeSubmission = it }
        if (submission.finishReason != reason || submission.result != result || submission.loserDisc != loserDisc) {
            return@withLock lastFinishResult
        }
        timeoutJob?.cancel()
        matchClock.stop()
        publishClockState()
        update {
            copy(
                matchState = MatchState(MatchStatus.FINISHING),
                message = "結果を送信中",
                pendingFinishReason = reason,
                pendingLoserDisc = loserDisc,
                pendingResultRequestId = submission.requestId,
            )
        }
        if (!checkpointBeforeExternalEffect()) return@withLock null
        val submitted = try {
            repository.submitMatchResult(submission)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update {
                copy(
                    matchState = MatchState(MatchStatus.FINISHING, error.message),
                    error = error.message ?: "結果を送信できませんでした",
                    message = "結果送信を再試行できます",
                )
            }
            return@withLock null
        }
        lastFinishResult = submitted
        applyServerResult(submitted)
        submitted
    }

    private fun applyServerResult(submitted: MatchFinishResult) {
        val next = when (submitted.serverStatus) {
            "ACTIVE" -> if (started && state.matchState.status == MatchStatus.PLAYING &&
                lastTransportState == TransportState.OPEN
            ) {
                MatchStatus.PLAYING
            } else {
                MatchStatus.RECONNECTING
            }
            "RECONNECTING" -> MatchStatus.RECONNECTING
            "RESULT_PENDING", "PENDING_RESULT" -> MatchStatus.PENDING_RESULT
            "CONFIRMED" -> MatchStatus.CONFIRMED
            "FORFEIT" -> MatchStatus.FORFEIT
            "EXPIRED" -> MatchStatus.EXPIRED
            "ABANDONED" -> MatchStatus.ABANDONED
            "DISPUTED" -> MatchStatus.DISPUTED
            else -> MatchStatus.FAILED
        }
        update {
            copy(
                matchState = MatchState(next),
                message = submitted.serverStatus,
                error = null,
                finishResult = submitted,
                pendingFinishReason = if (next.isTerminal()) null else pendingFinishReason,
                pendingLoserDisc = if (next.isTerminal()) null else pendingLoserDisc,
                pendingResultRequestId = if (next.isTerminal()) null else pendingResultRequestId,
            )
        }
        if (next.isTerminal()) closeTransport()
    }

    private fun MatchStatus.isTerminal(): Boolean = this in setOf(
        MatchStatus.CONFIRMED,
        MatchStatus.FORFEIT,
        MatchStatus.EXPIRED,
        MatchStatus.ABANDONED,
        MatchStatus.DISPUTED,
    )

    private fun resultForGame(game: GameState): MatchResult {
        val result = (game.status as? GameStatus.Finished)?.result
        return when {
            result == null -> throw IllegalStateException("NORMAL finish requires a finished game")
            result.winner == Disc.BLACK -> MatchResult.BLACK_WIN
            result.winner == Disc.WHITE -> MatchResult.WHITE_WIN
            else -> MatchResult.DRAW
        }
    }

    private fun winnerForLoser(loser: Disc): MatchResult = when (loser) {
        Disc.BLACK -> MatchResult.WHITE_WIN
        Disc.WHITE -> MatchResult.BLACK_WIN
        Disc.EMPTY -> error("EMPTY cannot lose a match")
    }

    private fun FinishReason.toSignalReason(): FinishSignalReason? = when (this) {
        FinishReason.RESIGNATION -> FinishSignalReason.RESIGNATION
        FinishReason.TIMEOUT -> FinishSignalReason.TIMEOUT
        FinishReason.DISCONNECT -> FinishSignalReason.DISCONNECT
        FinishReason.NORMAL, FinishReason.DISPUTED -> null
    }

    private fun FinishSignalReason.toFinishReason(): FinishReason = when (this) {
        FinishSignalReason.RESIGNATION -> FinishReason.RESIGNATION
        FinishSignalReason.TIMEOUT -> FinishReason.TIMEOUT
        FinishSignalReason.DISCONNECT -> FinishReason.DISCONNECT
    }

    private fun publishClockState() {
        val clock = matchClock.snapshot()
        if (clock.blackRemainingMillis != state.blackRemainingMillis || clock.whiteRemainingMillis != state.whiteRemainingMillis) {
            update { copy(blackRemainingMillis = clock.blackRemainingMillis, whiteRemainingMillis = clock.whiteRemainingMillis) }
        }
    }

    /** The peer is authoritative only for its own decreasing clock. */
    private fun mergeRemoteClock(remote: ClockSnapshot): ClockSnapshot {
        val local = matchClock.snapshot()
        return when (localDisc) {
            Disc.BLACK -> ClockSnapshot(
                blackRemainingMillis = local.blackRemainingMillis,
                whiteRemainingMillis = minOf(local.whiteRemainingMillis, remote.whiteRemainingMillis),
            )
            Disc.WHITE -> ClockSnapshot(
                blackRemainingMillis = minOf(local.blackRemainingMillis, remote.blackRemainingMillis),
                whiteRemainingMillis = local.whiteRemainingMillis,
            )
            Disc.EMPTY -> error("EMPTY cannot own a match clock")
        }
    }

    private fun scheduleLocalTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
        if (!started || state.matchState.status != MatchStatus.PLAYING || state.game.status !is GameStatus.InProgress ||
            state.game.currentPlayer != localDisc
        ) return
        val expectedPly = state.game.ply
        val remaining = matchClock.snapshot().remaining(localDisc)
        timeoutJob = callbackScope.launch {
            delay(remaining.coerceAtLeast(1L))
            actionMutex.withLock {
                timeoutJob = null
                if (!closed && state.matchState.status == MatchStatus.PLAYING && state.game.ply == expectedPly &&
                    state.game.currentPlayer == localDisc && matchClock.snapshot().remaining(localDisc) == 0L
                ) {
                    publishClockState()
                    finishLocallyLocked(FinishReason.TIMEOUT)
                }
            }
        }
    }

    private fun clockPayload(): String {
        val clock = matchClock.snapshot()
        return "{\"blackRemainingMillis\":${clock.blackRemainingMillis},\"whiteRemainingMillis\":${clock.whiteRemainingMillis}}"
    }

    private fun transition(command: MatchCommand) {
        when (val result = MatchStateMachine.reduce(state.matchState, command)) {
            is MatchTransition.Accepted -> update { copy(matchState = result.state) }
            is MatchTransition.Rejected -> update { copy(error = result.reason) }
        }
    }

    /**
     * The caller provides the small app-private durability port. A false return keeps the
     * command/result entirely local: no DataChannel send and no result RPC may cross this
     * checkpoint until the user retries.
     */
    private fun checkpointBeforeExternalEffect(): Boolean {
        val persisted = try {
            durableCheckpoint(state)
        } catch (error: Exception) {
            false
        }
        if (!persisted) {
            update {
                copy(
                    error = "対局状態を端末へ保存できませんでした",
                    message = "端末保存を再試行できます",
                )
            }
        }
        return persisted
    }

    private fun MatchStartAck.toFinishResult() = MatchFinishResult(
        serverStatus = serverStatus,
        deadlineEpochMillis = deadlineEpochMillis,
        negotiationEpoch = negotiationEpoch,
    )

    private fun update(transform: OnlineMatchViewState.() -> OnlineMatchViewState) {
        state = state.transform()
        listeners.toList().forEach { it(state) }
    }

    fun diagnostics(userId: String, opponentId: String?): MatchDiagnostics {
        val transport = transport.diagnostics()
        return MatchDiagnostics(
            matchId = matchId,
            userId = userId,
            localDisc = localDisc.name,
            opponentId = opponentId,
            sessionStatus = state.matchState.status,
            iceState = transport.iceState,
            peerConnectionState = transport.peerConnectionState,
            dataChannelState = transport.dataChannelState,
            packetsSent = state.commandCountSent,
            packetsReceived = state.commandCountReceived,
            ply = state.game.ply,
            stateHash = state.game.stateHash(),
            lastError = state.error,
            localStartAcked = state.localStartAcked,
            bothStartAcked = state.bothStartAcked,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        timeoutJob?.cancel()
        deliveryRetryJob?.cancel()
        synchronizationJob?.cancel()
        reconnectJob?.cancel()
        disconnectDebounceJob?.cancel()
        disconnectReportRetryJob?.cancel()
        timeoutJob = null
        closeTransport()
        listeners.clear()
        if (cancelCallbackScopeOnClose) callbackScope.cancel()
    }

    private companion object {
        const val MAX_PENDING_REMOTE_MESSAGES = 64
        const val MAX_SYNC_REQUESTS = 8
        val ACTIVE_SYNC_STATUSES = setOf(MatchStatus.PLAYING, MatchStatus.MOVE_CONFIRMING, MatchStatus.SYNCHRONIZING)
        val TERMINAL_SERVER_STATUSES = setOf("CONFIRMED", "FORFEIT", "EXPIRED", "ABANDONED", "DISPUTED")

        fun replay(moves: List<Position?>): GameState {
            require(moves.size <= 120) { "online transcript is too large" }
            var game = GameState()
            moves.forEachIndexed { index, move ->
                game = when (val outcome = move?.let(game::play) ?: game.pass()) {
                    is MoveOutcome.Played -> outcome.state
                    is MoveOutcome.Passed -> outcome.state
                    is MoveOutcome.Rejected -> throw IllegalArgumentException("invalid online transcript at ply $index")
                }
            }
            return game
        }
    }

    private data class PendingMove(
        val command: MoveCommand,
        val expectedPly: Int,
        val expectedStateHash: String,
    )

    private fun closeTransport() {
        if (transportClosed) return
        transportClosed = true
        transportSubscription.close()
        finishSubscription.close()
        moveAckSubscription.close()
        syncSubscription.close()
        stateSubscription.close()
        transport.close()
    }
}
