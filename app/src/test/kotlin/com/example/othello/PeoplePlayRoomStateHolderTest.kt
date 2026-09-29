package com.example.othello

import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.Position
import com.example.othello.game.TurnResolver
import com.example.othello.network.peopleplay.FinishReason
import com.example.othello.network.peopleplay.GameOver
import com.example.othello.network.peopleplay.Outcome
import com.example.othello.network.peopleplay.PeoplePlayError
import com.example.othello.network.peopleplay.PeoplePlayErrorCode
import com.example.othello.network.peopleplay.PeoplePlayParticipantSummary
import com.example.othello.network.peopleplay.PeoplePlayPlayers
import com.example.othello.network.peopleplay.PeoplePlayProtocolCodec
import com.example.othello.network.peopleplay.PeoplePlayRoomSnapshot
import com.example.othello.network.peopleplay.PeoplePlaySeats
import com.example.othello.network.peopleplay.PlayerColor
import com.example.othello.network.peopleplay.ResultCheck
import com.example.othello.network.peopleplay.RoomPhase
import com.example.othello.network.peopleplay.Seat
import com.example.othello.network.peopleplay.TimeControl
import com.example.othello.records.LocalGameRecord
import com.example.othello.records.LocalGameRecordStore
import com.example.othello.records.PeoplePlayLocalResult
import com.example.othello.records.PeoplePlayLocalResultSource
import com.example.othello.records.LocalRecordType
import com.example.othello.records.PeoplePlayLocalFinishReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeoplePlayRoomStateHolderTest {
    @Test
    fun creatorSeatAndJoinSpectatorAreDerivedFromSocketMemberId() = withRoomScope { scope ->
        val persistence = persistence(scope)
        val creatorRepo = FakePeoplePlayRepository("member-creator")
        val creator = holder(scope, creatorRepo, persistence)
        creator.connect(null)
        creatorRepo.sendSnapshot(waitingSnapshot(seats = PeoplePlaySeats(participant("member-creator"), null)))
        assertEquals("member-creator", creator.state.value.memberId)
        assertEquals(Seat.A, creator.state.value.selfSeat)
        assertFalse(creator.state.value.isSpectator)
        assertFalse(creator.state.value.canTakeSeat)
        creator.leaveSeat()
        assertTrue(creatorRepo.socket.sent.single().contains("LEAVE_SEAT"))

        val joinRepo = FakePeoplePlayRepository("member-watcher")
        val watcher = holder(scope, joinRepo, persistence)
        watcher.connect("room-fixture")
        joinRepo.sendSnapshot(waitingSnapshot(seats = PeoplePlaySeats(participant("member-creator"), null), spectatorCount = 1))
        assertEquals(null, watcher.state.value.selfSeat)
        assertTrue(watcher.state.value.isSpectator)
        assertTrue(watcher.state.value.canTakeSeat)
        watcher.takeSeat()
        assertTrue(joinRepo.socket.sent.single().contains("TAKE_SEAT"))
    }

    @Test
    fun acceptedSenderEchoCommitsOnePendingMoveAndRejectsFurtherInputUntilEcho() = withRoomScope { scope ->
        val repo = FakePeoplePlayRepository("member-black")
        val holder = holder(scope, repo, persistence(scope))
        holder.connect("room-fixture")
        repo.sendSnapshot(playingSnapshot())
        assertTrue(holder.state.value.canMove)

        val position = Position(2, 3)
        holder.play(position)
        assertEquals(1, repo.socket.sent.size)
        assertNotNull(holder.state.value.pendingMove)
        holder.play(Position(2, 2))
        assertEquals(1, repo.socket.sent.size)

        val played = GameState().play(position) as com.example.othello.game.MoveOutcome.Played
        val resolved = TurnResolver.resolveForcedPasses(played.state)
        repo.sendSnapshot(
            playingSnapshot(
                wirePly = 1,
                board = resolved.state.board.toWireBoard(),
                move = com.example.othello.network.peopleplay.PeoplePlayMove(position.row, position.column),
                nextTurn = PlayerColor.WHITE,
            ),
        )
        assertNull(holder.state.value.pendingMove)
        assertEquals(1, holder.state.value.snapshot?.wirePly)
        assertEquals(1, holder.state.value.acceptedMoves.size)
        assertEquals(1, holder.state.value.acceptedCoreState?.ply)
        assertFalse(holder.state.value.canMove)

        repo.send(
            PeoplePlayError("room-fixture", PeoplePlayErrorCode.BAD_PLY, "MOVE_SNAPSHOT", 1, 3),
        )
        assertEquals(1, holder.state.value.snapshot?.wirePly)
        assertEquals(1, holder.state.value.acceptedMoves.size)
    }

    @Test
    fun structurallyValidButWrongBoardTriggersDesyncWithoutAdoptingIt() = withRoomScope { scope ->
        val repo = FakePeoplePlayRepository("member-white")
        val holder = holder(scope, repo, persistence(scope))
        holder.connect("room-fixture")
        repo.sendSnapshot(playingSnapshot(seatA = participant("member-black"), seatB = participant("member-white"), selfWhite = true))
        val currentBoard = holder.state.value.snapshot!!.board
        repo.sendSnapshot(
            playingSnapshot(
                seatA = participant("member-black"), seatB = participant("member-white"), selfWhite = true,
                wirePly = 1,
                board = currentBoard.toMutableList().also { it[0] = 1 },
                move = com.example.othello.network.peopleplay.PeoplePlayMove(2, 3),
                nextTurn = PlayerColor.WHITE,
            ),
        )
        assertEquals(0, holder.state.value.snapshot?.wirePly)
        assertTrue(holder.state.value.resultCheckPending)
        assertEquals("DESYNC", holder.state.value.lastCommandError)
        assertTrue(repo.socket.sent.single().contains("DESYNC"))
    }

    @Test
    fun peerMoveIsRecomputedAndAcceptedFromTheOtherPlayerSnapshot() = withRoomScope { scope ->
        val repo = FakePeoplePlayRepository("member-white")
        val holder = holder(scope, repo, persistence(scope))
        holder.connect("room-fixture")
        repo.sendSnapshot(playingSnapshot())

        val move = Position(2, 3)
        val played = GameState().play(move) as com.example.othello.game.MoveOutcome.Played
        val resolved = TurnResolver.resolveForcedPasses(played.state)
        repo.sendSnapshot(
            playingSnapshot(
                wirePly = 1,
                board = resolved.state.board.toWireBoard(),
                move = com.example.othello.network.peopleplay.PeoplePlayMove(move.row, move.column),
                nextTurn = PlayerColor.WHITE,
            ),
        )

        assertEquals(1, holder.state.value.snapshot?.wirePly)
        assertEquals(resolved.state, holder.state.value.acceptedCoreState)
        assertEquals(listOf(move), holder.state.value.acceptedMoves)
        assertTrue(holder.state.value.canMove)
    }

    @Test
    fun pendingMoveRejectedByServerDoesNotAdvanceAcceptedState() = withRoomScope { scope ->
        val repo = FakePeoplePlayRepository("member-black")
        val holder = holder(scope, repo, persistence(scope))
        holder.connect("room-fixture")
        repo.sendSnapshot(playingSnapshot())
        holder.play(Position(2, 3))
        assertNotNull(holder.state.value.pendingMove)

        repo.send(PeoplePlayError("room-fixture", PeoplePlayErrorCode.BAD_PLY, "MOVE_SNAPSHOT", 0, 1))

        assertNull(holder.state.value.pendingMove)
        assertEquals(0, holder.state.value.snapshot?.wirePly)
        assertEquals(GameState(), holder.state.value.acceptedCoreState)
        assertEquals("BAD_PLY", holder.state.value.lastCommandError)
        assertTrue(holder.state.value.canMove)
    }

    @Test
    fun opponentNextTurnMismatchTriggersDesyncAndKeepsAcceptedSnapshot() = withRoomScope { scope ->
        val repo = FakePeoplePlayRepository("member-white")
        val holder = holder(scope, repo, persistence(scope))
        holder.connect("room-fixture")
        repo.sendSnapshot(playingSnapshot())
        val initial = holder.state.value.snapshot
        val move = Position(2, 3)
        val played = GameState().play(move) as com.example.othello.game.MoveOutcome.Played
        val board = TurnResolver.resolveForcedPasses(played.state).state.board.toWireBoard()

        repo.sendSnapshot(
            playingSnapshot(
                wirePly = 1,
                board = board,
                move = com.example.othello.network.peopleplay.PeoplePlayMove(move.row, move.column),
                nextTurn = PlayerColor.BLACK,
            ),
        )

        assertEquals(initial, holder.state.value.snapshot)
        assertEquals("DESYNC", holder.state.value.lastCommandError)
    }

    @Test
    fun forcedPassIncreasesCorePlyButNotWirePly() {
        val before = listOf(
            "..BBBBWB",
            "BBBBB.WB",
            "BWBWBBWB",
            "BWBBWBWB",
            "BWWWBWWB",
            "BWBWWBWB",
            "BWWWWWBB",
            "BWWBBBBB",
        )
        val state = GameState(
            board = Board.fromRows(before),
            currentPlayer = Disc.WHITE,
            ply = 57,
        )
        val pending = buildPeoplePlayMoveCandidate(
            roomId = "room-fixture",
            currentWirePly = 57,
            acceptedCoreState = state,
            acceptedMoves = emptyList(),
            position = Position(1, 5),
            selfColor = PlayerColor.WHITE,
        )
        assertNotNull(pending)
        assertEquals(58, pending.command.wirePly)
        assertEquals(59, pending.resultingCoreState.ply)
        assertEquals(PlayerColor.WHITE, pending.command.nextTurn)
        assertEquals(listOf(Position(1, 5), null), pending.resultingMoves)
        assertTrue(pending.clockNextTurnIsSelf)
    }

    @Test
    fun gameOverIsSavedOnceAsServerOutcomeAndCloseCannotReplaceIt() = withRoomScope { scope ->
        val store = MemoryRecordStore()
        val repo = FakePeoplePlayRepository("member-black")
        val holder = holder(scope, repo, LocalGameRecordPersistenceCoordinator(store, scope))
        holder.connect("room-fixture")
        val live = playingSnapshot()
        repo.sendSnapshot(live)
        val result = GameOver(
            roomId = "room-fixture", wirePly = 0, finishReason = FinishReason.NORMAL,
            outcome = Outcome.DRAW, winner = null, decidedAt = 1234, finalSnapshot = live,
        )
        repo.send(result)
        repo.send(result)
        repo.socket.listener.onClosed()
        assertEquals(1, store.records.size)
        val saved = store.records.single()
        assertEquals(LocalRecordType.PEOPLE_PLAY, saved.type)
        assertEquals(PeoplePlayLocalResult.DRAW, saved.peoplePlay?.result)
        assertEquals(PeoplePlayLocalResultSource.SERVER_MESSAGE, saved.peoplePlay?.resultSource)
        assertEquals(PeoplePlayLocalFinishReason.NORMAL, saved.peoplePlay?.finishReason)
        assertEquals("people-play:room-fixture:member-black", saved.localId)
        assertEquals(0, saved.moves.size)
        assertTrue(holder.state.value.localRecordSaved)
    }

    @Test
    fun localPlayerDisconnectSavesLossWhileSpectatorDisconnectDoesNotSave() = withRoomScope { scope ->
        val store = MemoryRecordStore()
        val persistence = LocalGameRecordPersistenceCoordinator(store, scope)
        val playerRepo = FakePeoplePlayRepository("member-black")
        val player = holder(scope, playerRepo, persistence)
        player.connect("room-fixture")
        playerRepo.sendSnapshot(playingSnapshot())
        player.closeByUser()
        assertEquals(1, store.records.size)
        assertEquals(PeoplePlayLocalResult.WHITE_WIN, store.records.single().peoplePlay?.result)
        assertEquals(PeoplePlayLocalResultSource.LOCAL_DISCONNECT, store.records.single().peoplePlay?.resultSource)
        assertEquals(PeoplePlayLocalFinishReason.DISCONNECT, store.records.single().peoplePlay?.finishReason)

        val spectatorRepo = FakePeoplePlayRepository("member-watcher")
        val spectator = holder(scope, spectatorRepo, persistence)
        spectator.connect("room-fixture")
        spectatorRepo.sendSnapshot(playingSnapshot(spectatorCount = 1))
        spectator.closeByUser()
        assertEquals(1, store.records.size)
        assertTrue(spectator.state.value.isSpectator)
    }

    @Test
    fun spectatorSnapshotIsReadOnlyAndResultCheckDoesNotProducePlayerReport() = withRoomScope { scope ->
        val repo = FakePeoplePlayRepository("member-watcher")
        val holder = holder(scope, repo, persistence(scope))
        holder.connect("room-fixture")
        holder.sendSnapshotForTest(playingSnapshot(spectatorCount = 1))
        assertTrue(holder.state.value.isSpectator)
        holder.play(Position(2, 3))
        holder.sendServerMessageForTest(ResultCheck("room-fixture", 0))
        assertTrue(repo.socket.sent.isEmpty())
        assertFalse(holder.state.value.canMove)
    }

    @Test
    fun resultCheckReportsCoreResultAndFreezesTheMonotonicClock() = withRoomScope { scope ->
        var nanos = 0L
        val clock = PeoplePlayMonotonicClock { nanos }
        val repo = FakePeoplePlayRepository("member-black")
        val holder = PeoplePlayRoomStateHolder(scope, repo, { "fixture-token" }, persistence(scope), {}, {}, clock)
        holder.connect("room-fixture")
        repo.sendSnapshot(playingSnapshot())
        nanos += 125_000_000
        repo.send(ResultCheck("room-fixture", 0))
        val report = PeoplePlayProtocolCodec.decodeClientMessage(repo.socket.sent.single()).getOrThrow() as com.example.othello.network.peopleplay.ResultReport
        assertEquals(com.example.othello.network.peopleplay.ReportedResult.NOT_FINISHED, report.result)
        assertTrue(holder.state.value.resultCheckPending)
        val atResultCheck = clock.remainingMillis()
        nanos += 500_000_000
        assertEquals(atResultCheck, clock.remainingMillis())
    }

    @Test
    fun monotonicTimeoutSendsSelfTimeoutExactlyOnce() = withRoomScope { scope ->
        var nanos = 0L
        val clock = PeoplePlayMonotonicClock { nanos }
        val repo = FakePeoplePlayRepository("member-black")
        val holder = PeoplePlayRoomStateHolder(scope, repo, { "fixture-token" }, persistence(scope), {}, {}, clock)
        holder.connect("room-fixture")
        repo.sendSnapshot(playingSnapshot())
        nanos += TimeControl.TWENTY_MINUTES.initialMillis * 1_000_000L
        holder.refreshClock()
        holder.refreshClock()
        val commands = repo.socket.sent.map { PeoplePlayProtocolCodec.decodeClientMessage(it).getOrThrow() }
        assertEquals(1, commands.count { it is com.example.othello.network.peopleplay.TimeoutSelf })
        assertEquals(0L, holder.state.value.remainingMillis)
    }

    @Test
    fun monotonicClockPausesOnOtherTurnAndRestoresElapsedPendingTimeOnReject() {
        var nanos = 0L
        val clock = PeoplePlayMonotonicClock { nanos }
        clock.start(initialMillis = 1_000, active = true)
        nanos += 200_000_000
        assertEquals(800, clock.remainingMillis())

        clock.beginPending(nextTurnIsSelf = false)
        nanos += 100_000_000
        assertEquals(800, clock.remainingMillis())
        clock.rejectPending(acceptedTurnIsSelf = true)
        assertEquals(700, clock.remainingMillis())

        clock.beginPending(nextTurnIsSelf = true)
        nanos += 100_000_000
        assertEquals(600, clock.remainingMillis())
        clock.acceptPending(active = true)
        clock.stop()
        nanos += 2_000_000_000
        assertEquals(600, clock.remainingMillis())
    }

    private fun holder(
        scope: CoroutineScope,
        repository: FakePeoplePlayRepository,
        persistence: LocalGameRecordPersistenceCoordinator,
    ) = PeoplePlayRoomStateHolder(scope, repository, { "fixture-token" }, persistence, {}, {})

    private fun persistence(scope: CoroutineScope) = LocalGameRecordPersistenceCoordinator(MemoryRecordStore(), scope)

    private fun <T> withRoomScope(block: (CoroutineScope) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { block(scope) } finally { scope.cancel() }
    }

    private fun participant(id: String) = PeoplePlayParticipantSummary(id, "Player $id", com.example.othello.network.peopleplay.AvatarId.BOY)

    private fun waitingSnapshot(
        seats: PeoplePlaySeats = PeoplePlaySeats(null, null),
        spectatorCount: Int = 0,
    ) = PeoplePlayRoomSnapshot(
        roomId = "room-fixture", phase = RoomPhase.WAITING, timeControl = TimeControl.TEN_MINUTES,
        seats = seats, players = null, wirePly = 0, board = GameState().board.toWireBoard(), move = null,
        nextTurn = PlayerColor.BLACK, terminalCandidate = false, resultCheckPly = null,
        spectatorCount = spectatorCount, spectatorAvatarPreview = emptyList(),
    )

    private fun playingSnapshot(
        seatA: PeoplePlayParticipantSummary = participant("member-black"),
        seatB: PeoplePlayParticipantSummary = participant("member-white"),
        selfWhite: Boolean = false,
        wirePly: Int = 0,
        board: List<Int> = GameState().board.toWireBoard(),
        move: com.example.othello.network.peopleplay.PeoplePlayMove? = null,
        nextTurn: PlayerColor? = PlayerColor.BLACK,
        spectatorCount: Int = 0,
    ) = PeoplePlayRoomSnapshot(
        roomId = "room-fixture", phase = RoomPhase.PLAYING, timeControl = TimeControl.TWENTY_MINUTES,
        seats = PeoplePlaySeats(seatA, seatB),
        players = if (selfWhite) PeoplePlayPlayers(seatA, seatB) else PeoplePlayPlayers(seatA, seatB),
        wirePly = wirePly, board = board, move = move, nextTurn = nextTurn,
        terminalCandidate = nextTurn == null, resultCheckPly = null,
        spectatorCount = spectatorCount, spectatorAvatarPreview = emptyList(),
    )

    private class FakePeoplePlayRepository(private val memberId: String) : PeoplePlayRepository {
        lateinit var listener: PeoplePlaySocketListener
        val socket = FakeSocket()
        override suspend fun listRooms(accessToken: String) = emptyList<PeoplePlayLobbyEntry>()
        override fun createRoom(accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket {
            this.listener = listener
            listener.onOpen(memberId)
            socket.listener = listener
            return socket
        }
        override fun joinRoom(roomId: String, accessToken: String, listener: PeoplePlaySocketListener): PeoplePlaySocket =
            createRoom(accessToken, listener)
        fun send(message: com.example.othello.network.peopleplay.PeoplePlayServerMessage) =
            listener.onText(PeoplePlayProtocolCodec.encodeServerMessage(message))
        fun sendSnapshot(snapshot: PeoplePlayRoomSnapshot) = send(snapshot)
    }

    private fun PeoplePlayRoomStateHolder.sendSnapshotForTest(snapshot: PeoplePlayRoomSnapshot) =
        onText(PeoplePlayProtocolCodec.encodeServerMessage(snapshot))

    private fun PeoplePlayRoomStateHolder.sendServerMessageForTest(message: com.example.othello.network.peopleplay.PeoplePlayServerMessage) =
        onText(PeoplePlayProtocolCodec.encodeServerMessage(message))

    private class FakeSocket : PeoplePlaySocket {
        lateinit var listener: PeoplePlaySocketListener
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close() = Unit
    }

    private class MemoryRecordStore : LocalGameRecordStore {
        val records = mutableListOf<LocalGameRecord>()
        override suspend fun list(limit: Int) = records.take(limit)
        override suspend fun save(record: LocalGameRecord) { records.removeAll { it.localId == record.localId }; records += record }
        override suspend fun delete(localId: String) { records.removeAll { it.localId == localId } }
    }
}
