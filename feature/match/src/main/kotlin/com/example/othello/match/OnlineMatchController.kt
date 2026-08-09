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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val callbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val cancelCallbackScopeOnClose: Boolean = true,
) : AutoCloseable {
    private var state = OnlineMatchViewState(
        matchState = MatchState(MatchStatus.P2P_CONNECTED),
        localDisc = localDisc,
    )
    private val listeners = mutableSetOf<(OnlineMatchViewState) -> Unit>()
    private val validator = MoveCommandValidator(matchId, localDisc.opponent())
    private val finishValidator = FinishCommandValidator(matchId, localDisc.opponent())
    private val transportSubscription: AutoCloseable
    private val finishSubscription: AutoCloseable
    private val stateSubscription: AutoCloseable
    private val startMutex = Mutex()
    private val finishMutex = Mutex()
    private var localAckRecorded = false
    private var activeSubmission: MatchSubmission? = null
    private var lastFinishResult: MatchFinishResult? = null
    private var started = false
    private var closed = false
    private var transportClosed = false

    init {
        transportSubscription = transport.observe { command ->
            callbackScope.launch { onRemoteCommand(command) }
        }
        finishSubscription = transport.observeFinish { command ->
            callbackScope.launch { onRemoteFinish(command) }
        }
        stateSubscription = transport.observeState { next ->
            callbackScope.launch {
                if (next == TransportState.CLOSED || next == TransportState.FAILED) onDisconnected()
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
        update { copy(message = "対局中", error = null, localStartAcked = true, bothStartAcked = true) }
        true
    }

    suspend fun play(position: Position): Boolean {
        if (!started || state.matchState.status != MatchStatus.PLAYING) return false
        if (state.game.currentPlayer != localDisc) return false
        val command = MoveCommand(matchId, state.game.ply, position, UUID.randomUUID().toString(), state.game.stateHash())
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
                applyNext(outcome.state, position)
                update { copy(commandCountSent = commandCountSent + 1) }
                true
            }
            else -> false
        }
    }

    suspend fun resign(): MatchFinishResult? = finishLocally(FinishReason.RESIGNATION)

    suspend fun finishNormally(): MatchFinishResult? = finish(FinishReason.NORMAL)

    suspend fun retryFinish(): MatchFinishResult? {
        val submission = activeSubmission ?: return null
        return finish(submission.finishReason, submission.result)
    }

    suspend fun onTimeout(): MatchFinishResult? = finishLocally(FinishReason.TIMEOUT)

    suspend fun finishForDisconnect(): MatchFinishResult? = finishLocally(FinishReason.DISCONNECT)

    fun onDisconnected() {
        if (state.matchState.status in setOf(MatchStatus.CONFIRMED, MatchStatus.DISPUTED, MatchStatus.PENDING_RESULT, MatchStatus.FINISHING)) return
        update { copy(matchState = MatchState(MatchStatus.DISCONNECTED), message = "接続が切断されました") }
    }

    fun reportConnectionError(message: String) {
        if (closed || state.matchState.status != MatchStatus.P2P_CONNECTED) return
        update { copy(error = message, message = "P2P接続を再試行しています") }
    }

    private fun onRemoteCommand(command: MoveCommand) {
        if (closed || state.matchState.status != MatchStatus.PLAYING) return
        when (val validation = validator.validate(state.game, command)) {
            is CommandValidation.Accepted -> applyNext(validation.state, command.move).also {
                update { copy(commandCountReceived = commandCountReceived + 1) }
            }
            is CommandValidation.Duplicate -> Unit
            is CommandValidation.Rejected -> update {
                copy(error = validation.violation.name, message = "対局プロトコルエラー: ${validation.violation.name}")
            }
        }
    }

    private suspend fun onRemoteFinish(command: FinishCommand) {
        if (closed || state.matchState.status != MatchStatus.PLAYING) return
        when (val validation = finishValidator.validate(state.game, command)) {
            is FinishCommandValidation.Accepted -> finish(
                validation.command.reason.toFinishReason(),
                winnerForLoser(validation.command.loserDisc),
            )
            is FinishCommandValidation.Duplicate -> Unit
            is FinishCommandValidation.Rejected -> update {
                copy(error = validation.violation.name, message = "対局プロトコルエラー: ${validation.violation.name}")
            }
        }
    }

    private fun applyNext(next: GameState, move: Position) {
        val resolution = TurnResolver.resolveForcedPasses(next)
        update { copy(game = resolution.state, moves = moves + move + List(resolution.forcedPasses) { null }) }
        if (resolution.state.status is GameStatus.Finished) {
            update { copy(matchState = MatchState(MatchStatus.FINISHING), message = "結果を送信中") }
            callbackScope.launch { finishNormally() }
        }
    }

    private suspend fun finishLocally(reason: FinishReason): MatchFinishResult? {
        if (!started || state.matchState.status != MatchStatus.PLAYING) return lastFinishResult
        val signalReason = reason.toSignalReason() ?: return null
        val command = FinishCommand(
            matchId = matchId,
            ply = state.game.ply,
            commandId = UUID.randomUUID().toString(),
            stateHash = state.game.stateHash(),
            loserDisc = localDisc,
            reason = signalReason,
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
        ).also { activeSubmission = it }
        if (submission.finishReason != reason || submission.result != result) return@withLock lastFinishResult
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
        closeTransport()
        listeners.clear()
        if (cancelCallbackScopeOnClose) callbackScope.cancel()
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
