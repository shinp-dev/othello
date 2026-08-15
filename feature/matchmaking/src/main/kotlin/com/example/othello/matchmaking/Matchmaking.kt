package com.example.othello.matchmaking

enum class AssignedDisc { BLACK, WHITE }

data class MatchAssignment(
    val matchId: String,
    val opponentId: String,
    val assignedDisc: AssignedDisc,
    val opponentRating: Int? = null,
) {
    init {
        require(opponentRating == null || opponentRating > 0)
    }
}

sealed interface EnqueueResult {
    data object Waiting : EnqueueResult
    data class Matched(val assignment: MatchAssignment) : EnqueueResult
}

/** The implementation calls enqueue_or_match(); it never accepts a client rating. */
interface MatchmakingRepository {
    suspend fun enqueueOrMatch(): EnqueueResult
    suspend fun cancelWaiting(): Boolean
    suspend fun heartbeatWaiting(): Boolean
    suspend fun claimMatchedAssignment(): MatchAssignment? = null
    suspend fun reconcileCallerActiveMatch(): Boolean = false
}

enum class MatchmakingStatus { IDLE, WAITING, SIGNALING, P2P_CONNECTED, PLAYING, FINISHING, CONFIRMED, DISCONNECTED, FAILED }

data class MatchmakingViewState(
    val status: MatchmakingStatus = MatchmakingStatus.IDLE,
    val assignment: MatchAssignment? = null,
    val error: String? = null,
)

/** UI-facing state holder; it contains no SQL or Supabase details. */
class MatchmakingController(private val repository: MatchmakingRepository) {
    var state: MatchmakingViewState = MatchmakingViewState()
        private set
    private val listeners = mutableSetOf<(MatchmakingViewState) -> Unit>()

    fun observe(listener: (MatchmakingViewState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    suspend fun enqueue() {
        state = MatchmakingViewState(MatchmakingStatus.WAITING)
        publishState()
        runCatching { repository.enqueueOrMatch() }.onSuccess { result ->
            state = when (result) {
                EnqueueResult.Waiting -> MatchmakingViewState(MatchmakingStatus.WAITING)
                is EnqueueResult.Matched -> MatchmakingViewState(MatchmakingStatus.SIGNALING, result.assignment)
            }
            publishState()
        }.onFailure { state = MatchmakingViewState(MatchmakingStatus.FAILED, error = it.message); publishState() }
    }

    suspend fun cancel() {
        runCatching {
            val cancelled = repository.cancelWaiting()
            if (cancelled) null else repository.claimMatchedAssignment()
        }.onSuccess { assignment ->
            state = if (assignment == null) MatchmakingViewState()
            else MatchmakingViewState(MatchmakingStatus.SIGNALING, assignment)
            publishState()
        }
            .onFailure { state = state.copy(status = MatchmakingStatus.FAILED, error = it.message); publishState() }
    }

    suspend fun heartbeat() {
        runCatching {
            val queueAlive = repository.heartbeatWaiting()
            val assignment = repository.claimMatchedAssignment()
            if (assignment != null) EnqueueResult.Matched(assignment)
            else if (!queueAlive) repository.enqueueOrMatch()
            else EnqueueResult.Waiting
        }.onSuccess { result ->
            when (result) {
                EnqueueResult.Waiting -> Unit
                is EnqueueResult.Matched -> {
                    state = MatchmakingViewState(MatchmakingStatus.SIGNALING, result.assignment)
                    publishState()
                }
            }
        }.onFailure { state = state.copy(status = MatchmakingStatus.FAILED, error = it.message); publishState() }
    }

    fun reset() {
        state = MatchmakingViewState()
        publishState()
    }

    private fun publishState() = listeners.toList().forEach { it(state) }
}
