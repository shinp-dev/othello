package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.Position
import com.example.othello.network.MatchTransport
import com.example.othello.network.FinishCommand
import com.example.othello.network.MoveCommand
import com.example.othello.network.TransportState
import com.example.othello.network.ClockSnapshot
import com.example.othello.records.FinishReason
import com.example.othello.records.MatchResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeMatchTransport : MatchTransport {
    var peer: FakeMatchTransport? = null
    val sent = mutableListOf<MoveCommand>()
    val sentFinishes = mutableListOf<FinishCommand>()
    private val listeners = mutableSetOf<(MoveCommand) -> Unit>()
    private val finishListeners = mutableSetOf<(FinishCommand) -> Unit>()
    private var state = TransportState.OPEN
    var failSend = false
    var closeCalls = 0

    override suspend fun send(command: MoveCommand) {
        if (failSend) error("send failed")
        sent += command
        peer?.deliver(command)
    }

    override suspend fun sendFinish(command: FinishCommand) {
        if (failSend) error("send failed")
        sentFinishes += command
        peer?.deliverFinish(command)
    }

    fun deliver(command: MoveCommand) = listeners.toList().forEach { it(command) }
    fun deliverFinish(command: FinishCommand) = finishListeners.toList().forEach { it(command) }
    override fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable { listeners += onCommand; return AutoCloseable { listeners -= onCommand } }
    override fun observeFinish(onCommand: (FinishCommand) -> Unit): AutoCloseable { finishListeners += onCommand; return AutoCloseable { finishListeners -= onCommand } }
    override fun observeState(onState: (TransportState) -> Unit): AutoCloseable { onState(state); return AutoCloseable { } }
    override fun close() { closeCalls++ }
}

private class FakeOnlineRepository : OnlineMatchRepository {
    var submitted: MatchSubmission? = null
    val submissions = mutableListOf<MatchSubmission>()
    val serverStatuses = mutableListOf("CONFIRMED")
    var startState = MatchStartAck("CREATED", localAcked = true, bothAcked = true)
    var ackCalls = 0
    var submitCalls = 0
    override suspend fun ackMatchStarted(matchId: String): MatchStartAck { ackCalls++; return startState }
    override suspend fun getMatchStartState(matchId: String) = startState
    override suspend fun abandonMatch(matchId: String) = true
    override suspend fun submitMatchResult(submission: MatchSubmission): MatchFinishResult {
        submitCalls++
        submitted = submission
        submissions += submission
        val status = if (serverStatuses.size > 1) serverStatuses.removeAt(0) else serverStatuses.single()
        return MatchFinishResult(status)
    }
}

class OnlineMatchControllerTest {
    @Test
    fun oneSidedStartAckDoesNotEnterPlayingAndDoubleOpenAcksOnce() = runBlocking {
        val repository = FakeOnlineRepository().apply {
            startState = MatchStartAck("CREATED", localAcked = true, bothAcked = false)
        }
        val controller = OnlineMatchController(
            "match",
            Disc.BLACK,
            FakeMatchTransport(),
            repository,
            startConfirmationAttempts = 1,
            startConfirmationDelayMillis = 0,
        )

        assertFalse(controller.onDataChannelOpen())
        assertEquals(MatchStatus.P2P_CONNECTED, controller.viewState.matchState.status)
        repository.startState = MatchStartAck("CREATED", localAcked = true, bothAcked = true)
        assertTrue(controller.onDataChannelOpen())
        assertTrue(controller.onDataChannelOpen())
        assertEquals(1, repository.ackCalls)
        assertEquals(MatchStatus.PLAYING, controller.viewState.matchState.status)
    }

    @Test
    fun failedSendDoesNotAdvanceOnlyTheLocalBoard() = runBlocking {
        val transport = FakeMatchTransport().apply { failSend = true }
        val controller = OnlineMatchController("match", Disc.BLACK, transport, FakeOnlineRepository())
        controller.onDataChannelOpen()

        assertFalse(controller.play(Position(2, 3)))
        assertEquals(GameState(), controller.viewState.game)
        assertEquals(0, controller.viewState.moves.size)
        assertEquals(0, controller.viewState.commandCountSent)
    }

