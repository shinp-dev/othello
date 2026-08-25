package com.example.othello

import com.example.othello.game.Disc
import com.example.othello.game.GameStatus
import com.example.othello.match.MatchFinishResult
import com.example.othello.match.MatchStartAck
import com.example.othello.match.MatchStatus
import com.example.othello.match.MatchSubmission
import com.example.othello.match.OnlineMatchController
import com.example.othello.match.OnlineMatchRepository
import com.example.othello.matchmaking.AssignedDisc
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.network.MatchTransport
import com.example.othello.network.MoveAck
import com.example.othello.network.MoveCommand
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecoveryCheckpointTestTransport : MatchTransport {
    private var commandListener: ((MoveCommand) -> Unit)? = null
    private var moveAckListener: ((MoveAck) -> Unit)? = null
    var lastSentCommand: MoveCommand? = null

    override suspend fun send(command: MoveCommand) {
        lastSentCommand = command
    }

    override suspend fun sendMoveAck(ack: MoveAck) = Unit

    override fun observe(onCommand: (MoveCommand) -> Unit): AutoCloseable {
        commandListener = onCommand
        return AutoCloseable { commandListener = null }
    }

    override fun observeMoveAck(onAck: (MoveAck) -> Unit): AutoCloseable {
        moveAckListener = onAck
        return AutoCloseable { moveAckListener = null }
    }

    fun deliver(command: MoveCommand) = requireNotNull(commandListener).invoke(command)

    fun deliverMoveAck(ack: MoveAck) = requireNotNull(moveAckListener).invoke(ack)
}

private class RecoveryCheckpointTestRepository : OnlineMatchRepository {
    override suspend fun ackMatchStarted(matchId: String, expectedNegotiationEpoch: Int) =
        MatchStartAck("ACTIVE", localAcked = true, bothAcked = true, negotiationEpoch = expectedNegotiationEpoch)

    override suspend fun getMatchStartState(matchId: String) =
        MatchStartAck("ACTIVE", localAcked = true, bothAcked = true)

    override suspend fun abandonMatch(matchId: String) = true

    override suspend fun submitMatchResult(submission: MatchSubmission) = MatchFinishResult("CONFIRMED")

    override suspend fun resumeMatch(matchId: String, expectedNegotiationEpoch: Int) =
        MatchStartAck("ACTIVE", localAcked = true, bothAcked = true, negotiationEpoch = expectedNegotiationEpoch)
}

class RecoveryCheckpointConsistencyTest {
    @Test
    fun checkpointsStayConsistentAcrossNormalForcedPassAndTerminalMoves() = runBlocking {
        val matchId = "recovery-checkpoint-consistency"
        val assignment = MatchAssignment(
            matchId = matchId,
            opponentId = "opponent",
            assignedDisc = AssignedDisc.BLACK,
            opponentRating = 1_500,
            lifecycleStatus = "ACTIVE",
            negotiationEpoch = 0,
        )
        val transport = RecoveryCheckpointTestTransport()
        val controller = OnlineMatchController(
            matchId = matchId,
            localDisc = Disc.BLACK,
            transport = transport,
            repository = RecoveryCheckpointTestRepository(),
            deliveryAckTimeoutMillis = 60_000,
        )
        val snapshots = mutableListOf<OnlineMatchRecoverySnapshot>()
        var sawFinishedBeforeServerResult = false
        val subscription = controller.observe { view ->
            if (view.matchState.status !in setOf(
                    MatchStatus.CONFIRMED,
                    MatchStatus.FORFEIT,
                    MatchStatus.EXPIRED,
                    MatchStatus.ABANDONED,
                    MatchStatus.DISPUTED,
                )
            ) {
                val snapshot = view.toRecoverySnapshot(
                    userId = "user",
                    assignment = assignment,
                    negotiationEpoch = 0,
                    updatedAtEpochMillis = 1,
                )
                snapshots += snapshot
                if (view.game.status is GameStatus.Finished) {
                    sawFinishedBeforeServerResult = true
                    assertNull(snapshot.runningDisc)
                } else if (snapshot.runningDisc != null) {
                    assertEquals(view.game.currentPlayer, snapshot.runningDisc)
                }
            }
        }

        assertTrue(controller.onDataChannelOpen())
        while (controller.viewState.game.status is GameStatus.InProgress) {
            val before = controller.viewState.game
            val move = before.legalMoves.first()
            if (before.currentPlayer == Disc.BLACK) {
                assertTrue(controller.play(move))
                val sent = requireNotNull(transport.lastSentCommand)
                transport.deliverMoveAck(
                    MoveAck(matchId, sent.commandId, controller.viewState.game.ply, controller.viewState.game.stateHash()),
                )
            } else {
                transport.deliver(
                    MoveCommand(
                        matchId = matchId,
                        ply = before.ply,
                        move = move,
                        commandId = "remote-${before.ply}",
                        previousStateHash = before.stateHash(),
                    ),
                )
            }
        }

        assertTrue(snapshots.any { it.canonicalMoves.length == 2 && it.runningDisc != null })
        assertTrue(snapshots.any { "--" in it.canonicalMoves }, "deterministic game should exercise a forced pass")
        assertTrue(sawFinishedBeforeServerResult)
        subscription.close()
        controller.close()
    }
}
