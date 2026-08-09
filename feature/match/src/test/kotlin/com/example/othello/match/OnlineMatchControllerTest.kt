package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.Position
import com.example.othello.network.MatchTransport
import com.example.othello.network.MoveCommand
import com.example.othello.network.TransportState
import com.example.othello.records.FinishReason
import com.example.othello.records.MatchResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeMatchTransport : MatchTransport {
    var peer: FakeMatchTransport? = null
    val sent = mutableListOf<MoveCommand>()
    private val listeners = mutableSetOf<(MoveCommand) -> Unit>()
    private var state = TransportState.OPEN

    override suspend fun send(command: MoveCommand) {
        sent += command
        peer?.deliver(command)
    }

    fun deliver(command: MoveCommand) = listeners.toList().forEach { it(command) }
    override fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable { listeners += onCommand; return AutoCloseable { listeners -= onCommand } }
    override fun observeState(onState: (TransportState) -> Unit): AutoCloseable { onState(state); return AutoCloseable { } }
}

private class FakeOnlineRepository : OnlineMatchRepository {
    var submitted: MatchSubmission? = null
    override suspend fun ackMatchStarted(matchId: String) = MatchStartAck("CREATED")
    override suspend fun abandonMatch(matchId: String) = true
    override suspend fun submitMatchResult(submission: MatchSubmission): MatchFinishResult {
        submitted = submission
        return MatchFinishResult("CONFIRMED")
    }
}

class OnlineMatchControllerTest {
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
    }

    private enum class ProtocolState { PLAYING; companion object { fun from(status: MatchStatus) = when (status) { MatchStatus.PLAYING -> PLAYING; else -> error("not playing") } } }
}
