package com.example.othello

import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.TurnResolver
import com.example.othello.network.peopleplay.Desync
import com.example.othello.network.peopleplay.DesyncReason
import com.example.othello.network.peopleplay.FinishReason
import com.example.othello.network.peopleplay.GameOver
import com.example.othello.network.peopleplay.MoveSnapshot
import com.example.othello.network.peopleplay.Outcome
import com.example.othello.network.peopleplay.PeoplePlayError
import com.example.othello.network.peopleplay.PeoplePlayGameResult
import com.example.othello.network.peopleplay.PeoplePlayParticipantSummary
import com.example.othello.network.peopleplay.PeoplePlayPlayers
import com.example.othello.network.peopleplay.PeoplePlayProtocolCodec
import com.example.othello.network.peopleplay.PeoplePlayRoomSnapshot
import com.example.othello.network.peopleplay.PlayerColor
import com.example.othello.network.peopleplay.ReportedResult
import com.example.othello.network.peopleplay.ResultCheck
import com.example.othello.network.peopleplay.ResultReport
import com.example.othello.network.peopleplay.RoomPhase
import com.example.othello.network.peopleplay.Seat
import com.example.othello.network.peopleplay.TakeSeat
import com.example.othello.network.peopleplay.LeaveSeat
import com.example.othello.network.peopleplay.TimeControl
import com.example.othello.network.peopleplay.TimeoutSelf
import com.example.othello.records.LocalGameRecord
import com.example.othello.records.LocalRecordType
import com.example.othello.records.PeoplePlayLocalColor
import com.example.othello.records.PeoplePlayLocalFinishReason
import com.example.othello.records.PeoplePlayLocalMetadata
import com.example.othello.records.PeoplePlayLocalResult
import com.example.othello.records.PeoplePlayLocalResultSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class PeoplePlayConnectionStatus { CONNECTING, CONNECTED, CLOSED, FAILED }

internal data class PeoplePlayPendingMove(
    val command: MoveSnapshot,
    val resultingCoreState: GameState,
    val resultingMoves: List<Position?>,
    val clockNextTurnIsSelf: Boolean,
)

internal data class PeoplePlayRoomUiState(
    val status: PeoplePlayConnectionStatus = PeoplePlayConnectionStatus.CONNECTING,
    val roomId: String? = null,
    val memberId: String? = null,
    val snapshot: PeoplePlayRoomSnapshot? = null,
    val selfSeat: Seat? = null,
    val selfColor: PlayerColor? = null,
    val isSpectator: Boolean = true,
    val acceptedCoreState: GameState? = null,
    val acceptedMoves: List<Position?> = emptyList(),
    val pendingMove: PeoplePlayPendingMove? = null,
    val resultCheckPending: Boolean = false,
    val gameOver: PeoplePlayGameResult? = null,
    val localRecordId: String? = null,
    val localRecordSaved: Boolean = false,
    val remainingMillis: Long? = null,
    val connectionError: String? = null,
    val lastCommandError: String? = null,
) {
    val canTakeSeat: Boolean get() = status == PeoplePlayConnectionStatus.CONNECTED && snapshot?.phase == RoomPhase.WAITING && selfSeat == null
    val canLeaveSeat: Boolean get() = status == PeoplePlayConnectionStatus.CONNECTED && snapshot?.phase == RoomPhase.WAITING && selfSeat != null
    val legalMoves: Set<Position> get() = if (canMove) acceptedCoreState?.legalMoves.orEmpty() else emptySet()
    val canMove: Boolean get() = status == PeoplePlayConnectionStatus.CONNECTED &&
        snapshot?.phase == RoomPhase.PLAYING && !isSpectator && selfColor != null &&
        snapshot.nextTurn == selfColor && pendingMove == null && !resultCheckPending && gameOver == null &&
        !snapshot.terminalCandidate && remainingMillis != 0L
}

