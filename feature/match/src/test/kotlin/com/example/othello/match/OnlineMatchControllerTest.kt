package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.CanonicalMoves
import com.example.othello.game.TurnResolver
import com.example.othello.network.ClockSnapshot
import com.example.othello.network.FinishCommand
import com.example.othello.network.MatchTransport
import com.example.othello.network.MoveAck
import com.example.othello.network.MoveCommand
import com.example.othello.network.SyncMessage
import com.example.othello.network.SyncMessageType
import com.example.othello.network.TransportState
import com.example.othello.records.FinishReason
import com.example.othello.records.MatchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeMatchTransport : MatchTransport {
    var peer: FakeMatchTransport? = null
    val sent = mutableListOf<MoveCommand>()
    val sentFinishes = mutableListOf<FinishCommand>()
    val sentMoveAcks = mutableListOf<MoveAck>()
    val sentSyncMessages = mutableListOf<SyncMessage>()
    private val listeners = mutableSetOf<(MoveCommand) -> Unit>()
    private val finishListeners = mutableSetOf<(FinishCommand) -> Unit>()
    private val moveAckListeners = mutableSetOf<(MoveAck) -> Unit>()
    private val syncListeners = mutableSetOf<(SyncMessage) -> Unit>()
    private val stateListeners = mutableSetOf<(TransportState) -> Unit>()
    private var state = TransportState.OPEN
    var failSend = false
    var dropCommand: (MoveCommand) -> Boolean = { false }
    var dropAck: (MoveAck) -> Boolean = { false }
    var closeCalls = 0

    override suspend fun send(command: MoveCommand) {
        if (failSend) error("send failed")
        sent += command
        if (!dropCommand(command)) peer?.deliver(command)
    }

    override suspend fun sendFinish(command: FinishCommand) {
        if (failSend) error("send failed")
        sentFinishes += command
        peer?.deliverFinish(command)
    }

    override suspend fun sendMoveAck(ack: MoveAck) {
        if (failSend) error("send failed")
        sentMoveAcks += ack
        if (!dropAck(ack)) peer?.deliverMoveAck(ack)
    }

    override suspend fun sendSync(message: SyncMessage) {
        if (failSend) error("send failed")
        sentSyncMessages += message
        peer?.deliverSync(message)
    }

    fun deliver(command: MoveCommand) = listeners.toList().forEach { it(command) }
    fun deliverFinish(command: FinishCommand) = finishListeners.toList().forEach { it(command) }
    fun deliverMoveAck(ack: MoveAck) = moveAckListeners.toList().forEach { it(ack) }
    fun deliverSync(message: SyncMessage) = syncListeners.toList().forEach { it(message) }
    fun emitState(next: TransportState) {
        state = next
        stateListeners.toList().forEach { it(state) }
    }
    fun fail() = emitState(TransportState.FAILED)
    fun disconnect() = emitState(TransportState.DISCONNECTED)
    fun reconnect() = emitState(TransportState.OPEN)

    override fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable { listeners += onCommand; return AutoCloseable { listeners -= onCommand } }
    override fun observeFinish(onCommand: (FinishCommand) -> Unit): AutoCloseable { finishListeners += onCommand; return AutoCloseable { finishListeners -= onCommand } }
    override fun observeMoveAck(onAck: (MoveAck) -> Unit): AutoCloseable { moveAckListeners += onAck; return AutoCloseable { moveAckListeners -= onAck } }
    override fun observeSync(onMessage: (SyncMessage) -> Unit): AutoCloseable { syncListeners += onMessage; return AutoCloseable { syncListeners -= onMessage } }
    override fun observeState(onState: (TransportState) -> Unit): AutoCloseable {
        stateListeners += onState
        onState(state)
        return AutoCloseable { stateListeners -= onState }
    }
    override fun close() { closeCalls++ }
}

