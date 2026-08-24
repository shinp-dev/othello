package com.example.othello.matchmaking

import com.example.othello.network.MAX_MATCH_NEGOTIATION_EPOCH
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.util.UUID

enum class AssignedDisc { BLACK, WHITE }

data class MatchAssignment(
    val matchId: String,
    val opponentId: String,
    val assignedDisc: AssignedDisc,
    val opponentRating: Int? = null,
    val lifecycleStatus: String? = null,
    val negotiationEpoch: Int = 0,
) {
    init {
        require(opponentRating == null || opponentRating > 0)
        require(negotiationEpoch in 0..MAX_MATCH_NEGOTIATION_EPOCH)
    }
}

sealed interface EnqueueResult {
    data object Waiting : EnqueueResult
    data class Matched(val assignment: MatchAssignment) : EnqueueResult
}

/**
 * Matchmaking operations are idempotent for one client-side waiting session.
 * Implementations must pass [requestId] to the corresponding v2 RPC unchanged.
 */
interface MatchmakingRepository {
    suspend fun enqueueOrMatch(requestId: String): EnqueueResult
    suspend fun cancelWaiting(requestId: String): MatchAssignment?
    suspend fun claimActiveMatch(): MatchAssignment?
    fun subscribeToMatchNotifications(
        onMatchAvailable: () -> Unit,
        onError: (Throwable) -> Unit = {},
    ): AutoCloseable = AutoCloseable {}
}

enum class MatchmakingStatus { IDLE, WAITING, SIGNALING, P2P_CONNECTED, PLAYING, FINISHING, CONFIRMED, DISCONNECTED, FAILED }

data class MatchmakingViewState(
    val status: MatchmakingStatus = MatchmakingStatus.IDLE,
    val assignment: MatchAssignment? = null,
    val error: String? = null,
)

/** UI-facing state holder; it contains no SQL or Supabase details. */
class MatchmakingController(
    private val repository: MatchmakingRepository,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    var state: MatchmakingViewState = MatchmakingViewState()
        private set
    private val listeners = mutableSetOf<(MatchmakingViewState) -> Unit>()
    private val operationMutex = Mutex()
    private var waitingRequestId: String? = null

    fun observe(listener: (MatchmakingViewState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    suspend fun enqueue() = operationMutex.withLock {
        val requestId = waitingRequestId ?: requestIdFactory().also { waitingRequestId = it }
        state = MatchmakingViewState(MatchmakingStatus.WAITING)
        publishState()
        try {
            val result = repository.enqueueOrMatch(requestId)
            if (state.status == MatchmakingStatus.WAITING) {
                state = when (result) {
                    EnqueueResult.Waiting -> MatchmakingViewState(MatchmakingStatus.WAITING)
                    is EnqueueResult.Matched -> {
                        waitingRequestId = null
                        MatchmakingViewState(MatchmakingStatus.SIGNALING, result.assignment)
                    }
                }
                publishState()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (state.status == MatchmakingStatus.WAITING) {
                state = MatchmakingViewState(MatchmakingStatus.FAILED, error = error.message)
                publishState()
            }
        }
    }

    suspend fun cancel() = operationMutex.withLock {
        val requestId = waitingRequestId ?: return@withLock
        try {
            val assignment = repository.cancelWaiting(requestId)
            waitingRequestId = null
            state = if (assignment == null) MatchmakingViewState()
            else MatchmakingViewState(MatchmakingStatus.SIGNALING, assignment)
            publishState()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state = state.copy(status = MatchmakingStatus.FAILED, error = error.message)
            publishState()
        }
    }

    suspend fun heartbeat() = operationMutex.withLock {
        if (state.status != MatchmakingStatus.WAITING) return@withLock
        val requestId = waitingRequestId ?: requestIdFactory().also { waitingRequestId = it }
        try {
            when (val result = repository.enqueueOrMatch(requestId)) {
                EnqueueResult.Waiting -> Unit
                is EnqueueResult.Matched -> {
                    waitingRequestId = null
                    state = MatchmakingViewState(MatchmakingStatus.SIGNALING, result.assignment)
                    publishState()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (state.status == MatchmakingStatus.WAITING) {
                state = state.copy(status = MatchmakingStatus.FAILED, error = error.message)
                publishState()
            }
        }
    }

    suspend fun claimNotifiedMatch() = operationMutex.withLock {
        if (state.status != MatchmakingStatus.WAITING) return@withLock
        try {
            val assignment = repository.claimActiveMatch()
            if (assignment != null && state.status == MatchmakingStatus.WAITING) {
                waitingRequestId = null
                state = MatchmakingViewState(MatchmakingStatus.SIGNALING, assignment)
                publishState()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The heartbeat loop remains the fallback if Realtime delivery or
            // the immediate claim races with another queue operation.
        }
    }

    /**
     * Restores the caller's unexpired assignment after process or response loss.
     * Unlike a Realtime notification claim, failures are propagated to the caller.
     */
    suspend fun restoreActiveAssignment(): MatchAssignment? = operationMutex.withLock {
        val assignment = repository.claimActiveMatch()
        if (assignment != null && state.status in RESTORABLE_STATUSES) {
            waitingRequestId = null
            state = MatchmakingViewState(MatchmakingStatus.SIGNALING, assignment)
            publishState()
        }
        assignment
    }

    fun subscribeToMatchNotifications(
        onMatchAvailable: () -> Unit,
        onError: (Throwable) -> Unit = {},
    ): AutoCloseable = repository.subscribeToMatchNotifications(onMatchAvailable, onError)

    fun reset() {
        waitingRequestId = null
        state = MatchmakingViewState()
        publishState()
    }

    private fun publishState() = listeners.toList().forEach { it(state) }

    private companion object {
        val RESTORABLE_STATUSES = setOf(
            MatchmakingStatus.IDLE,
            MatchmakingStatus.WAITING,
            MatchmakingStatus.FAILED,
            MatchmakingStatus.DISCONNECTED,
        )
    }
}