/** Device-only monotonic clock. Display refresh frequency never determines elapsed time. */
internal class PeoplePlayMonotonicClock(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var remainingMillis = 0L
    private var runningSinceNanos: Long? = null
    private var pendingSinceNanos: Long? = null
    private var pendingWasRunning = false

    fun start(initialMillis: Long, active: Boolean) {
        remainingMillis = initialMillis.coerceAtLeast(0)
        runningSinceNanos = if (active && remainingMillis > 0) nowNanos() else null
        pendingSinceNanos = null
        pendingWasRunning = false
    }

    fun remainingMillis(): Long {
        accumulate()
        return remainingMillis
    }

    fun isRunning(): Boolean = runningSinceNanos != null

    fun setActive(active: Boolean) {
        accumulate()
        runningSinceNanos = if (active && remainingMillis > 0) nowNanos() else null
    }

    fun beginPending(nextTurnIsSelf: Boolean) {
        accumulate()
        pendingSinceNanos = nowNanos()
        pendingWasRunning = runningSinceNanos != null
        if (!nextTurnIsSelf) runningSinceNanos = null
    }

    fun acceptPending(active: Boolean) {
        pendingSinceNanos = null
        pendingWasRunning = false
        setActive(active)
    }

    fun rejectPending(acceptedTurnIsSelf: Boolean) {
        val now = nowNanos()
        val pendingStart = pendingSinceNanos
        if (pendingWasRunning && runningSinceNanos == null && pendingStart != null) {
            remainingMillis = (remainingMillis - ((now - pendingStart).coerceAtLeast(0) / 1_000_000L)).coerceAtLeast(0)
        } else {
            accumulate()
        }
        pendingSinceNanos = null
        pendingWasRunning = false
        runningSinceNanos = if (acceptedTurnIsSelf && remainingMillis > 0) now else null
    }

    fun stop() {
        accumulate()
        runningSinceNanos = null
    }

    private fun accumulate() {
        val started = runningSinceNanos ?: return
        val now = nowNanos()
        val elapsed = ((now - started).coerceAtLeast(0) / 1_000_000L)
        remainingMillis = (remainingMillis - elapsed).coerceAtLeast(0)
        runningSinceNanos = if (remainingMillis > 0) now else null
    }
}