private class FakeOnlineRepository : OnlineMatchRepository {
    var submitted: MatchSubmission? = null
    val submissions = mutableListOf<MatchSubmission>()
    val attemptedSubmissions = mutableListOf<MatchSubmission>()
    val serverStatuses = mutableListOf("CONFIRMED")
    var startState = MatchStartAck("CREATED", localAcked = true, bothAcked = true)
    val startStateResponses = ArrayDeque<MatchStartAck>()
    var ackCalls = 0
    var submitCalls = 0
    var abandonCalls = 0
    var resumeCalls = 0
    var reconcileCalls = 0
    var submitDelayMillis = 0L
    var submitFailuresRemaining = 0
    var resumeResult = MatchFinishResult("ACTIVE")
    var reconcileResult = MatchFinishResult("ACTIVE")
    override suspend fun ackMatchStarted(matchId: String): MatchStartAck {
        ackCalls++
        return startStateResponses.removeFirstOrNull() ?: startState
    }
    override suspend fun getMatchStartState(matchId: String) =
        startStateResponses.removeFirstOrNull() ?: startState
    override suspend fun abandonMatch(matchId: String): Boolean { abandonCalls++; return true }
    override suspend fun submitMatchResult(submission: MatchSubmission): MatchFinishResult {
        attemptedSubmissions += submission
        if (submitFailuresRemaining > 0) {
            submitFailuresRemaining--
            error("submit failed")
        }
        if (submitDelayMillis > 0) delay(submitDelayMillis)
        submitCalls++
        submitted = submission
        submissions += submission
        val status = if (serverStatuses.size > 1) serverStatuses.removeAt(0) else serverStatuses.single()
        return MatchFinishResult(status)
    }
    override suspend fun resumeMatch(matchId: String): MatchFinishResult { resumeCalls++; return resumeResult }
    override suspend fun reconcileMatch(matchId: String): MatchFinishResult { reconcileCalls++; return reconcileResult }
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
    fun failedInitialSendKeepsTheDurableMovePendingForBoundedRetry() = runBlocking {
        val transport = FakeMatchTransport().apply { failSend = true }
        val controller = OnlineMatchController(
            "match",
            Disc.BLACK,
            transport,
            FakeOnlineRepository(),
            deliveryAckTimeoutMillis = 10_000,
        )
        controller.onDataChannelOpen()

        assertTrue(controller.play(Position(2, 3)))
        assertEquals(1, controller.viewState.game.ply)
        assertEquals(listOf(Position(2, 3)), controller.viewState.moves)
        assertEquals(1, controller.viewState.commandCountSent)
        assertEquals(MatchStatus.MOVE_CONFIRMING, controller.viewState.matchState.status)
        assertTrue(controller.viewState.awaitingMoveAck)
        controller.close()
    }

    @Test
    fun failedDurableMoveCheckpointBlocksNetworkUntilExplicitRetry() = runBlocking {
        var storageAvailable = false
        val transport = FakeMatchTransport()
        val controller = OnlineMatchController(
            "durable-move",
            Disc.BLACK,
            transport,
            FakeOnlineRepository(),
            deliveryAckTimeoutMillis = 10_000,
            durableCheckpoint = { storageAvailable },
        )
        controller.onDataChannelOpen()

        assertTrue(controller.play(Position(2, 3)))
        assertTrue(transport.sent.isEmpty())
        assertEquals(MatchStatus.MOVE_CONFIRMING, controller.viewState.matchState.status)
        assertTrue(controller.viewState.error?.contains("保存") == true)

        storageAvailable = true
        assertTrue(controller.retryPendingMove())
        assertEquals(1, transport.sent.size)
        assertEquals(transport.sent.single().commandId, transport.sent.first().commandId)
        controller.close()
    }

    @Test
    fun remoteMoveIsNotAcknowledgedUntilItsCheckpointIsDurable() = runBlocking {
        var storageAvailable = false
        val transport = FakeMatchTransport()
        val controller = OnlineMatchController(
            "durable-remote-move",
            Disc.WHITE,
            transport,
            FakeOnlineRepository(),
            durableCheckpoint = { storageAvailable },
        )
        controller.onDataChannelOpen()
        val command = MoveCommand(
            "durable-remote-move",
            0,
            Position(2, 3),
            "move-1",
            GameState().stateHash(),
        )

        transport.deliver(command)
        assertEquals(1, controller.viewState.game.ply)
        assertTrue(transport.sentMoveAcks.isEmpty())

        storageAvailable = true
        transport.deliver(command)
        assertEquals(1, controller.viewState.game.ply)
        assertEquals("move-1", transport.sentMoveAcks.single().commandId)
        controller.close()
    }

