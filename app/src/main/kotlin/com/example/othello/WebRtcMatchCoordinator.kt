package com.example.othello

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.othello.data.supabase.SupabaseSignalingDataSource
import com.example.othello.game.Disc
import com.example.othello.match.OnlineMatchController
import com.example.othello.match.OnlineMatchRepository
import com.example.othello.match.MatchDiagnostics
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.transport.webrtc.AndroidWebRtcTransportFactory
import com.example.othello.transport.webrtc.DefaultIceServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns the P2P session outside Compose so Activity recreation does not create a second peer. */
class WebRtcMatchCoordinator(
    context: Context,
    private val userId: String,
    private val assignment: MatchAssignment,
    private val signaling: SupabaseSignalingDataSource,
    private val repository: OnlineMatchRepository,
    scope: CoroutineScope,
    private val debugAutoPlay: Boolean = false,
    debugTimeControlMillis: Long? = null,
) : AutoCloseable {
    val matchId: String get() = assignment.matchId
    val opponentRating: Int? get() = assignment.opponentRating
    private val sessionJob = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
    private val transport = AndroidWebRtcTransportFactory(context.applicationContext)
        .create(assignment.matchId, DefaultIceServers.publicStun)
    val controller = OnlineMatchController(
        assignment.matchId,
        if (assignment.assignedDisc.name == "BLACK") Disc.BLACK else Disc.WHITE,
        transport,
        repository,
        callbackScope = sessionScope,
        cancelCallbackScopeOnClose = false,
        timeControlMillis = debugTimeControlMillis ?: com.example.othello.match.DEFAULT_TIME_CONTROL_MILLIS,
    )

    fun diagnostics(): MatchDiagnostics = controller.diagnostics(userId, assignment.opponentId)
    private val signalingSession = WebRtcSignalingSession(
        matchId = assignment.matchId,
        userId = userId,
        opponentId = assignment.opponentId,
        offerer = assignment.assignedDisc.name == "BLACK",
        transport = transport,
        signaling = signaling,
        parentScope = sessionScope,
        onDataChannelOpen = controller::onDataChannelOpen,
        onError = { error -> controller.reportConnectionError(error.message ?: "signaling failed") },
        onEvent = { event ->
            Log.i(
                "WebRtcSignaling",
                "match=${assignment.matchId} role=${assignment.assignedDisc.name} event=$event elapsedRealtimeMs=${SystemClock.elapsedRealtime()}",
            )
        },
    )
    private var autoPlaySubscription: AutoCloseable? = null
    private var autoPlayJob: Job? = null
    private var pendingResultRetryJob: Job? = null
    private var autoPlayInFlight = false
    private var started = false
    private var closed = false

    fun start() {
        if (started) return
        started = true
        autoPlaySubscription = controller.observe { view ->
            if (debugAutoPlay && view.matchState.status == com.example.othello.match.MatchStatus.PLAYING &&
                    view.game.currentPlayer == view.localDisc &&
                    view.game.legalMoves.isNotEmpty() && !autoPlayInFlight
            ) {
                autoPlayInFlight = true
                autoPlayJob = sessionScope.launch {
                    try {
                        while (true) {
                            val next = controller.viewState
                            if (next.matchState.status != com.example.othello.match.MatchStatus.PLAYING ||
                                next.game.currentPlayer != next.localDisc || next.game.legalMoves.isEmpty()
                            ) break
                            if (!controller.play(next.game.legalMoves.first())) break
                        }
                    } finally {
                        autoPlayInFlight = false
                    }
                }
            } else if (view.matchState.status in setOf(
                    com.example.othello.match.MatchStatus.FINISHING,
                    com.example.othello.match.MatchStatus.PENDING_RESULT,
                )
            ) {
                schedulePendingResultRetry()
            } else if (view.matchState.status in setOf(
                    com.example.othello.match.MatchStatus.CONFIRMED,
                    com.example.othello.match.MatchStatus.DISPUTED,
                )
            ) {
                pendingResultRetryJob?.cancel()
            }
        }
        signalingSession.start()
    }

    private fun schedulePendingResultRetry() {
        if (pendingResultRetryJob?.isActive == true || closed) return
        pendingResultRetryJob = sessionScope.launch {
            repeat(PENDING_RESULT_RETRY_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) 750L else PENDING_RESULT_RETRY_MILLIS)
                if (closed || controller.viewState.matchState.status !in setOf(
                        com.example.othello.match.MatchStatus.FINISHING,
                        com.example.othello.match.MatchStatus.PENDING_RESULT,
                    )
                ) {
                    return@launch
                }
                controller.retryFinish()
            }
        }
    }

    suspend fun leave() {
        when (controller.viewState.matchState.status) {
            com.example.othello.match.MatchStatus.PLAYING -> controller.finishForDisconnect()
            com.example.othello.match.MatchStatus.CONFIRMED,
            com.example.othello.match.MatchStatus.DISPUTED,
            com.example.othello.match.MatchStatus.PENDING_RESULT -> Unit
            else -> runCatching { repository.abandonMatch(assignment.matchId) }
        }
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        signalingSession.close()
        autoPlaySubscription?.close()
        autoPlayJob?.cancel()
        pendingResultRetryJob?.cancel()
        controller.close()
        sessionJob.cancel()
    }

    private companion object {
        const val PENDING_RESULT_RETRY_ATTEMPTS = 61
        const val PENDING_RESULT_RETRY_MILLIS = 5_000L
    }
}
