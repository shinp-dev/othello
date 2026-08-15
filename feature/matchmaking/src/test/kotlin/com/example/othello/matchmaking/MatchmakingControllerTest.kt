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
        val repository = FakeMatchmakingRepository()
        val controller = MatchmakingController(repository)
        controller.enqueue()
        repository.enqueueCalls = 0
        repository.heartbeatResult = false
        repository.claimResult = assignment

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
        controller.enqueue()

        controller.cancel()

        assertEquals(MatchmakingStatus.SIGNALING, controller.state.status)
        assertEquals(assignment, controller.state.assignment)
    }

    @Test
    fun realtimeNotificationClaimsWaitingMatchImmediately() = runBlocking {
        val repository = FakeMatchmakingRepository().apply { claimResult = assignment }
        val controller = MatchmakingController(repository)
        controller.enqueue()

        controller.claimNotifiedMatch()

        assertEquals(MatchmakingStatus.SIGNALING, controller.state.status)
        assertEquals(assignment, controller.state.assignment)
    }

    @Test
    fun lateNotificationDoesNotReplaceEstablishedAssignment() = runBlocking {
        val first = assignment
        val second = MatchAssignment("other-match", "other-opponent", AssignedDisc.WHITE, 1480)
        val repository = FakeMatchmakingRepository().apply {
            enqueueResult = EnqueueResult.Matched(first)
            claimResult = second
        }
        val controller = MatchmakingController(repository)
        controller.enqueue()

        controller.claimNotifiedMatch()

        assertEquals(first, controller.state.assignment)
    }

    @Test
    fun lateCancelDoesNotHideRealtimeAssignment() = runBlocking {
        val repository = FakeMatchmakingRepository().apply { claimResult = assignment }
        val controller = MatchmakingController(repository)
        controller.enqueue()
        controller.claimNotifiedMatch()

        controller.cancel()

        assertEquals(MatchmakingStatus.SIGNALING, controller.state.status)
        assertEquals(assignment, controller.state.assignment)
    }
}