    @Test
    fun failedDurableResultCheckpointBlocksPeerAndServerAndKeepsRequestId() = runBlocking {
        var storageAvailable = false
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository()
        val controller = OnlineMatchController(
            "durable-result",
            Disc.BLACK,
            transport,
            repository,
            durableCheckpoint = { storageAvailable },
        )
        controller.onDataChannelOpen()

        assertNull(controller.resign())
        val requestId = controller.viewState.pendingResultRequestId
        assertTrue(requestId?.isNotBlank() == true)
        assertTrue(transport.sentFinishes.isEmpty())
        assertEquals(0, repository.submitCalls)

        storageAvailable = true
        assertEquals("CONFIRMED", controller.retryFinish()?.serverStatus)
        assertEquals(requestId, repository.submitted?.requestId)
        assertEquals(1, repository.submitCalls)
    }

    @Test
    fun pendingResultCannotBeReplacedByLateSyncFailure() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository().apply {
            serverStatuses.clear()
            serverStatuses += "RESULT_PENDING"
        }
        val controller = OnlineMatchController("terminal-sync", Disc.BLACK, transport, repository)
        controller.onDataChannelOpen()
        controller.resign()
        assertEquals(MatchStatus.PENDING_RESULT, controller.viewState.matchState.status)
        transport.failSend = true

        transport.deliverSync(
            SyncMessage("terminal-sync", "late", SyncMessageType.REQUEST, 0, GameState().stateHash()),
        )

