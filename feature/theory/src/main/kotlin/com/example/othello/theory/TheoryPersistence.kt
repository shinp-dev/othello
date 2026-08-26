package com.example.othello.theory

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

object TheorySessionJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(snapshot: TheorySessionSnapshot): String = json.encodeToString(snapshot)

    fun decode(encoded: String): TheorySessionSnapshot = json.decodeFromString(encoded)
}

interface TheorySessionStore {
    suspend fun load(): TheorySessionSnapshot?
    suspend fun save(snapshot: TheorySessionSnapshot)
}

enum class TheorySessionSaveStatus { IDLE, SAVING, SAVED, FAILED }

data class TheorySessionSaveState(
    val status: TheorySessionSaveStatus = TheorySessionSaveStatus.IDLE,
    val errorMessage: String? = null,
)

/** Coalesces rapid tree edits while retaining the latest snapshot after a failed write. */
class TheorySessionPersistenceCoordinator(
    private val store: TheorySessionStore,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(TheorySessionSaveState())
    private var latest: TheorySessionSnapshot? = null
    private var worker: Job? = null

    val state: StateFlow<TheorySessionSaveState> = mutableState.asStateFlow()

    fun enqueue(snapshot: TheorySessionSnapshot): Job = synchronized(this) {
        latest = snapshot
        startWorkerLocked()
    }

    fun retry(): Job? = synchronized(this) {
        if (latest == null || worker?.isActive == true) null else startWorkerLocked()
    }

    private fun startWorkerLocked(): Job {
        worker?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch(start = CoroutineStart.LAZY) { drain() }
        worker = job
        job.start()
        return job
    }

    private suspend fun drain() {
        while (true) {
            val target = synchronized(this) { requireNotNull(latest) }
            mutableState.value = TheorySessionSaveState(TheorySessionSaveStatus.SAVING)
            try {
                store.save(target)
            } catch (cancelled: CancellationException) {
                synchronized(this) { worker = null }
                throw cancelled
            } catch (failure: Throwable) {
                synchronized(this) {
                    worker = null
                    mutableState.value = TheorySessionSaveState(
                        TheorySessionSaveStatus.FAILED,
                        failure.message,
                    )
                }
                return
            }

            val finished = synchronized(this) {
                if (latest == target) {
                    worker = null
                    mutableState.value = TheorySessionSaveState(TheorySessionSaveStatus.SAVED)
                    true
                } else {
                    false
                }
            }
            if (finished) return
        }
    }
}
