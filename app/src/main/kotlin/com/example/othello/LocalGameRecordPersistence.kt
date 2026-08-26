package com.example.othello

import com.example.othello.records.LocalGameRecord
import com.example.othello.records.LocalGameRecordStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LocalRecordSaveStatus {
    PENDING,
    SAVING,
    SAVED,
    FAILED,
    DISCARDING,
    DISCARDED,
    DISCARD_FAILED,
}

data class LocalRecordSaveState(
    val localId: String,
    val status: LocalRecordSaveStatus,
    val errorMessage: String? = null,
)

/**
 * Owns completed local-record writes independently from the match screen.
 * Production supplies the process-owned scope from [LocalGameRecordPersistenceProcessOwner].
 */
class LocalGameRecordPersistenceCoordinator(
    private val store: LocalGameRecordStore,
    private val scope: CoroutineScope,
) {
    private val states = MutableStateFlow<Map<String, LocalRecordSaveState>>(emptyMap())
    private val records = mutableMapOf<String, LocalGameRecord>()
    private val jobs = mutableMapOf<String, Job>()
    private val operationGenerations = mutableMapOf<String, Long>()
    private val discardRequested = mutableSetOf<String>()
    private var nextOperationGeneration = 0L

    val saveStates: StateFlow<Map<String, LocalRecordSaveState>> = states.asStateFlow()

    fun enqueue(record: LocalGameRecord): Job? = synchronized(this) {
        if (record.localId in discardRequested) return@synchronized jobs[record.localId]?.takeIf { it.isActive }
        val existing = records[record.localId]
        require(existing == null || existing == record) { "localId collision: ${record.localId}" }
        records.putIfAbsent(record.localId, record)
        val current = states.value[record.localId]
        if (current?.status == LocalRecordSaveStatus.SAVED) return@synchronized null
        jobs[record.localId]?.takeIf { it.isActive }?.let { return@synchronized it }

        val generation = newOperationGeneration(record.localId)
        setState(record.localId, LocalRecordSaveStatus.SAVING)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                store.save(record)
                synchronized(this@LocalGameRecordPersistenceCoordinator) {
                    if (isCurrentOperation(record.localId, generation)) {
                        jobs.remove(record.localId)
                        setState(record.localId, LocalRecordSaveStatus.SAVED)
                    }
                }
            } catch (cancelled: CancellationException) {
                synchronized(this@LocalGameRecordPersistenceCoordinator) {
                    if (isCurrentOperation(record.localId, generation)) {
                        jobs.remove(record.localId)
                        setState(record.localId, LocalRecordSaveStatus.PENDING, cancelled.message)
                    }
                }
                throw cancelled
            } catch (failure: Throwable) {
                synchronized(this@LocalGameRecordPersistenceCoordinator) {
                    if (isCurrentOperation(record.localId, generation)) {
                        jobs.remove(record.localId)
                        setState(record.localId, LocalRecordSaveStatus.FAILED, failure.message)
                    }
                }
            }
        }
        jobs[record.localId] = job
        job.start()
        job
    }

    /**
     * Retracts a completed record invalidated by a terminal-position undo.
     * Deletion is ordered after any in-flight save, even if that save ignores cancellation.
     */
    fun discard(localId: String): Job? = synchronized(this) {
        if (records[localId] == null) return@synchronized null
        val current = states.value[localId]
        if (current?.status == LocalRecordSaveStatus.DISCARDED) return@synchronized null
        jobs[localId]?.takeIf {
            it.isActive && current?.status == LocalRecordSaveStatus.DISCARDING
        }?.let { return@synchronized it }

        discardRequested += localId
        val priorJob = jobs[localId]?.takeIf { it.isActive }
        val generation = newOperationGeneration(localId)
        setState(localId, LocalRecordSaveStatus.DISCARDING)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                priorJob?.cancelAndJoin()
                store.delete(localId)
                synchronized(this@LocalGameRecordPersistenceCoordinator) {
                    if (isCurrentOperation(localId, generation)) {
                        jobs.remove(localId)
                        records.remove(localId)
                        setState(localId, LocalRecordSaveStatus.DISCARDED)
                    }
                }
            } catch (cancelled: CancellationException) {
                synchronized(this@LocalGameRecordPersistenceCoordinator) {
                    if (isCurrentOperation(localId, generation)) {
                        jobs.remove(localId)
                        setState(localId, LocalRecordSaveStatus.DISCARD_FAILED, cancelled.message)
                    }
                }
                throw cancelled
            } catch (failure: Throwable) {
                synchronized(this@LocalGameRecordPersistenceCoordinator) {
                    if (isCurrentOperation(localId, generation)) {
                        jobs.remove(localId)
                        setState(localId, LocalRecordSaveStatus.DISCARD_FAILED, failure.message)
                    }
                }
            }
        }
        jobs[localId] = job
        job.start()
        job
    }

    fun retry(localId: String): Job? = synchronized(this) {
        when (states.value[localId]?.status) {
            LocalRecordSaveStatus.DISCARD_FAILED -> discard(localId)
            LocalRecordSaveStatus.FAILED,
            LocalRecordSaveStatus.PENDING -> records[localId]?.let(::enqueue)
            else -> null
        }
    }

    fun state(localId: String): LocalRecordSaveState? = saveStates.value[localId]

    fun pendingRecords(): List<LocalGameRecord> = recordsWithStatus(
        LocalRecordSaveStatus.PENDING,
        LocalRecordSaveStatus.SAVING,
        LocalRecordSaveStatus.DISCARDING,
    )

    fun failedRecords(): List<LocalGameRecord> = recordsWithStatus(
        LocalRecordSaveStatus.FAILED,
        LocalRecordSaveStatus.DISCARD_FAILED,
    )

    private fun recordsWithStatus(vararg statuses: LocalRecordSaveStatus): List<LocalGameRecord> = synchronized(this) {
        val accepted = statuses.toSet()
        records.values.filter { states.value[it.localId]?.status in accepted }
    }

    private fun setState(localId: String, status: LocalRecordSaveStatus, errorMessage: String? = null) {
        states.value = states.value + (localId to LocalRecordSaveState(localId, status, errorMessage))
    }

    private fun newOperationGeneration(localId: String): Long = (++nextOperationGeneration).also {
        operationGenerations[localId] = it
    }

    private fun isCurrentOperation(localId: String, generation: Long): Boolean =
        operationGenerations[localId] == generation
}

/**
 * Process-lifetime owner for formal local match records.
 * Activity destruction never cancels this scope; only process teardown does.
 */
class LocalGameRecordPersistenceProcessOwner(
    store: LocalGameRecordStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val processScope = CoroutineScope(SupervisorJob() + dispatcher)
    val coordinator = LocalGameRecordPersistenceCoordinator(store, processScope)

    override fun close() {
        processScope.cancel()
    }
}
