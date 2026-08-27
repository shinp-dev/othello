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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class LocalGameRecordPersistenceTest {
    @Test
    fun completedGameSurvivesImmediateResetAndIsSavedOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = DelayedStore(started, release)
        val processOwner = LocalGameRecordPersistenceProcessOwner(store, Dispatchers.Default)
        val coordinator = processOwner.coordinator
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
        closeable.close() // Activity/screen owner is destroyed while the process save is suspended.
        release.complete(Unit)
        saveJob?.join()

        assertEquals(listOf(completed), store.records.values.toList())
        assertEquals(LocalRecordSaveStatus.SAVED, coordinator.state(completed.localId)?.status)
        processOwner.close()
    }

    @Test
    fun resignationSurvivesObserverDisposal() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = DelayedStore(started, release)
        val processOwner = LocalGameRecordPersistenceProcessOwner(store, Dispatchers.Default)
        val coordinator = processOwner.coordinator
        val controller = LocalMatchController()
        var saveJob: Job? = null
        val closeable = controller.observe { it.completedRecord?.let { saveJob = coordinator.enqueue(it) } }

        val completed = assertNotNull(controller.resign())
        started.await()
        closeable.close()
        release.complete(Unit)
        saveJob?.join()

        assertEquals(completed, store.records[completed.localId])
        processOwner.close()
    }

    @Test
    fun failedSaveCanRetryWithSameIdAndDuplicateEnqueueIsIdempotent() = runBlocking {
        val store = FlakyStore()
        val processOwner = LocalGameRecordPersistenceProcessOwner(store, Dispatchers.Default)
        val coordinator = processOwner.coordinator
        val record = LocalGameRecord("retry", listOf(Position(2, 3)), 1, LocalRecordType.LOCAL_HUMAN)

        coordinator.enqueue(record)?.join()
        assertEquals(LocalRecordSaveStatus.FAILED, coordinator.state(record.localId)?.status)
        coordinator.retry(record.localId)?.join()
        assertNull(coordinator.enqueue(record))

        assertEquals(2, store.attempts)
        assertEquals(1, store.records.size)
        assertEquals(LocalRecordSaveStatus.SAVED, coordinator.state(record.localId)?.status)
        processOwner.close()
    }

    @Test
    fun activityDestructionBeforeIoDispatchDoesNotCancelProcessSave() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val store = RecordingStore()
        val processOwner = LocalGameRecordPersistenceProcessOwner(store, dispatcher)
        val coordinator = processOwner.coordinator
        val controller = LocalMatchController()
        val activityOwner = FakeActivityOwner(controller, coordinator)

        val completed = assertNotNull(controller.resign())
        activityOwner.destroy()
        assertEquals(LocalRecordSaveStatus.SAVING, coordinator.state(completed.localId)?.status)

        dispatcher.runAll()
        activityOwner.saveJob?.join()

        assertEquals(listOf(completed), store.records.values.toList())
        assertEquals(LocalRecordSaveStatus.SAVED, coordinator.state(completed.localId)?.status)
        processOwner.close()
    }

    @Test
    fun resetFailureRemainsDiscoverableAfterActivityRecreationAndRetriesOnce() = runBlocking {
        val store = FlakyStore()
        val processOwner = LocalGameRecordPersistenceProcessOwner(store, Dispatchers.Default)
        val coordinator = processOwner.coordinator
        val controller = LocalMatchController()
        val firstActivityOwner = FakeActivityOwner(controller, coordinator)

        val completed = assertNotNull(controller.resign())
        controller.reset()
        firstActivityOwner.destroy()
        firstActivityOwner.saveJob?.join()

        assertEquals(listOf(completed), coordinator.failedRecords())
        assertEquals(emptyList(), coordinator.pendingRecords())

        // A recreated Activity obtains the same process owner and can retry by stable localId.
        coordinator.retry(coordinator.failedRecords().single().localId)?.join()

        assertEquals(2, store.attempts)
        assertEquals(listOf(completed), store.records.values.toList())
        assertEquals(LocalRecordSaveStatus.SAVED, coordinator.state(completed.localId)?.status)
        processOwner.close()
    }

    @Test
    fun destroyingActivityWithIncompleteMatchDoesNotEnqueueRecord() {
        val store = RecordingStore()
        val processOwner = LocalGameRecordPersistenceProcessOwner(store, Dispatchers.Default)
        val coordinator = processOwner.coordinator
        val controller = LocalMatchController()
        val activityOwner = FakeActivityOwner(controller, coordinator)

        controller.play(controller.viewState.game.legalMoves.first())
        activityOwner.destroy()

        assertNull(activityOwner.saveJob)
        assertEquals(emptyList(), coordinator.pendingRecords())
        assertEquals(emptyList(), coordinator.failedRecords())
        assertEquals(emptyList(), store.records.values.toList())
        processOwner.close()
    }

    @Test
    fun duplicateEnqueueWhileSavingUsesOneActiveWriteAndOneFinalRecord() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = DelayedStore(started, release)
        val processOwner = LocalGameRecordPersistenceProcessOwner(store, Dispatchers.Default)
        val coordinator = processOwner.coordinator
        val record = LocalGameRecord("saving", listOf(Position(2, 3)), 1, LocalRecordType.LOCAL_HUMAN)

        val first = assertNotNull(coordinator.enqueue(record))
        started.await()
        val duplicate = coordinator.enqueue(record)
        assertEquals(first, duplicate)
        assertEquals(listOf(record), coordinator.pendingRecords())

        release.complete(Unit)
        first.join()

        assertEquals(1, store.attempts)
        assertEquals(listOf(record), store.records.values.toList())
        assertEquals(LocalRecordSaveStatus.SAVED, coordinator.state(record.localId)?.status)
        processOwner.close()
    }

    @Test
    fun terminalUndoDiscardsAlreadySavedRecord() = runBlocking {
        val store = RecordingStore()
        val processOwner = LocalGameRecordPersistenceProcessOwner(store, Dispatchers.Default)
        val coordinator = processOwner.coordinator
        val record = LocalGameRecord("withdrawn", listOf(Position(2, 3)), 1, LocalRecordType.LOCAL_HUMAN)
        coordinator.enqueue(record)?.join()
        assertEquals(record, store.records[record.localId])

        coordinator.discard(record.localId)?.join()

        assertNull(store.records[record.localId])
        assertEquals(LocalRecordSaveStatus.DISCARDED, coordinator.state(record.localId)?.status)
        assertEquals(emptyList(), coordinator.failedRecords())
        processOwner.close()
    }

    @Test
    fun discardWaitsForNonCooperativeInFlightSaveThenDeletesIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = NonCooperativeDelayedStore(started, release)
        val processOwner = LocalGameRecordPersistenceProcessOwner(store, Dispatchers.Default)
        val coordinator = processOwner.coordinator
        val record = LocalGameRecord("racing", listOf(Position(2, 3)), 1, LocalRecordType.LOCAL_AI)
        coordinator.enqueue(record)
        started.await()

        val discard = assertNotNull(coordinator.discard(record.localId))
        assertEquals(LocalRecordSaveStatus.DISCARDING, coordinator.state(record.localId)?.status)
        release.complete(Unit)
        discard.join()

        assertEquals(1, store.saveAttempts)
        assertEquals(1, store.deleteAttempts)
        assertNull(store.records[record.localId])
        assertEquals(LocalRecordSaveStatus.DISCARDED, coordinator.state(record.localId)?.status)
        processOwner.close()
    }

    private class DelayedStore(
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : LocalGameRecordStore {
        val records = linkedMapOf<String, LocalGameRecord>()
        var attempts = 0

        override suspend fun list(limit: Int) = records.values.take(limit)

        override suspend fun save(record: LocalGameRecord) {
            attempts++
            started.complete(Unit)
            release.await()
            records[record.localId] = record
        }

        override suspend fun delete(localId: String) {
            records.remove(localId)
        }
    }

    private class NonCooperativeDelayedStore(
        private val started: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : LocalGameRecordStore {
        val records = linkedMapOf<String, LocalGameRecord>()
        var saveAttempts = 0
        var deleteAttempts = 0

        override suspend fun list(limit: Int) = records.values.take(limit)

        override suspend fun save(record: LocalGameRecord) {
            saveAttempts++
            started.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                records[record.localId] = record
            }
        }

        override suspend fun delete(localId: String) {
            deleteAttempts++
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

    private class RecordingStore : LocalGameRecordStore {
        val records = linkedMapOf<String, LocalGameRecord>()

        override suspend fun list(limit: Int) = records.values.take(limit)

        override suspend fun save(record: LocalGameRecord) {
            records[record.localId] = record
        }

        override suspend fun delete(localId: String) {
            records.remove(localId)
        }
    }

    private class FakeActivityOwner(
        controller: LocalMatchController,
        coordinator: LocalGameRecordPersistenceCoordinator,
    ) {
        var saveJob: Job? = null
            private set
        private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val observation = controller.observe { state ->
            state.completedRecord?.let { saveJob = coordinator.enqueue(it) }
        }

        fun destroy() {
            observation.close()
            activityScope.cancel()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