    @Test
    fun zeroPlyResignationNotifiesPeerAndBothSubmitTheResigningSideAsLoser() = runBlocking {
        val blackTransport = FakeMatchTransport()
        val whiteTransport = FakeMatchTransport()
        blackTransport.peer = whiteTransport
        whiteTransport.peer = blackTransport
        val blackRepository = FakeOnlineRepository()
        val whiteRepository = FakeOnlineRepository()
        val black = OnlineMatchController("resign", Disc.BLACK, blackTransport, blackRepository)
        val white = OnlineMatchController("resign", Disc.WHITE, whiteTransport, whiteRepository)
        black.onDataChannelOpen(); white.onDataChannelOpen()

        black.resign()

        assertEquals(MatchResult.WHITE_WIN, blackRepository.submitted?.result)
        assertEquals(MatchResult.WHITE_WIN, whiteRepository.submitted?.result)
        assertEquals(FinishReason.RESIGNATION, blackRepository.submitted?.finishReason)
        assertEquals(FinishReason.RESIGNATION, whiteRepository.submitted?.finishReason)
        assertEquals("", blackRepository.submitted?.canonicalMoves)
        assertEquals("", whiteRepository.submitted?.canonicalMoves)
        assertEquals(1, blackTransport.sentFinishes.size)
    }

    @Test
    fun closeRemovesCallbacksAndIsIdempotent() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository()
        val controller = OnlineMatchController("match", Disc.BLACK, transport, repository)
        controller.onDataChannelOpen()
        val before = controller.viewState

        controller.close()
        controller.close()
        transport.deliver(MoveCommand("match", 0, Position(2, 3), "old", before.game.stateHash()))