        assertEquals(MatchStatus.PENDING_RESULT, controller.viewState.matchState.status)
        assertEquals(1, repository.submitCalls)
        assertTrue(transport.sentSyncMessages.isEmpty())
        controller.close()
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
    fun peerFinishNoticeFailureStillSubmitsAuthoritativeServerResult() = runBlocking {
        val transport = FakeMatchTransport().apply { failSend = true }
        val repository = FakeOnlineRepository()
        val controller = OnlineMatchController("finish-notice-failure", Disc.BLACK, transport, repository)
        controller.onDataChannelOpen()

        val result = controller.resign()

        assertEquals("CONFIRMED", result?.serverStatus)
        assertEquals(1, repository.submitCalls)
        assertEquals(FinishReason.RESIGNATION, repository.submitted?.finishReason)
        assertEquals(Disc.BLACK, repository.submitted?.loserDisc)
        assertEquals(MatchResult.WHITE_WIN, repository.submitted?.result)
        assertTrue(transport.sentFinishes.isEmpty())
        assertEquals(MatchStatus.CONFIRMED, controller.viewState.matchState.status)
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
    fun restoredResultOutboxRetriesBeforeAnyDataChannelAndKeepsRequestIdentity() = runBlocking {
        val repository = FakeOnlineRepository()
        val controller = OnlineMatchController(
            matchId = "restored-outbox",
            localDisc = Disc.BLACK,
            transport = FakeMatchTransport(),
            repository = repository,
            initialPendingFinishReason = FinishReason.RESIGNATION,
            initialPendingLoserDisc = Disc.BLACK,
            initialPendingResultRequestId = "stable-request-id",
        )

        assertEquals(MatchStatus.FINISHING, controller.viewState.matchState.status)
        assertEquals("stable-request-id", controller.viewState.pendingResultRequestId)
        assertEquals("CONFIRMED", controller.retryFinish()?.serverStatus)

        assertEquals(0, repository.ackCalls)
        assertEquals("stable-request-id", repository.submitted?.requestId)
        assertEquals(FinishReason.RESIGNATION, repository.submitted?.finishReason)
    }

    @Test
    fun serverActivePendingAndTerminalStatusesMapToProtocolStates() = runBlocking {
        val mappings = listOf(
            "ACTIVE" to MatchStatus.PLAYING,
            "RECONNECTING" to MatchStatus.RECONNECTING,
            "RESULT_PENDING" to MatchStatus.PENDING_RESULT,
            "PENDING_RESULT" to MatchStatus.PENDING_RESULT,
            "CONFIRMED" to MatchStatus.CONFIRMED,
            "FORFEIT" to MatchStatus.FORFEIT,
            "EXPIRED" to MatchStatus.EXPIRED,
            "ABANDONED" to MatchStatus.ABANDONED,
            "DISPUTED" to MatchStatus.DISPUTED,
        )
        val terminal = setOf(
            MatchStatus.CONFIRMED,
            MatchStatus.FORFEIT,
            MatchStatus.EXPIRED,
            MatchStatus.ABANDONED,
            MatchStatus.DISPUTED,
        )

        mappings.forEach { (serverStatus, expected) ->
            val transport = FakeMatchTransport()
            val repository = FakeOnlineRepository().apply { reconcileResult = MatchFinishResult(serverStatus) }
            val controller = OnlineMatchController("status-$serverStatus", Disc.BLACK, transport, repository)
            controller.onDataChannelOpen()

            assertEquals(serverStatus, controller.reconcileServerState().serverStatus)
            assertEquals(expected, controller.viewState.matchState.status)
            assertEquals(if (expected in terminal) 1 else 0, transport.closeCalls)
            controller.close()
        }
    }

    @Test
    fun unexpectedTransportFailureDuringPlayReportsOpponentAndEntersReconnectGrace() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository().apply {
            serverStatuses.clear()
            serverStatuses += "RECONNECTING"
            submitDelayMillis = 10
        }
        val controller = OnlineMatchController(
            "lost-channel",
            Disc.BLACK,
            transport,
            repository,
            reconnectGraceMillis = 1_000,
        )
        controller.onDataChannelOpen()

        transport.fail()

        awaitCondition { repository.submitCalls == 1 }

        assertEquals(1, repository.submitCalls)
        assertEquals(FinishReason.DISCONNECT, repository.submitted?.finishReason)
        assertEquals(Disc.WHITE, repository.submitted?.loserDisc)
        assertEquals(MatchResult.BLACK_WIN, repository.submitted?.result)
        assertEquals(MatchStatus.RECONNECTING, controller.viewState.matchState.status)
        assertTrue(transport.sentFinishes.isEmpty())
        controller.close()
    }

    @Test
    fun transportFailureBeforeStartAbandonsReservation() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository()
        val controller = OnlineMatchController("pre-start", Disc.BLACK, transport, repository)

        transport.fail()

