package com.example.othello.matchmaking

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class FakeMatchmakingRepository : MatchmakingRepository {
    var enqueueResult: EnqueueResult = EnqueueResult.Waiting
    var enqueueError: Throwable? = null
    var cancelResult: MatchAssignment? = null
    var cancelError: Throwable? = null
    var claimResult: MatchAssignment? = null
    val enqueueRequestIds = mutableListOf<String>()
    val cancelRequestIds = mutableListOf<String>()
    var claimCalls = 0

    override suspend fun enqueueOrMatch(requestId: String): EnqueueResult {
        enqueueRequestIds += requestId
        enqueueError?.let { throw it }
        return enqueueResult
    }

    override suspend fun cancelWaiting(requestId: String): MatchAssignment? {
        cancelRequestIds += requestId
        cancelError?.let { throw it }
        return cancelResult
    }

    override suspend fun claimActiveMatch(): MatchAssignment? {
        claimCalls++
        return claimResult
    }
}

class MatchmakingControllerTest {
    private val firstRequestId = "00000000-0000-0000-0000-000000000001"
    private val secondRequestId = "00000000-0000-0000-0000-000000000002"
    private val assignment = MatchAssignment("match", "opponent", AssignedDisc.BLACK, 1520, "MATCHED", 2)

    private fun controller(
        repository: FakeMatchmakingRepository,
        requestIds: Iterator<String> = listOf(firstRequestId, secondRequestId).iterator(),
    ) = MatchmakingController(repository) { requestIds.next() }

    @Test
    fun heartbeatUsesTheWaitingSessionRequestIdAndOneRpc() = runBlocking {
        val repository = FakeMatchmakingRepository()
        val controller = controller(repository)
        controller.enqueue()

        controller.heartbeat()

        assertEquals(MatchmakingStatus.WAITING, controller.state.status)
        assertEquals(listOf(firstRequestId, firstRequestId), repository.enqueueRequestIds)
        assertEquals(0, repository.claimCalls)
    }

    @Test
    fun retryAfterLostEnqueueResponseKeepsTheSameRequestId() = runBlocking {
        val repository = FakeMatchmakingRepository().apply {
            enqueueError = IllegalStateException("response lost")
        }
        val controller = controller(repository)

        controller.enqueue()
        assertEquals(MatchmakingStatus.FAILED, controller.state.status)
        repository.enqueueError = null
        controller.enqueue()

        assertEquals(MatchmakingStatus.WAITING, controller.state.status)
        assertEquals(listOf(firstRequestId, firstRequestId), repository.enqueueRequestIds)
    }

    @Test
    fun heartbeatReceivesAssignmentFromItsSingleEnqueueRpc() = runBlocking {
        val repository = FakeMatchmakingRepository()
        val controller = controller(repository)
        controller.enqueue()
        repository.enqueueResult = EnqueueResult.Matched(assignment)

        controller.heartbeat()

        assertEquals(MatchmakingStatus.SIGNALING, controller.state.status)
        assertEquals(assignment, controller.state.assignment)
        assertEquals(listOf(firstRequestId, firstRequestId), repository.enqueueRequestIds)
        assertEquals(0, repository.claimCalls)
    }

    @Test
    fun cancelThatLosesMatchRaceDoesNotHideTheAssignment() = runBlocking {
        val repository = FakeMatchmakingRepository().apply {
            cancelResult = assignment
        }
        val controller = controller(repository)
        controller.enqueue()

        controller.cancel()

        assertEquals(MatchmakingStatus.SIGNALING, controller.state.status)
        assertEquals(assignment, controller.state.assignment)
        assertEquals(listOf(firstRequestId), repository.cancelRequestIds)
        assertEquals(0, repository.claimCalls)
    }

    @Test
    fun failedWaitingSessionCanRetryCancellationWithTheSameRequestId() = runBlocking {
        val repository = FakeMatchmakingRepository().apply {
            enqueueError = IllegalStateException("enqueue response lost")
            cancelError = IllegalStateException("cancel response lost")
        }
        val controller = controller(repository)
        controller.enqueue()

        controller.cancel()
        assertEquals(MatchmakingStatus.FAILED, controller.state.status)
        repository.cancelError = null
        controller.cancel()

        assertEquals(MatchmakingStatus.IDLE, controller.state.status)
        assertEquals(listOf(firstRequestId, firstRequestId), repository.cancelRequestIds)
    }

    @Test
    fun coroutineCancellationIsNotConvertedToFailedState() = runBlocking {
        val repository = FakeMatchmakingRepository().apply {
            enqueueError = CancellationException("screen left")
        }
        val controller = controller(repository)

        assertFailsWith<CancellationException> { controller.enqueue() }
        assertEquals(MatchmakingStatus.WAITING, controller.state.status)

        repository.enqueueError = null
        repository.cancelError = CancellationException("screen left")
        assertFailsWith<CancellationException> { controller.cancel() }
        assertEquals(MatchmakingStatus.WAITING, controller.state.status)
    }

    @Test
    fun realtimeNotificationClaimsWaitingMatchImmediately() = runBlocking {
        val repository = FakeMatchmakingRepository().apply { claimResult = assignment }
        val controller = controller(repository)
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
        val controller = controller(repository)
        controller.enqueue()

        controller.claimNotifiedMatch()

        assertEquals(first, controller.state.assignment)
        assertEquals(0, repository.claimCalls)
    }

    @Test
    fun lateCancelDoesNotHideRealtimeAssignment() = runBlocking {
        val repository = FakeMatchmakingRepository().apply { claimResult = assignment }
        val controller = controller(repository)
        controller.enqueue()
        controller.claimNotifiedMatch()

        controller.cancel()

        assertEquals(MatchmakingStatus.SIGNALING, controller.state.status)
        assertEquals(assignment, controller.state.assignment)
        assertEquals(emptyList(), repository.cancelRequestIds)
    }

    @Test
    fun activeAssignmentCanBeRestoredBeforeEnqueue() = runBlocking {
        val repository = FakeMatchmakingRepository().apply { claimResult = assignment }
        val controller = controller(repository)

        val restored = controller.restoreActiveAssignment()

        assertEquals(assignment, restored)
        assertEquals(MatchmakingStatus.SIGNALING, controller.state.status)
        assertEquals(assignment, controller.state.assignment)
        assertEquals(1, repository.claimCalls)
        assertEquals(emptyList(), repository.enqueueRequestIds)
    }

    @Test
    fun resetCreatesANewWaitingSessionRequestId() = runBlocking {
        val repository = FakeMatchmakingRepository()
        val controller = controller(repository)
        controller.enqueue()
        controller.reset()

        controller.enqueue()

        assertEquals(listOf(firstRequestId, secondRequestId), repository.enqueueRequestIds)
    }
}