        assertEquals(before, controller.viewState)
        assertEquals(1, transport.closeCalls)
        assertNull(repository.submitted)
    }

    @Test
    fun pendingResultRetryReusesSubmissionAndClosesOnlyAfterConfirmation() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository().apply {
            serverStatuses.clear()
            serverStatuses += listOf("PENDING_RESULT", "CONFIRMED")
        }
        val controller = OnlineMatchController("pending", Disc.BLACK, transport, repository)
        controller.onDataChannelOpen()

        assertEquals("PENDING_RESULT", controller.resign()?.serverStatus)
        assertEquals(MatchStatus.PENDING_RESULT, controller.viewState.matchState.status)
        assertEquals(0, transport.closeCalls)
        assertEquals("CONFIRMED", controller.retryFinish()?.serverStatus)

        assertEquals(2, repository.submitCalls)
        assertEquals(repository.submissions.first(), repository.submissions.last())
        assertEquals(MatchStatus.CONFIRMED, controller.viewState.matchState.status)
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun twoControllersStartAndExchangeMoveWithIdempotentDuplicate() = runBlocking {
        val blackTransport = FakeMatchTransport()
        val whiteTransport = FakeMatchTransport()
        blackTransport.peer = whiteTransport
        whiteTransport.peer = blackTransport
        val black = OnlineMatchController("match", Disc.BLACK, blackTransport, FakeOnlineRepository())
        val white = OnlineMatchController("match", Disc.WHITE, whiteTransport, FakeOnlineRepository())
        assertTrue(black.onDataChannelOpen())
        assertTrue(white.onDataChannelOpen())

        assertTrue(black.play(Position(2, 3)))
        assertEquals(1, white.viewState.game.ply)
        whiteTransport.deliver(blackTransport.sent.single())
        assertEquals(1, white.viewState.game.ply)
        assertEquals(ProtocolState.PLAYING, ProtocolState.from(black.viewState.matchState.status))
    }

    @Test
    fun moveReceivedDuringMutualStartAckIsAppliedAfterLocalStartConfirmation() = runBlocking {
        val blackTransport = FakeMatchTransport()
        val whiteTransport = FakeMatchTransport()
        blackTransport.peer = whiteTransport
        whiteTransport.peer = blackTransport
        val black = OnlineMatchController("start-race", Disc.BLACK, blackTransport, FakeOnlineRepository())
        val white = OnlineMatchController("start-race", Disc.WHITE, whiteTransport, FakeOnlineRepository())

        assertTrue(black.onDataChannelOpen())
        assertTrue(black.play(Position(2, 3)))
        assertEquals(0, white.viewState.game.ply)

        assertTrue(white.onDataChannelOpen())
        assertEquals(1, white.viewState.game.ply)
        assertEquals(1, white.viewState.commandCountReceived)
    }

    @Test
    fun localMonotonicClockTimeoutSendsFinishAndSubmitsOnce() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository()
        val controller = OnlineMatchController(
            "timeout",
            Disc.BLACK,
            transport,
            repository,
            timeControlMillis = 25,
        )

        assertEquals(25, controller.viewState.blackRemainingMillis)
        assertEquals(25, controller.viewState.whiteRemainingMillis)
        assertTrue(controller.onDataChannelOpen())
        delay(100)

        assertEquals(FinishReason.TIMEOUT, repository.submitted?.finishReason)
        assertEquals(MatchResult.WHITE_WIN, repository.submitted?.result)
        assertEquals(1, repository.submitCalls)
        assertEquals(1, transport.sentFinishes.size)
        assertEquals(0, controller.viewState.blackRemainingMillis)
    }

    @Test
    fun remoteClockSnapshotCannotReduceTheLocalPlayersClock() = runBlocking {
        var now = 0L
        val transport = FakeMatchTransport()
        val controller = OnlineMatchController(
            "clock-authority",
            Disc.WHITE,
            transport,
            FakeOnlineRepository(),
            timeControlMillis = 5_000,
            monotonicNowMillis = { now },
        )
        controller.onDataChannelOpen()
        now = 1_000

        transport.deliver(
            MoveCommand(
                matchId = "clock-authority",
                ply = 0,
                move = Position(2, 3),
                commandId = "remote-move",
                previousStateHash = GameState().stateHash(),
                clockSnapshot = ClockSnapshot(4_000, 0),
            ),
        )

        assertEquals(4_000, controller.viewState.blackRemainingMillis)
        assertEquals(5_000, controller.viewState.whiteRemainingMillis)
    }

    @Test
    fun fullGameUsesOnlyRealMovesAndCanonicalPassesAreLocal() = runBlocking {
        val blackTransport = FakeMatchTransport()
        val whiteTransport = FakeMatchTransport()
        blackTransport.peer = whiteTransport
        whiteTransport.peer = blackTransport
        val blackRepository = FakeOnlineRepository()
        val whiteRepository = FakeOnlineRepository()
        val black = OnlineMatchController("full", Disc.BLACK, blackTransport, blackRepository)
        val white = OnlineMatchController("full", Disc.WHITE, whiteTransport, whiteRepository)
        black.onDataChannelOpen(); white.onDataChannelOpen()

        var guard = 0
        while (black.viewState.game.status is GameStatus.InProgress && guard++ < 120) {
            val controller = if (black.viewState.game.currentPlayer == Disc.BLACK) black else white
            val move = controller.viewState.game.legalMoves.firstOrNull() ?: break
            controller.play(move)
        }
        assertTrue(guard < 120)
        assertEquals(black.viewState.game, white.viewState.game)
        assertTrue(black.viewState.moves.size <= 120)
        assertEquals(black.viewState.moves, white.viewState.moves)
        assertEquals(MatchStatus.CONFIRMED, black.viewState.matchState.status)
        assertEquals(MatchStatus.CONFIRMED, white.viewState.matchState.status)
        assertEquals(1, blackRepository.submitCalls)
        assertEquals(1, whiteRepository.submitCalls)
        assertEquals(1, blackTransport.closeCalls)
        assertEquals(1, whiteTransport.closeCalls)
    }

    private enum class ProtocolState { PLAYING; companion object { fun from(status: MatchStatus) = when (status) { MatchStatus.PLAYING -> PLAYING; else -> error("not playing") } } }
}
