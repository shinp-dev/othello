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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LocalRecordSaveStatus { PENDING, SAVING, SAVED, FAILED }

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

    val saveStates: StateFlow<Map<String, LocalRecordSaveState>> = states.asStateFlow()

    fun enqueue(record: LocalGameRecord): Job? = synchronized(this) {
        val existing = records[record.localId]
        require(existing == null || existing == record) { "localId collision: ${record.localId}" }
        records.putIfAbsent(record.localId, record)
        val current = states.value[record.localId]
        if (current?.status == LocalRecordSaveStatus.SAVED) return@synchronized null
        jobs[record.localId]?.takeIf { it.isActive }?.let { return@synchronized it }

        setState(record.localId, LocalRecordSaveStatus.SAVING)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                store.save(record)
                synchronized(this@LocalGameRecordPersistenceCoordinator) {
                    jobs.remove(record.localId)
                    setState(record.localId, LocalRecordSaveStatus.SAVED)
                }
            } catch (cancelled: CancellationException) {
                synchronized(this@LocalGameRecordPersistenceCoordinator) {
                    jobs.remove(record.localId)
                    setState(record.localId, LocalRecordSaveStatus.PENDING, cancelled.message)
                }
                throw cancelled
            } catch (failure: Throwable) {
                synchronized(this@LocalGameRecordPersistenceCoordinator) {
                    jobs.remove(record.localId)
                    setState(record.localId, LocalRecordSaveStatus.FAILED, failure.message)
                }
            }
        }
        jobs[record.localId] = job
        job.start()
        job
    }

    fun retry(localId: String): Job? = synchronized(this) {
        records[localId]?.let(::enqueue)
    }

    fun state(localId: String): LocalRecordSaveState? = saveStates.value[localId]

    fun pendingRecords(): List<LocalGameRecord> = recordsWithStatus(
        LocalRecordSaveStatus.PENDING,
        LocalRecordSaveStatus.SAVING,
    )

    fun failedRecords(): List<LocalGameRecord> = recordsWithStatus(LocalRecordSaveStatus.FAILED)

    private fun recordsWithStatus(vararg statuses: LocalRecordSaveStatus): List<LocalGameRecord> = synchronized(this) {
        val accepted = statuses.toSet()
        records.values.filter { states.value[it.localId]?.status in accepted }
    }

    private fun setState(localId: String, status: LocalRecordSaveStatus, errorMessage: String? = null) {
        states.value = states.value + (localId to LocalRecordSaveState(localId, status, errorMessage))
    }
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
