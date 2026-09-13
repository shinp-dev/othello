package com.example.othello

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface StandardBootstrapState {
    data object Preparing : StandardBootstrapState
    data class Ready(val content: StandardContentSnapshot) : StandardBootstrapState
    data class Failed(val cause: Throwable) : StandardBootstrapState
}

/** One Standard entry. AI failure is deliberately not an entry failure. */
internal class StandardBootstrapController(
    private val prepareContent: suspend () -> StandardContentSnapshot,
    private val aiPreparation: StandardAiPreparationController,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<StandardBootstrapState>(StandardBootstrapState.Preparing)
    val state = mutableState.asStateFlow()
    val aiState = aiPreparation.state

    suspend fun prepare() = mutex.withLock {
        if (mutableState.value is StandardBootstrapState.Ready) return@withLock
        mutableState.value = StandardBootstrapState.Preparing
        try {
            val content = withContext(dispatcher) {
                val snapshot = prepareContent()
                aiPreparation.prepare()
                snapshot
            }
            mutableState.value = StandardBootstrapState.Ready(content)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = StandardBootstrapState.Failed(failure)
        }
    }

    suspend fun retryAi() = withContext(dispatcher) {
        if (mutableState.value is StandardBootstrapState.Ready) aiPreparation.prepare()
    }
}
