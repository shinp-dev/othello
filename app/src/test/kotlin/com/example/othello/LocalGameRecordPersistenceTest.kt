package com.example.othello

import com.example.othello.game.Position
import com.example.othello.match.LocalMatchController
import com.example.othello.records.LocalGameRecord
import com.example.othello.records.LocalGameRecordStore
import com.example.othello.records.LocalRecordType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class LocalGameRecordPersistenceTest {
    @Test
    fun completedGameSurvivesImmediateResetAndIsSavedOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = DelayedStore(started, release)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = LocalGameRecordPersistenceCoordinator(store, scope)
        val controller = LocalMatchController()
        var saveJob: Job? = null
        val closeable = controller.observe { it.completedRecord?.let { saveJob = coordinator.enqueue(it) } }

        var guard = 0
        while (controller.viewState.completedRecord == null && guard++ < 120) {
            controller.play(controller.viewState.game.legalMoves.first())
        }
        val completed = assertNotNull(controller.viewState.completedRecord)
        started.await()
        controller.reset()
        release.complete(Unit)
        saveJob?.join()

        assertEquals(listOf(completed), store.records.values.toList())
        assertEquals(LocalRecordSaveStatus.SAVED, coordinator.state(completed.localId)?.status)
        closeable.close()
        scope.cancel()
    }

    @Test
    fun resignationSurvivesObserverDisposal() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = DelayedStore(started, release)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = LocalGameRecordPersistenceCoordinator(store, scope)
        val controller = LocalMatchController()
        var saveJob: Job? = null
        val closeable = controller.observe { it.completedRecord?.let { saveJob = coordinator.enqueue(it) } }

        val completed = assertNotNull(controller.resign())
        started.await()
        closeable.close()
        release.complete(Unit)
        saveJob?.join()

        assertEquals(completed, store.records[completed.localId])
        scope.cancel()
    }

    @Test
    fun failedSaveCanRetryWithSameIdAndDuplicateEnqueueIsIdempotent() = runBlocking {
        val store = FlakyStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = LocalGameRecordPersistenceCoordinator(store, scope)
        val record = LocalGameRecord("retry", listOf(Position(2, 3)), 1, LocalRecordType.LOCAL_HUMAN)

        coordinator.enqueue(record)?.join()
        assertEquals(LocalRecordSaveStatus.FAILED, coordinator.state(record.localId)?.status)
        coordinator.retry(record.localId)?.join()
        assertNull(coordinator.enqueue(record))

        assertEquals(2, store.attempts)
        assertEquals(1, store.records.size)
        assertEquals(LocalRecordSaveStatus.SAVED, coordinator.state(record.localId)?.status)
        scope.cancel()
    }

    private class DelayedStore(
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : LocalGameRecordStore {
        val records = linkedMapOf<String, LocalGameRecord>()

        override suspend fun list(limit: Int) = records.values.take(limit)

        override suspend fun save(record: LocalGameRecord) {
            started.complete(Unit)
            release.await()
            records[record.localId] = record
        }

        override suspend fun delete(localId: String) {
            records.remove(localId)
        }
    }

    private class FlakyStore : LocalGameRecordStore {
        val records = linkedMapOf<String, LocalGameRecord>()
        var attempts = 0

        override suspend fun list(limit: Int) = records.values.take(limit)

        override suspend fun save(record: LocalGameRecord) {
            attempts++
            if (attempts == 1) error("temporary failure")
            records[record.localId] = record
        }

        override suspend fun delete(localId: String) {
            records.remove(localId)
        }
    }
}
