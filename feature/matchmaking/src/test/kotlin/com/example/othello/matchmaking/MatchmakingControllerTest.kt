package com.example.othello.matchmaking

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeMatchmakingRepository : MatchmakingRepository {
    var heartbeatResult = true
    var claimResult: MatchAssignment? = null
    var enqueueResult: EnqueueResult = EnqueueResult.Waiting
    var cancelResult = true
    var enqueueCalls = 0

    override suspend fun enqueueOrMatch(): EnqueueResult {
        enqueueCalls++
        return enqueueResult
    }

    override suspend fun cancelWaiting() = cancelResult
    override suspend fun heartbeatWaiting() = heartbeatResult
    override suspend fun claimMatchedAssignment() = claimResult
}

class MatchmakingControllerTest {
    private val assignment = MatchAssignment("match", "opponent", AssignedDisc.BLACK, 1520)

    @Test
    fun expiredQueueIsReenqueuedInsteadOfRemainingFalselyWaiting() = runBlocking {
        val repository = FakeMatchmakingRepository().apply { heartbeatResult = false }
        val controller = MatchmakingController(repository)
        controller.enqueue()
        repository.enqueueCalls = 0

        controller.heartbeat()

        assertEquals(MatchmakingStatus.WAITING, controller.state.status)
        assertEquals(1, repository.enqueueCalls)
    }

    @Test
    fun heartbeatClaimsMatchThatRacedWithQueueDeletion() = runBlocking {
        val repository = FakeMatchmakingRepository().apply {
            heartbeatResult = false
            claimResult = assignment
        }
        val controller = MatchmakingController(repository)

        controller.heartbeat()

        assertEquals(MatchmakingStatus.SIGNALING, controller.state.status)
        assertEquals(assignment, controller.state.assignment)
        assertEquals(0, repository.enqueueCalls)
    }

    @Test
    fun cancelThatLosesMatchRaceDoesNotHideTheAssignment() = runBlocking {
        val repository = FakeMatchmakingRepository().apply {
            cancelResult = false
            claimResult = assignment
        }
        val controller = MatchmakingController(repository)

        controller.cancel()

        assertEquals(MatchmakingStatus.SIGNALING, controller.state.status)
        assertEquals(assignment, controller.state.assignment)
    }
}
