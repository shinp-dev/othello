package com.example.othello.match

import com.example.othello.game.CanonicalMoves
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.TurnResolver
import com.example.othello.network.MatchTransport
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
)

/** Coordinates local game rules, DataChannel validation and result submission. */
class OnlineMatchController(
    private val matchId: String,
    private val localDisc: Disc,
    private val transport: MatchTransport,
    private val repository: OnlineMatchRepository,
    private val ackAttempts: Int = 3,
    private val startConfirmationAttempts: Int = 20,
    private val startConfirmationDelayMillis: Long = 250,
    private val timeControlMillis: Long = DEFAULT_TIME_CONTROL_MILLIS,
    monotonicNowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val callbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val cancelCallbackScopeOnClose: Boolean = true,
) : AutoCloseable {
    private var state = OnlineMatchViewState(
        matchState = MatchState(MatchStatus.P2P_CONNECTED),
        localDisc = localDisc,
        blackRemainingMillis = timeControlMillis,
        whiteRemainingMillis = timeControlMillis,
    )
    private val listeners = mutableSetOf<(OnlineMatchViewState) -> Unit>()
    private val validator = MoveCommandValidator(matchId, localDisc.opponent())
    private val finishValidator = FinishCommandValidator(matchId, localDisc.opponent())
    private val transportSubscription: AutoCloseable
    private val finishSubscription: AutoCloseable
    private val stateSubscription: AutoCloseable
    private val startMutex = Mutex()
    private val finishMutex = Mutex()
    private val actionMutex = Mutex()
    private val matchClock = MatchClock(timeControlMillis, monotonicNowMillis)
    private var timeoutJob: Job? = null
    private var localAckRecorded = false
    private var activeSubmission: MatchSubmission? = null
    private var lastFinishResult: MatchFinishResult? = null
    private var started = false
    private var closed = false
    private var transportClosed = false
    private val pendingRemoteCommands = ArrayDeque<MoveCommand>()
    private val pendingRemoteFinishes = ArrayDeque<FinishCommand>()

    init {
        transportSubscription = transport.observe { command ->
            callbackScope.launch { onRemoteCommand(command) }
        }
        finishSubscription = transport.observeFinish { command ->
            callbackScope.launch { onRemoteFinish(command) }
        }
        stateSubscription = transport.observeState { next ->
            callbackScope.launch {
                if (next == TransportState.CLOSED || next == TransportState.FAILED) handleTransportTermination()
            }
        }
    }

    val viewState: OnlineMatchViewState get() = state

    fun observe(listener: (OnlineMatchViewState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    suspend fun onDataChannelOpen(): Boolean = startMutex.withLock {
        if (closed) return@withLock false
        if (started) return@withLock true
        transition(MatchCommand.DataChannelOpened)

        var startState: MatchStartAck? = null
        var failure: Exception? = null
        if (!localAckRecorded) {
            for (attempt in 0 until ackAttempts.coerceAtLeast(1)) {
                try {
                    startState = repository.ackMatchStarted(matchId)
                    localAckRecorded = startState?.localAcked == true
                    if (localAckRecorded) break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure = error
                }
                if (attempt + 1 < ackAttempts) delay(startConfirmationDelayMillis)
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

        if (startState?.bothAcked != true) {
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
                if (startState?.bothAcked == true) break
            }
        }

        val confirmed = startState
        if (confirmed?.bothAcked != true || confirmed.serverStatus != "CREATED") {
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
        matchClock.start(state.game.currentPlayer)
        update { copy(message = "対局中", error = null, localStartAcked = true, bothStartAcked = true) }
        publishClockState()
        scheduleLocalTimeout()
        drainPendingRemoteMessages()
        true
    }

    suspend fun play(position: Position): Boolean = actionMutex.withLock {
        if (!started || state.matchState.status != MatchStatus.PLAYING) return false
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
                try {
                    transport.send(command)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    update { copy(error = error.message ?: "着手を送信できませんでした", message = "着手送信に失敗") }
                    return false
                }
                applyNext(outcome.state, position, clockSnapshot)
                update { copy(commandCountSent = commandCountSent + 1) }
                true
            }
            else -> false
        }
    }

    suspend fun resign(): MatchFinishResult? = actionMutex.withLock { finishLocallyLocked(FinishReason.RESIGNATION) }

    suspend fun finishNormally(): MatchFinishResult? = actionMutex.withLock { finish(FinishReason.NORMAL) }

    suspend fun retryFinish(): MatchFinishResult? = actionMutex.withLock {
        val submission = activeSubmission ?: return null
        return finish(submission.finishReason, submission.result)
    }

    suspend fun onTimeout(): MatchFinishResult? = actionMutex.withLock { finishLocallyLocked(FinishReason.TIMEOUT) }

    suspend fun finishForDisconnect(): MatchFinishResult? = actionMutex.withLock { finishLocallyLocked(FinishReason.DISCONNECT) }

    private suspend fun handleTransportTermination() = actionMutex.withLock {
        if (closed || state.matchState.status in setOf(
                MatchStatus.CONFIRMED,
                MatchStatus.DISPUTED,
                MatchStatus.PENDING_RESULT,
                MatchStatus.FINISHING,
                MatchStatus.DISCONNECTED,
            )
        ) return@withLock
        if (started && state.matchState.status == MatchStatus.PLAYING) {
            // The DataChannel is already unavailable, so the terminal peer packet cannot be
            // delivered. Persist the local disconnect fact directly; a one-sided submission
            // receives the short PENDING_RESULT lease and can never strand the active lock for
            // the full play lease.
            finish(FinishReason.DISCONNECT, winnerForLoser(localDisc))
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

    fun reportConnectionError(message: String) {
        if (closed || state.matchState.status != MatchStatus.P2P_CONNECTED) return
        update { copy(error = message, message = "P2P接続を再試行しています") }
    }

    /** UI ticker hook. Official timeout scheduling remains inside this controller. */
    fun refreshClock() {
        if (state.matchState.status == MatchStatus.PLAYING) publishClockState()
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

    private fun applyRemoteCommand(command: MoveCommand) {
        when (val validation = validator.validate(state.game, command)) {
            is CommandValidation.Accepted -> applyNext(
                validation.state,
                command.move,
                command.clockSnapshot?.let(::mergeRemoteClock) ?: matchClock.snapshot(),
            ).also {
                update { copy(commandCountReceived = commandCountReceived + 1) }
            }
            is CommandValidation.Duplicate -> Unit
            is CommandValidation.Rejected -> update {
                copy(error = validation.violation.name, message = "対局プロトコルエラー: ${validation.violation.name}")
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
                finish(validation.command.reason.toFinishReason(), winnerForLoser(validation.command.loserDisc))
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

    private fun applyNext(next: GameState, move: Position, clockSnapshot: ClockSnapshot) {
        val resolution = TurnResolver.resolveForcedPasses(next)
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
        if (resolution.state.status is GameStatus.Finished) {
            update { copy(matchState = MatchState(MatchStatus.FINISHING), message = "結果を送信中") }
            callbackScope.launch { finishNormally() }
        }
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
        try {
            transport.sendFinish(command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update { copy(error = error.message ?: "終局通知を送信できませんでした", message = "終局通知に失敗") }
            return null
        }
        return finish(reason, winnerForLoser(localDisc))
    }

    private suspend fun finish(reason: FinishReason, explicitResult: MatchResult? = null): MatchFinishResult? = finishMutex.withLock {
        if (state.matchState.status in setOf(MatchStatus.CONFIRMED, MatchStatus.DISPUTED)) return@withLock lastFinishResult
        val result = explicitResult ?: resultForGame(state.game)
        val submission = activeSubmission ?: MatchSubmission(
            matchId,
            CanonicalMoves.encode(state.moves),
            result,
            state.game.stateHash(),
            reason,
            clockPayload(),
        ).also { activeSubmission = it }
        if (submission.finishReason != reason || submission.result != result) return@withLock lastFinishResult
        timeoutJob?.cancel()
        matchClock.stop()
        publishClockState()
        update { copy(matchState = MatchState(MatchStatus.FINISHING), message = "結果を送信中") }
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
        val next = when (submitted.serverStatus) {
            "CONFIRMED" -> MatchStatus.CONFIRMED
            "DISPUTED" -> MatchStatus.DISPUTED
            "PENDING_RESULT" -> MatchStatus.PENDING_RESULT
            else -> MatchStatus.FAILED
        }
        update { copy(matchState = MatchState(next), message = submitted.serverStatus, error = null, finishResult = submitted) }
        lastFinishResult = submitted
        if (next == MatchStatus.CONFIRMED || next == MatchStatus.DISPUTED) closeTransport()
        submitted
    }

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
        timeoutJob = null
        closeTransport()
        listeners.clear()
        if (cancelCallbackScopeOnClose) callbackScope.cancel()
    }

    private companion object {
        const val MAX_PENDING_REMOTE_MESSAGES = 64
    }

    private fun closeTransport() {
        if (transportClosed) return
        transportClosed = true
        transportSubscription.close()
        finishSubscription.close()
        stateSubscription.close()
        transport.close()
    }
}
