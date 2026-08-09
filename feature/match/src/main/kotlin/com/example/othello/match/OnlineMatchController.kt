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
import com.example.othello.network.ProtocolViolation
import com.example.othello.network.TransportState
import com.example.othello.records.FinishReason
import com.example.othello.records.MatchResult
import java.util.UUID

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
)

/** Coordinates local game rules, DataChannel validation and result submission. */
class OnlineMatchController(
    private val matchId: String,
    private val localDisc: Disc,
    private val transport: MatchTransport,
    private val repository: OnlineMatchRepository,
    private val ackAttempts: Int = 3,
) : AutoCloseable {
    private var state = OnlineMatchViewState(
        matchState = MatchState(MatchStatus.P2P_CONNECTED),
        localDisc = localDisc,
    )
    private val listeners = mutableSetOf<(OnlineMatchViewState) -> Unit>()
    private val validator = MoveCommandValidator(matchId, localDisc.opponent())
    private val transportSubscription: AutoCloseable
    private val stateSubscription: AutoCloseable
    private var started = false
    private var closed = false

    init {
        transportSubscription = transport.observe { command -> onRemoteCommand(command) }
        stateSubscription = transport.observeState { next ->
            if (next == TransportState.CLOSED || next == TransportState.FAILED) onDisconnected()
        }
    }

    val viewState: OnlineMatchViewState get() = state

    fun observe(listener: (OnlineMatchViewState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    suspend fun onDataChannelOpen(): Boolean {
        if (started) return true
        transition(MatchCommand.DataChannelOpened)
        var failure: Throwable? = null
        repeat(ackAttempts) {
            try {
                repository.ackMatchStarted(matchId)
                started = true
                update { copy(matchState = MatchState(MatchStatus.PLAYING), message = "対局中", localStartAcked = true) }
                return true
            } catch (error: Throwable) {
                failure = error
            }
        }
        update { copy(matchState = MatchState(MatchStatus.FAILED, failure?.message), error = failure?.message, message = "開始確認に失敗") }
        return false
    }

    suspend fun play(position: Position): Boolean {
        if (!started || state.matchState.status != MatchStatus.PLAYING) return false
        if (state.game.currentPlayer != localDisc) return false
        val command = MoveCommand(matchId, state.game.ply, position, UUID.randomUUID().toString(), state.game.stateHash())
        return when (val outcome = state.game.play(position)) {
            is MoveOutcome.Played -> {
                applyNext(outcome.state, position)
                transport.send(command)
                update { copy(commandCountSent = commandCountSent + 1) }
                true
            }
            else -> false
        }
    }

    suspend fun resign(): MatchFinishResult? = finish(FinishReason.RESIGNATION)

    suspend fun finishNormally(): MatchFinishResult? = finish(FinishReason.NORMAL)

    suspend fun onTimeout(): MatchFinishResult? = finish(FinishReason.TIMEOUT)

    suspend fun finishForDisconnect(): MatchFinishResult? = finish(FinishReason.DISCONNECTED)

    fun onDisconnected() {
        if (state.matchState.status in setOf(MatchStatus.CONFIRMED, MatchStatus.DISPUTED, MatchStatus.PENDING_RESULT, MatchStatus.FINISHING)) return
        update { copy(matchState = MatchState(MatchStatus.DISCONNECTED), message = "接続が切断されました") }
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

    private fun applyNext(next: GameState, move: Position) {
        val resolution = TurnResolver.resolveForcedPasses(next)
        update { copy(game = resolution.state, moves = moves + move + List(resolution.forcedPasses) { null }) }
        if (resolution.state.status is GameStatus.Finished) update { copy(matchState = MatchState(MatchStatus.FINISHING), message = "結果を送信中") }
    }

    private suspend fun finish(reason: FinishReason): MatchFinishResult? {
        if (state.matchState.status == MatchStatus.CONFIRMED) return null
        update { copy(matchState = MatchState(MatchStatus.FINISHING), message = "結果を送信中") }
        val result = resultForGame(state.game)
        val submitted = repository.submitMatchResult(
            MatchSubmission(matchId, CanonicalMoves.encode(state.moves), result, state.game.stateHash(), reason),
        )
        val next = when (submitted.serverStatus) {
            "CONFIRMED" -> MatchStatus.CONFIRMED
            "DISPUTED" -> MatchStatus.DISPUTED
            "PENDING_RESULT" -> MatchStatus.PENDING_RESULT
            else -> MatchStatus.FAILED
        }
        update { copy(matchState = MatchState(next), message = submitted.serverStatus, error = null) }
        return submitted
    }

    private fun resultForGame(game: GameState): MatchResult {
        val result = (game.status as? GameStatus.Finished)?.result
        return when {
            result == null -> if (localDisc == Disc.BLACK) MatchResult.BLACK_WIN else MatchResult.WHITE_WIN
            result.winner == Disc.BLACK -> MatchResult.BLACK_WIN
            result.winner == Disc.WHITE -> MatchResult.WHITE_WIN
            else -> MatchResult.DRAW
        }
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
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        transportSubscription.close()
        stateSubscription.close()
        transport.close()
        listeners.clear()
    }
}