        assertEquals(1, repository.abandonCalls)
        assertEquals(0, repository.submitCalls)
        assertEquals(MatchStatus.DISCONNECTED, controller.viewState.matchState.status)
    }

    @Test
    fun twoControllersExchangeMoveAndPeerAckReturnsSenderToPlaying() = runBlocking {
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
        assertEquals(1, whiteTransport.sentMoveAcks.size)
        assertEquals(MatchStatus.PLAYING, black.viewState.matchState.status)
        assertFalse(black.viewState.awaitingMoveAck)
        whiteTransport.deliver(blackTransport.sent.single())
        assertEquals(1, white.viewState.game.ply)
        assertEquals(2, whiteTransport.sentMoveAcks.size)
        assertEquals(ProtocolState.PLAYING, ProtocolState.from(black.viewState.matchState.status))
    }

    @Test
    fun lostAckRetriesTheSameCommandAndReceiverAppliesItOnlyOnce() = runBlocking {
        val blackTransport = FakeMatchTransport()
        val whiteTransport = FakeMatchTransport()
        blackTransport.peer = whiteTransport
        whiteTransport.peer = blackTransport
        var firstAck = true
        whiteTransport.dropAck = {
            if (firstAck) {
                firstAck = false
                true
            } else false
        }
        val black = OnlineMatchController(
            "lost-ack",
            Disc.BLACK,
            blackTransport,
            FakeOnlineRepository(),
            deliveryRetryAttempts = 2,
            deliveryAckTimeoutMillis = 5,
            synchronizationTimeoutMillis = 100,
        )
        val white = OnlineMatchController("lost-ack", Disc.WHITE, whiteTransport, FakeOnlineRepository())
        black.onDataChannelOpen()
        white.onDataChannelOpen()

        assertTrue(black.play(Position(2, 3)))
        awaitCondition { black.viewState.matchState.status == MatchStatus.PLAYING && blackTransport.sent.size == 2 }

        assertEquals(blackTransport.sent.first(), blackTransport.sent.last())
        assertEquals(1, white.viewState.game.ply)
        assertEquals(1, white.viewState.commandCountReceived)
        assertEquals(2, whiteTransport.sentMoveAcks.size)
        assertFalse(black.viewState.awaitingMoveAck)
        black.close()
        white.close()
    }

    @Test
    fun duplicateAckAfterConfirmationIsIdempotent() = runBlocking {
        val blackTransport = FakeMatchTransport()
        val whiteTransport = FakeMatchTransport()
        blackTransport.peer = whiteTransport
        whiteTransport.peer = blackTransport
        val black = OnlineMatchController("duplicate-ack", Disc.BLACK, blackTransport, FakeOnlineRepository())
        val white = OnlineMatchController("duplicate-ack", Disc.WHITE, whiteTransport, FakeOnlineRepository())
        black.onDataChannelOpen()
        white.onDataChannelOpen()
        assertTrue(black.play(Position(2, 3)))
        awaitCondition { black.viewState.matchState.status == MatchStatus.PLAYING }
        val confirmed = black.viewState

        blackTransport.deliverMoveAck(whiteTransport.sentMoveAcks.single())

        assertEquals(confirmed, black.viewState)
        assertFalse(black.viewState.awaitingMoveAck)
        black.close()
        white.close()
    }

    @Test
    fun sequenceOrHashGapRequestsSynchronization() = runBlocking {
        val initial = GameState()
        val invalidCommands = listOf(
            MoveCommand("gap", 1, Position(2, 3), "sequence-gap", initial.stateHash()),
            MoveCommand("gap", 0, Position(2, 3), "hash-gap", "not-the-current-hash"),
        )

        invalidCommands.forEach { invalid ->
            val transport = FakeMatchTransport()
            val controller = OnlineMatchController(
                "gap",
                Disc.WHITE,
                transport,
                FakeOnlineRepository(),
                synchronizationTimeoutMillis = 1_000,
            )
            controller.onDataChannelOpen()

            transport.deliver(invalid)

            assertEquals(MatchStatus.SYNCHRONIZING, controller.viewState.matchState.status)
            assertEquals(SyncMessageType.REQUEST, transport.sentSyncMessages.single().type)
            assertEquals(initial.ply, transport.sentSyncMessages.single().ply)
            controller.close()
        }
    }

    @Test
    fun validPrefixSnapshotConvergesToPeerStateAndReturnsToPlaying() = runBlocking {
        val transport = FakeMatchTransport()
        val controller = OnlineMatchController(
            "prefix-sync",
            Disc.WHITE,
            transport,
            FakeOnlineRepository(),
            synchronizationTimeoutMillis = 1_000,
        )
        controller.onDataChannelOpen()
        transport.deliver(
            MoveCommand(
                matchId = "prefix-sync",
                ply = 1,
                move = Position(2, 2),
                commandId = "gap",
                previousStateHash = GameState().stateHash(),
            ),
        )
        assertEquals(MatchStatus.SYNCHRONIZING, controller.viewState.matchState.status)
        val afterOpening = (GameState().play(Position(2, 3)) as MoveOutcome.Played).state

        transport.deliverSync(
            SyncMessage(
                matchId = "prefix-sync",
                requestId = transport.sentSyncMessages.single().requestId,
                type = SyncMessageType.SNAPSHOT,
                ply = afterOpening.ply,
                stateHash = afterOpening.stateHash(),
                transcript = "d3",
            ),
        )

        assertEquals(afterOpening, controller.viewState.game)
        assertEquals(listOf(Position(2, 3)), controller.viewState.moves)
        assertEquals(MatchStatus.PLAYING, controller.viewState.matchState.status)
        assertFalse(controller.viewState.awaitingMoveAck)
        controller.close()
    }

    @Test
    fun unsolicitedOrMultiTurnSnapshotCannotRewriteTheLocalGame() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository().apply {
            serverStatuses.clear()
            serverStatuses += "RECONNECTING"
        }
        val controller = OnlineMatchController(
            "guarded-sync",
            Disc.WHITE,
            transport,
            repository,
            synchronizationTimeoutMillis = 1_000,
        )
        controller.onDataChannelOpen()
        val first = TurnResolver.resolveForcedPasses(
            (GameState().play(Position(2, 3)) as MoveOutcome.Played).state,
        )

        transport.deliverSync(
            SyncMessage(
                "guarded-sync", "unsolicited", SyncMessageType.SNAPSHOT,
                first.state.ply, first.state.stateHash(), "d3",
            ),
        )
        assertEquals(0, controller.viewState.game.ply)

        transport.deliver(
            MoveCommand("guarded-sync", 3, Position(0, 0), "gap", GameState().stateHash()),
        )
        val requestId = transport.sentSyncMessages.single { it.type == SyncMessageType.REQUEST }.requestId
        val secondMove = first.state.legalMoves.first()
        val second = TurnResolver.resolveForcedPasses(
            (first.state.play(secondMove) as MoveOutcome.Played).state,
        )
        val forgedMoves = listOf(Position(2, 3)) + List(first.forcedPasses) { null } +
            secondMove + List(second.forcedPasses) { null }
        transport.deliverSync(
            SyncMessage(
                "guarded-sync", requestId, SyncMessageType.SNAPSHOT,
                second.state.ply, second.state.stateHash(), CanonicalMoves.encode(forgedMoves),
            ),
        )

        assertEquals(0, controller.viewState.game.ply)
        assertEquals(MatchStatus.RECONNECTING, controller.viewState.matchState.status)
        controller.close()
    }

    @Test
    fun lostMoveCommandConvergesThroughOneTurnTranscriptSync() = runBlocking {
        val blackTransport = FakeMatchTransport().apply { dropCommand = { true } }
        val whiteTransport = FakeMatchTransport()
        blackTransport.peer = whiteTransport
        whiteTransport.peer = blackTransport
        val blackRepository = FakeOnlineRepository()
        val black = OnlineMatchController(
            "lost-command-sync",
            Disc.BLACK,
            blackTransport,
            blackRepository,
            deliveryRetryAttempts = 1,
            deliveryAckTimeoutMillis = 5,
            synchronizationTimeoutMillis = 100,
        )
        val white = OnlineMatchController(
            "lost-command-sync",
            Disc.WHITE,
            whiteTransport,
            FakeOnlineRepository(),
            synchronizationTimeoutMillis = 100,
        )
        black.onDataChannelOpen()
        white.onDataChannelOpen()

        assertTrue(black.play(Position(2, 3)))
        awaitCondition {
            black.viewState.matchState.status == MatchStatus.PLAYING &&
                white.viewState.game.ply == 1
        }

        assertEquals(black.viewState.game, white.viewState.game)
        assertFalse(black.viewState.awaitingMoveAck)
        assertEquals(0, blackRepository.submitCalls)
        black.close()
        white.close()
    }

    @Test
    fun oneSidedTransientDisconnectReturnsToOpenWithoutConsumingServerReconnect() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository().apply {
            startState = MatchStartAck(
                "ACTIVE",
                localAcked = true,
                bothAcked = true,
                negotiationEpoch = 2,
            )
        }
        val controller = OnlineMatchController(
            "transient-disconnect",
            Disc.BLACK,
            transport,
            repository,
            disconnectDebounceMillis = 50,
        )
        controller.onDataChannelOpen()

        transport.disconnect()
        delay(10)
        transport.reconnect()
        delay(75)

        assertEquals(0, repository.submitCalls)
        assertEquals(0, repository.resumeCalls)
        assertEquals("ACTIVE", repository.startState.serverStatus)
        assertEquals(2, repository.startState.negotiationEpoch)
        assertEquals(MatchStatus.PLAYING, controller.viewState.matchState.status)
        controller.close()
    }

    @Test
    fun serverAlreadyReconnectingWhenTransportOpensCompletesAckAndSynchronizesWithPeer() = runBlocking {
        var now = 0L
        val blackTransport = FakeMatchTransport()
        val whiteTransport = FakeMatchTransport()
        blackTransport.peer = whiteTransport
        whiteTransport.peer = blackTransport
        val blackRepository = FakeOnlineRepository().apply {
            serverStatuses.clear()
            serverStatuses += "RECONNECTING"
            startState = MatchStartAck("ACTIVE", localAcked = true, bothAcked = true)
        }
        val black = OnlineMatchController(
            "reconnect",
            Disc.BLACK,
            blackTransport,
            blackRepository,
            synchronizationTimeoutMillis = 100,
            reconnectGraceMillis = 1_000,
            disconnectDebounceMillis = 0,
            monotonicNowMillis = { now },
        )
        val white = OnlineMatchController("reconnect", Disc.WHITE, whiteTransport, FakeOnlineRepository())
        black.onDataChannelOpen()
        white.onDataChannelOpen()
        assertTrue(black.play(Position(2, 3)))
        awaitCondition { black.viewState.matchState.status == MatchStatus.PLAYING }

        blackTransport.disconnect()
        awaitCondition {
            blackRepository.submitCalls == 1 &&
                black.viewState.matchState.status == MatchStatus.RECONNECTING
        }
        blackRepository.startStateResponses += MatchStartAck(
            "RECONNECTING", localAcked = true, bothAcked = false, negotiationEpoch = 1,
        )
        blackRepository.startStateResponses += MatchStartAck(
            "ACTIVE", localAcked = true, bothAcked = true, negotiationEpoch = 1,
        )
        blackTransport.reconnect()
        awaitCondition { black.viewState.matchState.status == MatchStatus.PLAYING }

        assertEquals(0, blackRepository.resumeCalls)
        assertEquals(black.viewState.game, white.viewState.game)
        assertTrue(blackTransport.sentSyncMessages.any { it.type == SyncMessageType.REQUEST })
        assertTrue(whiteTransport.sentSyncMessages.any { it.type == SyncMessageType.SNAPSHOT })
        val before = black.viewState.whiteRemainingMillis
        now += 100
        black.refreshClock()
        assertTrue(black.viewState.whiteRemainingMillis < before)
        black.close()
        white.close()
    }

    @Test
    fun disconnectEvidenceRetriesAreBoundedAndReuseOneRequestId() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository().apply {
            submitFailuresRemaining = 2
            serverStatuses.clear()
            serverStatuses += "RECONNECTING"
        }
        val controller = OnlineMatchController(
            "disconnect-report-retry",
            Disc.BLACK,
            transport,
            repository,
            reconnectGraceMillis = 10_000,
            disconnectDebounceMillis = 0,
            disconnectReportRetryMillis = 5,
        )
        controller.onDataChannelOpen()

        transport.disconnect()
        awaitCondition { repository.attemptedSubmissions.size == 3 }

        assertEquals(1, repository.attemptedSubmissions.map { it.requestId }.toSet().size)
        assertEquals(1, repository.submitCalls)
        assertEquals(MatchStatus.RECONNECTING, controller.viewState.matchState.status)
        controller.close()
    }

    @Test
    fun deadlineReconcileActiveUsesTheSameSynchronizationPath() = runBlocking {
        val blackTransport = FakeMatchTransport()
        val whiteTransport = FakeMatchTransport()
        blackTransport.peer = whiteTransport
        whiteTransport.peer = blackTransport
        val repository = FakeOnlineRepository().apply {
            serverStatuses.clear()
            serverStatuses += "RECONNECTING"
            startState = MatchStartAck("RECONNECTING", localAcked = true, bothAcked = false)
            reconcileResult = MatchFinishResult("ACTIVE")
        }
        val black = OnlineMatchController(
            "deadline-reconcile",
            Disc.BLACK,
            blackTransport,
            repository,
            startConfirmationAttempts = 1,
            startConfirmationDelayMillis = 0,
            synchronizationTimeoutMillis = 100,
            reconnectGraceMillis = 20,
            disconnectDebounceMillis = 0,
        )
        val white = OnlineMatchController(
            "deadline-reconcile",
            Disc.WHITE,
            whiteTransport,
            FakeOnlineRepository(),
        )
        // Initial start uses the default bilateral state; make the reconnect response
        // incomplete only after both local controllers are running.
        repository.startState = MatchStartAck("CREATED", localAcked = true, bothAcked = true)
        black.onDataChannelOpen()
        white.onDataChannelOpen()
        repository.startState = MatchStartAck("RECONNECTING", localAcked = true, bothAcked = false)

        blackTransport.disconnect()
        blackTransport.reconnect()
        awaitCondition { black.viewState.matchState.status == MatchStatus.PLAYING }

        assertEquals(1, repository.reconcileCalls)
        assertTrue(blackTransport.sentSyncMessages.any { it.type == SyncMessageType.REQUEST })
        black.close()
        white.close()
    }

    @Test
    fun retryAndSyncExhaustionEntersReconnectGrace() = runBlocking {
        val transport = FakeMatchTransport().apply { dropCommand = { true } }
        val repository = FakeOnlineRepository().apply {
            serverStatuses.clear()
            serverStatuses += "RECONNECTING"
            submitDelayMillis = 10
        }
        val controller = OnlineMatchController(
            "retry-exhaustion",
            Disc.BLACK,
            transport,
            repository,
            deliveryRetryAttempts = 2,
            deliveryAckTimeoutMillis = 5,
            synchronizationTimeoutMillis = 5,
            reconnectGraceMillis = 1_000,
        )
        controller.onDataChannelOpen()

        assertTrue(controller.play(Position(2, 3)))
        awaitCondition {
            controller.viewState.matchState.status == MatchStatus.RECONNECTING &&
                repository.submitCalls == 1
        }

        assertEquals(2, transport.sent.size)
        assertEquals(transport.sent.first(), transport.sent.last())
        assertEquals(SyncMessageType.REQUEST, transport.sentSyncMessages.single().type)
        assertEquals(1, repository.submitCalls)
        assertEquals(Disc.WHITE, repository.submitted?.loserDisc)
        controller.close()
    }

    @Test
    fun lateMismatchedAckCannotBypassReconnectEpochHandshake() = runBlocking {
        val transport = FakeMatchTransport()
        val repository = FakeOnlineRepository().apply {
            serverStatuses.clear()
            serverStatuses += "RECONNECTING"
        }
        val controller = OnlineMatchController(
            "late-ack",
            Disc.BLACK,
            transport,
            repository,
            deliveryAckTimeoutMillis = 10_000,
            reconnectGraceMillis = 10_000,
            disconnectDebounceMillis = 0,
        )
        controller.onDataChannelOpen()
        controller.play(Position(2, 3))
        transport.disconnect()
        assertEquals(MatchStatus.RECONNECTING, controller.viewState.matchState.status)

        transport.deliverMoveAck(MoveAck("late-ack", "wrong", 99, "wrong-hash"))

        assertEquals(MatchStatus.RECONNECTING, controller.viewState.matchState.status)
        assertTrue(transport.sentSyncMessages.isEmpty())
        controller.close()
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
        val repository = FakeOnlineRepository().apply { submitDelayMillis = 10 }
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

    private suspend fun awaitCondition(timeoutMillis: Long = 1_000, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(1)
        }
    }

    private enum class ProtocolState { PLAYING; companion object { fun from(status: MatchStatus) = when (status) { MatchStatus.PLAYING -> PLAYING; else -> error("not playing") } } }
}