/** A process-owned room state machine; the WebSocket and accepted core state survive Compose recreation. */
internal class PeoplePlayRoomStateHolder(
    private val scope: CoroutineScope,
    private val repository: PeoplePlayRepository,
    private val accessToken: suspend () -> String?,
    private val persistence: LocalGameRecordPersistenceCoordinator,
    private val onReady: (String) -> Unit,
    private val onOpenFailure: (PeoplePlayOpenFailure) -> Unit,
    private val monotonicClock: PeoplePlayMonotonicClock = PeoplePlayMonotonicClock(),
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : PeoplePlaySocketListener {
    private val mutableState = MutableStateFlow(PeoplePlayRoomUiState())
    val state: StateFlow<PeoplePlayRoomUiState> = mutableState.asStateFlow()
    private var socket: PeoplePlaySocket? = null
    private var started = false
    private var initialClockStarted = false
    private var timeoutSent = false
    private var timeoutJob: Job? = null
    private val ended = AtomicBoolean(false)
    private val desyncSent = AtomicBoolean(false)

    init {
        scope.launch {
            persistence.saveStates.collect { saves ->
                val localId = mutableState.value.localRecordId ?: return@collect
                if (saves[localId]?.status == com.example.othello.LocalRecordSaveStatus.SAVED) {
                    mutableState.value = mutableState.value.copy(localRecordSaved = true)
                }
            }
        }
    }

    fun connect(roomId: String?) {
        if (started) return
        started = true
        scope.launch {
            val token = try {
                accessToken()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (token.isNullOrBlank()) {
                failOpen(PeoplePlayOpenFailure.Http(401, "AUTH_REQUIRED"))
                return@launch
            }
            try {
                socket = if (roomId == null) repository.createRoom(token, this@PeoplePlayRoomStateHolder)
                else repository.joinRoom(roomId, token, this@PeoplePlayRoomStateHolder)
            } catch (_: Throwable) {
                failOpen(PeoplePlayOpenFailure.Transport)
            }
        }
    }

    @Synchronized
    override fun onOpen(memberId: String) {
        if (memberId.isBlank()) {
            failOpen(PeoplePlayOpenFailure.Protocol)
            return
        }
        mutableState.value = mutableState.value.copy(
            status = PeoplePlayConnectionStatus.CONNECTED,
            memberId = memberId,
        )
    }

    @Synchronized
    override fun onText(text: String) {
        val decoded = PeoplePlayProtocolCodec.decodeServerMessage(text).getOrElse {
            socket?.close()
            setConnectionError("BAD_MESSAGE")
            return
        }
        when (decoded) {
            is PeoplePlayRoomSnapshot -> acceptSnapshot(decoded)
            is ResultCheck -> acceptResultCheck(decoded)
            is GameOver -> acceptGameOver(decoded)
            is PeoplePlayError -> acceptError(decoded)
        }
    }

    @Synchronized
    override fun onClosed() {
        if (mutableState.value.status == PeoplePlayConnectionStatus.FAILED || ended.get()) return
        if (mutableState.value.roomId == null) {
            failOpen(PeoplePlayOpenFailure.Transport)
            return
        }
        if (mutableState.value.gameOver == null) saveLocalDisconnectIfPlayer()
        timeoutJob?.cancel()
        mutableState.value = mutableState.value.copy(status = PeoplePlayConnectionStatus.CLOSED)
    }

    @Synchronized
    override fun onFailure(failure: PeoplePlayOpenFailure) {
        if (mutableState.value.roomId == null || mutableState.value.status == PeoplePlayConnectionStatus.CONNECTING) {
            failOpen(failure)
            return
        }
        if (mutableState.value.gameOver == null) saveLocalDisconnectIfPlayer()
        timeoutJob?.cancel()
        mutableState.value = mutableState.value.copy(
            status = PeoplePlayConnectionStatus.FAILED,
            connectionError = safeFailureCode(failure),
        )
    }

    @Synchronized
    fun takeSeat() = sendIfAllowed {
        val current = mutableState.value
        if (current.canTakeSeat) send(TakeSeat(roomId = requireNotNull(current.roomId)))
    }

    @Synchronized
    fun leaveSeat() = sendIfAllowed {
        val current = mutableState.value
        if (current.canLeaveSeat) send(LeaveSeat(roomId = requireNotNull(current.roomId)))
    }

    @Synchronized
    fun play(position: Position) = sendIfAllowed {
        val current = mutableState.value
        if (!current.canMove || position !in current.legalMoves) return@sendIfAllowed
        val core = requireNotNull(current.acceptedCoreState)
        val playerColor = current.selfColor ?: return@sendIfAllowed
        val pending = buildPeoplePlayMoveCandidate(
            roomId = requireNotNull(current.roomId),
            currentWirePly = requireNotNull(current.snapshot).wirePly,
            acceptedCoreState = core,
            acceptedMoves = current.acceptedMoves,
            position = position,
            selfColor = playerColor,
        ) ?: return@sendIfAllowed
        mutableState.value = current.copy(pendingMove = pending)
        monotonicClock.beginPending(pending.clockNextTurnIsSelf)
        scheduleTimeout()
        if (!send(pending.command)) rejectPendingMove("SEND_FAILED")
    }

    @Synchronized
    fun refreshClock() {
        val current = mutableState.value
        val remaining = if (current.selfColor != null && current.snapshot?.phase == RoomPhase.PLAYING) {
            monotonicClock.remainingMillis()
        } else null
        mutableState.value = current.copy(remainingMillis = remaining)
        maybeTimeout()
    }

    @Synchronized
    fun closeByUser() {
        if (ended.get()) return
        if (mutableState.value.gameOver == null) saveLocalDisconnectIfPlayer()
        ended.set(true)
        timeoutJob?.cancel()
        socket?.close()
        socket = null
        mutableState.value = mutableState.value.copy(status = PeoplePlayConnectionStatus.CLOSED)
    }

    private fun acceptSnapshot(snapshot: PeoplePlayRoomSnapshot) {
        val current = mutableState.value
        val memberId = current.memberId ?: return protocolFailure()
        if (current.roomId != null && current.roomId != snapshot.roomId) return protocolFailure()
        if (current.roomId == null) onReady(snapshot.roomId)
        val selfSeat = when (memberId) {
            snapshot.seats.a?.memberId -> Seat.A
            snapshot.seats.b?.memberId -> Seat.B
            else -> null
        }
        val selfColor = snapshot.players?.let { players ->
            when (memberId) {
                players.black.memberId -> PlayerColor.BLACK
                players.white.memberId -> PlayerColor.WHITE
                else -> null
            }
        }
        val spectator = selfSeat == null && selfColor == null
        var acceptedCore = current.acceptedCoreState
        var acceptedMoves = current.acceptedMoves
        var pending = current.pendingMove
        if (!spectator && snapshot.phase == RoomPhase.PLAYING) {
            val verification = verifyPlayerSnapshot(current, snapshot)
            when (verification) {
                SnapshotVerification.Bad -> return sendDesync(snapshot.wirePly.coerceAtLeast(1))
                is SnapshotVerification.Accept -> {
                    acceptedCore = verification.state
                    acceptedMoves = verification.moves
                    if (verification.echo) {
                        pending = null
                        monotonicClock.acceptPending(verification.state.currentPlayer.toPlayerColorOrNull() == selfColor && !verification.state.status.let { it is GameStatus.Finished })
                    }
                }
                SnapshotVerification.Unchanged -> Unit
            }
        } else if (snapshot.phase == RoomPhase.PLAYING && snapshot.wirePly == 0) {
            if (snapshot.board != GameState().board.toWireBoard()) return sendDesync(1)
            acceptedCore = GameState()
            acceptedMoves = emptyList()
        }
        val next = current.copy(
            status = PeoplePlayConnectionStatus.CONNECTED,
            roomId = snapshot.roomId,
            snapshot = snapshot,
            selfSeat = selfSeat,
            selfColor = selfColor,
            isSpectator = spectator,
            acceptedCoreState = acceptedCore,
            acceptedMoves = acceptedMoves,
            pendingMove = pending,
            resultCheckPending = current.resultCheckPending || snapshot.resultCheckPly != null,
            lastCommandError = null,
        )
        mutableState.value = next
        if (next.resultCheckPending) {
            timeoutJob?.cancel()
            monotonicClock.stop()
        } else if (snapshot.phase == RoomPhase.PLAYING && selfColor != null && !initialClockStarted) {
            initialClockStarted = true
            val starts = selfColor == PlayerColor.BLACK && snapshot.nextTurn == PlayerColor.BLACK
            monotonicClock.start(snapshot.timeControl.initialMillis, starts)
        } else if (snapshot.phase == RoomPhase.PLAYING && selfColor != null && pending == null && !next.resultCheckPending) {
            monotonicClock.setActive(snapshot.nextTurn == selfColor && snapshot.terminalCandidate.not())
        }
        refreshClock()
        scheduleTimeout()
    }

    private fun verifyPlayerSnapshot(current: PeoplePlayRoomUiState, next: PeoplePlayRoomSnapshot): SnapshotVerification {
        val accepted = current.acceptedCoreState
        val old = current.snapshot
        if (accepted == null || old == null || old.phase != RoomPhase.PLAYING) {
            if (next.wirePly != 0) return SnapshotVerification.Bad
            return if (next.board == GameState().board.toWireBoard() && next.nextTurn == PlayerColor.BLACK && !next.terminalCandidate) {
                SnapshotVerification.Accept(GameState(), emptyList(), false)
            } else SnapshotVerification.Bad
        }
        if (next.wirePly == old.wirePly) {
            val sameBoardAndTurn = next.board == old.board && next.move == old.move &&
                next.nextTurn == old.nextTurn && next.terminalCandidate == old.terminalCandidate
            return if (sameBoardAndTurn) SnapshotVerification.Unchanged else SnapshotVerification.Bad
        }
        if (next.wirePly != old.wirePly + 1) return SnapshotVerification.Bad
        val pending = current.pendingMove
        if (pending != null) {
            val expected = pending.command
            val echo = next.wirePly == expected.wirePly && next.move == expected.move &&
                next.board == expected.board && next.nextTurn == expected.nextTurn &&
                next.terminalCandidate == expected.terminalCandidate
            return if (echo) SnapshotVerification.Accept(pending.resultingCoreState, pending.resultingMoves, true)
            else SnapshotVerification.Bad
        }
        val selfColor = current.selfColor ?: return SnapshotVerification.Bad
        if (accepted.currentPlayer.toPlayerColorOrNull() == selfColor) return SnapshotVerification.Bad
        val move = next.move ?: return SnapshotVerification.Bad
        val outcome = accepted.play(Position(move.row, move.column)) as? MoveOutcome.Played ?: return SnapshotVerification.Bad
        val resolved = TurnResolver.resolveForcedPasses(outcome.state)
        val terminal = resolved.state.status is GameStatus.Finished
        val expectedTurn = if (terminal) null else resolved.state.currentPlayer.toPlayerColorOrNull()
        val expectedMoves = current.acceptedMoves + listOf(Position(move.row, move.column)) + List(resolved.forcedPasses) { null }
        return if (resolved.state.board.toWireBoard() == next.board && expectedTurn == next.nextTurn && terminal == next.terminalCandidate) {
            SnapshotVerification.Accept(resolved.state, expectedMoves, false)
        } else SnapshotVerification.Bad
    }

    private fun acceptResultCheck(message: ResultCheck) {
        val current = mutableState.value
        if (message.roomId != current.roomId || message.wirePly != current.snapshot?.wirePly || current.isSpectator || current.gameOver != null) {
            sendDesync(current.snapshot?.wirePly?.coerceAtLeast(1) ?: 1, DesyncReason.RESULT_MISMATCH)
            return
        }
        mutableState.value = current.copy(resultCheckPending = true)
        timeoutJob?.cancel()
        monotonicClock.stop()
        refreshClock()
        val result = current.acceptedCoreState?.toReportedResult() ?: ReportedResult.NOT_FINISHED
        send(ResultReport(roomId = message.roomId, wirePly = message.wirePly, result = result))
    }

    private fun acceptGameOver(message: GameOver) {
        val current = mutableState.value
        if (message.roomId != current.roomId || current.gameOver != null) return
        val snapshot = message.finalSnapshot
        if (snapshot.roomId != message.roomId || snapshot.wirePly != message.wirePly) return protocolFailure()
        if (!ended.compareAndSet(false, true)) return
        // A valid server result is final. Do not let the following socket close turn it into DISCONNECT.
        timeoutJob?.cancel()
        monotonicClock.stop()
        val savedRecord = if (!current.isSpectator && current.selfColor != null) {
            buildLocalRecord(current, message, PeoplePlayLocalResultSource.SERVER_MESSAGE)
        } else null
        if (savedRecord != null) persistence.enqueue(savedRecord)
        mutableState.value = current.copy(
            snapshot = snapshot,
            gameOver = message.result,
            pendingMove = null,
            resultCheckPending = false,
            localRecordId = savedRecord?.localId,
            localRecordSaved = savedRecord == null || persistence.state(savedRecord.localId)?.status == LocalRecordSaveStatus.SAVED,
            remainingMillis = current.selfColor?.let { monotonicClock.remainingMillis() },
        )
    }

    private fun acceptError(error: PeoplePlayError) {
        val current = mutableState.value
        if (error.roomId != current.roomId) return protocolFailure()
        if (error.rejectedType == "MOVE_SNAPSHOT" && current.pendingMove != null) rejectPendingMove(error.code.name)
        else mutableState.value = current.copy(lastCommandError = error.code.name)
    }

    private fun rejectPendingMove(code: String) {
        val current = mutableState.value
        if (current.pendingMove == null) return
        val active = current.snapshot?.nextTurn == current.selfColor && current.selfColor != null && !current.resultCheckPending
        monotonicClock.rejectPending(active)
        mutableState.value = current.copy(pendingMove = null, lastCommandError = code)
        refreshClock()
        scheduleTimeout()
    }

    private fun saveLocalDisconnectIfPlayer() {
        val current = mutableState.value
        val snapshot = current.snapshot ?: return
        val color = current.selfColor ?: return
        if (snapshot.phase != RoomPhase.PLAYING || current.isSpectator || current.gameOver != null) return
        if (!ended.compareAndSet(false, true)) return
        timeoutJob?.cancel()
        monotonicClock.stop()
        val opponent = snapshot.players?.opponent(color) ?: return
        val result = if (color == PlayerColor.BLACK) Outcome.WHITE_WIN else Outcome.BLACK_WIN
        val metadata = PeoplePlayLocalMetadata(
            roomId = snapshot.roomId,
            playedAtEpochMillis = wallClockMillis(),
            opponentDisplayName = opponent.displayName,
            playerColor = color.toLocalColor(),
            timeControl = snapshot.timeControl.name,
            result = result.toLocalResult(),
            finishReason = PeoplePlayLocalFinishReason.DISCONNECT,
            resultSource = PeoplePlayLocalResultSource.LOCAL_DISCONNECT,
            moveHistoryComplete = true,
        )
        val record = LocalGameRecord(
            localId = "people-play:${snapshot.roomId}:${current.memberId}",
            moves = current.acceptedMoves,
            createdAtEpochMillis = metadata.playedAtEpochMillis,
            type = LocalRecordType.PEOPLE_PLAY,
            playerDisc = color.toDisc(),
            peoplePlay = metadata,
        )
        persistence.enqueue(record)
        mutableState.value = current.copy(localRecordId = record.localId, localRecordSaved = false)
    }

    private fun buildLocalRecord(
        current: PeoplePlayRoomUiState,
        gameOver: GameOver,
        source: PeoplePlayLocalResultSource,
    ): LocalGameRecord {
        val snapshot = gameOver.finalSnapshot
        val color = current.selfColor ?: error("Only a player can store People Play results")
        val opponent = requireNotNull(snapshot.players).opponent(color)
        val metadata = PeoplePlayLocalMetadata(
            roomId = snapshot.roomId,
            playedAtEpochMillis = gameOver.decidedAt,
            opponentDisplayName = opponent.displayName,
            playerColor = color.toLocalColor(),
            timeControl = snapshot.timeControl.name,
            result = gameOver.outcome.toLocalResult(),
            finishReason = gameOver.finishReason.toLocalFinishReason(),
            resultSource = source,
            moveHistoryComplete = gameOver.finishReason != FinishReason.DESYNC,
        )
        return LocalGameRecord(
            localId = "people-play:${snapshot.roomId}:${current.memberId}",
            moves = current.acceptedMoves,
            createdAtEpochMillis = gameOver.decidedAt,
            type = LocalRecordType.PEOPLE_PLAY,
            playerDisc = color.toDisc(),
            peoplePlay = metadata,
        )
    }

    private fun sendDesync(observedPly: Int, reason: DesyncReason = DesyncReason.SNAPSHOT_MISMATCH) {
        val current = mutableState.value
        val roomId = current.roomId ?: return
        if (current.isSpectator || current.selfColor == null || current.gameOver != null || !desyncSent.compareAndSet(false, true)) return
        send(Desync(roomId = roomId, observedPly = observedPly.coerceAtLeast(1), reason = reason))
        timeoutJob?.cancel()
        monotonicClock.stop()
        mutableState.value = current.copy(lastCommandError = "DESYNC", resultCheckPending = true)
    }

    private fun sendIfAllowed(action: () -> Unit) = action()

    private fun send(message: com.example.othello.network.peopleplay.PeoplePlayClientMessage): Boolean {
        val text = runCatching { PeoplePlayProtocolCodec.encodeClientMessage(message) }.getOrNull() ?: return false
        return socket?.send(text) == true
    }

    private fun protocolFailure() {
        socket?.close()
        setConnectionError("BAD_MESSAGE")
    }

    private fun failOpen(failure: PeoplePlayOpenFailure) {
        onOpenFailure(failure)
        mutableState.value = mutableState.value.copy(
            status = PeoplePlayConnectionStatus.FAILED,
            connectionError = safeFailureCode(failure),
        )
    }

    private fun setConnectionError(code: String) {
        mutableState.value = mutableState.value.copy(
            status = PeoplePlayConnectionStatus.FAILED,
            connectionError = code,
        )
    }

    private fun scheduleTimeout() {
        timeoutJob?.cancel()
        val current = mutableState.value
        if (current.gameOver != null || current.isSpectator || current.selfColor == null || !monotonicClock.isRunning()) return
        val remaining = monotonicClock.remainingMillis()
        if (remaining <= 0) {
            maybeTimeout()
            return
        }
        timeoutJob = scope.launch {
            delay(remaining)
            maybeTimeout()
        }
    }

    private fun maybeTimeout() {
        val current = mutableState.value
        if (timeoutSent || current.isSpectator || current.selfColor == null || current.gameOver != null || current.snapshot?.phase != RoomPhase.PLAYING) return
        if (monotonicClock.remainingMillis() > 0) return
        timeoutSent = true
        timeoutJob?.cancel()
        monotonicClock.stop()
        send(TimeoutSelf(roomId = current.roomId ?: return))
        mutableState.value = current.copy(remainingMillis = 0L)
    }

    private sealed interface SnapshotVerification {
        data class Accept(val state: GameState, val moves: List<Position?>, val echo: Boolean) : SnapshotVerification
        data object Unchanged : SnapshotVerification
        data object Bad : SnapshotVerification
    }
}

internal fun Board.toWireBoard(): List<Int> = buildList(64) {
    for (row in 0 until 8) for (column in 0 until 8) {
        add(when (this@toWireBoard[Position(row, column)]) {
            Disc.EMPTY -> 0
            Disc.BLACK -> 1
            Disc.WHITE -> 2
        })
    }
}

internal fun buildPeoplePlayMoveCandidate(
    roomId: String,
    currentWirePly: Int,
    acceptedCoreState: GameState,
    acceptedMoves: List<Position?>,
    position: Position,
    selfColor: PlayerColor,
): PeoplePlayPendingMove? {
    if (roomId.isBlank() || acceptedCoreState.currentPlayer.toPlayerColorOrNull() != selfColor ||
        position !in acceptedCoreState.legalMoves
    ) return null
    val played = acceptedCoreState.play(position) as? MoveOutcome.Played ?: return null
    val resolved = TurnResolver.resolveForcedPasses(played.state)
    val nextColor = resolved.state.currentPlayer.toPlayerColorOrNull()
    val terminal = resolved.state.status is GameStatus.Finished
    val command = MoveSnapshot(
        roomId = roomId,
        wirePly = currentWirePly + 1,
        move = com.example.othello.network.peopleplay.PeoplePlayMove(position.row, position.column),
        board = resolved.state.board.toWireBoard(),
        nextTurn = if (terminal) null else nextColor,
        terminalCandidate = terminal,
    )
    return PeoplePlayPendingMove(
        command = command,
        resultingCoreState = resolved.state,
        resultingMoves = acceptedMoves + listOf(position) + List(resolved.forcedPasses) { null },
        clockNextTurnIsSelf = !terminal && nextColor == selfColor,
    )
}

internal fun wireBoardToCoreState(board: List<Int>, nextTurn: PlayerColor, ply: Int = 0): GameState {
    require(board.size == 64 && board.all { it in 0..2 })
    val rows = (0 until 8).map { row ->
        (0 until 8).joinToString("") { column -> when (board[row * 8 + column]) { 0 -> "."; 1 -> "B"; 2 -> "W"; else -> error("invalid cell") } }
    }
    return GameState(
        board = Board.fromRows(rows),
        currentPlayer = nextTurn.toDisc(),
        ply = ply,
    )
}

private fun GameState.toReportedResult(): ReportedResult = when (val gameStatus = status) {
    GameStatus.InProgress -> ReportedResult.NOT_FINISHED
    is GameStatus.Finished -> when (gameStatus.result.winner) {
        Disc.BLACK -> ReportedResult.BLACK_WIN
        Disc.WHITE -> ReportedResult.WHITE_WIN
        null -> ReportedResult.DRAW
        Disc.EMPTY -> error("empty cannot win")
    }
}

private fun Disc.toPlayerColorOrNull(): PlayerColor? = when (this) { Disc.BLACK -> PlayerColor.BLACK; Disc.WHITE -> PlayerColor.WHITE; Disc.EMPTY -> null }
private fun PlayerColor.toDisc(): Disc = when (this) { PlayerColor.BLACK -> Disc.BLACK; PlayerColor.WHITE -> Disc.WHITE }
private fun PeoplePlayPlayers.opponent(color: PlayerColor): PeoplePlayParticipantSummary = if (color == PlayerColor.BLACK) white else black
private fun PlayerColor.toLocalColor(): PeoplePlayLocalColor = if (this == PlayerColor.BLACK) PeoplePlayLocalColor.BLACK else PeoplePlayLocalColor.WHITE
private fun Outcome.toLocalResult(): PeoplePlayLocalResult = when (this) {
    Outcome.BLACK_WIN -> PeoplePlayLocalResult.BLACK_WIN
    Outcome.WHITE_WIN -> PeoplePlayLocalResult.WHITE_WIN
    Outcome.DRAW -> PeoplePlayLocalResult.DRAW
    Outcome.NO_CONTEST -> PeoplePlayLocalResult.NO_CONTEST
}
private fun FinishReason.toLocalFinishReason(): PeoplePlayLocalFinishReason = when (this) {
    FinishReason.NORMAL -> PeoplePlayLocalFinishReason.NORMAL
    FinishReason.TIMEOUT -> PeoplePlayLocalFinishReason.TIMEOUT
    FinishReason.DISCONNECT -> PeoplePlayLocalFinishReason.DISCONNECT
    FinishReason.DESYNC -> PeoplePlayLocalFinishReason.DESYNC
}
private fun PeoplePlayOpenFailure.safeCode(): String = when (this) {
    is PeoplePlayOpenFailure.Http -> errorCode ?: "HTTP_$status"
    PeoplePlayOpenFailure.Protocol -> "BAD_HANDSHAKE"
    PeoplePlayOpenFailure.Transport -> "CONNECTION_FAILED"
}
private fun safeFailureCode(failure: PeoplePlayOpenFailure): String = failure.safeCode()
