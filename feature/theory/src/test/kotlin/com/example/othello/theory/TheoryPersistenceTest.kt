package com.example.othello.theory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class TheoryPersistenceTest {
    @Test
    fun rapidWritesCoalesceToTheNewestSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = BlockingStore()
            val coordinator = TheorySessionPersistenceCoordinator(store, scope)
            val first = TheoryExplorationSession.fresh().snapshot()
            val session = TheoryExplorationSession.fresh().apply { play(current.legalMoves.first()) }
            val second = session.snapshot()
            session.selectMetric("potential_mobility")
            val latest = session.snapshot()

            coordinator.enqueue(first)
            store.firstWriteStarted.await()
            coordinator.enqueue(second)
            coordinator.enqueue(latest)
            store.releaseFirstWrite.complete(Unit)

            withTimeout(5_000) {
                while (coordinator.state.value.status != TheorySessionSaveStatus.SAVED) {
                    kotlinx.coroutines.yield()
                }
            }

            assertEquals(listOf(first, latest), store.saved)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedLatestSnapshotCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = FailOnceStore()
            val coordinator = TheorySessionPersistenceCoordinator(store, scope)
            val snapshot = TheoryExplorationSession.fresh().snapshot()

            coordinator.enqueue(snapshot).join()
            assertEquals(TheorySessionSaveStatus.FAILED, coordinator.state.value.status)
            assertTrue(coordinator.retry() != null)
            coordinator.retry()?.join()
            withTimeout(5_000) {
                while (coordinator.state.value.status != TheorySessionSaveStatus.SAVED) {
                    kotlinx.coroutines.yield()
                }
            }

            assertEquals(snapshot, store.saved)
        } finally {
            scope.cancel()
        }
    }

    private class BlockingStore : TheorySessionStore {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val saved = mutableListOf<TheorySessionSnapshot>()

        override suspend fun load(): TheorySessionSnapshot? = null

        override suspend fun save(snapshot: TheorySessionSnapshot) {
            if (saved.isEmpty()) {
                firstWriteStarted.complete(Unit)
                releaseFirstWrite.await()
            }
            saved += snapshot
        }
    }

    private class FailOnceStore : TheorySessionStore {
        var shouldFail = true
        var saved: TheorySessionSnapshot? = null

        override suspend fun load(): TheorySessionSnapshot? = null

        override suspend fun save(snapshot: TheorySessionSnapshot) {
            if (shouldFail) {
                shouldFail = false
                error("disk full")
            }
            saved = snapshot
        }
    }
}
