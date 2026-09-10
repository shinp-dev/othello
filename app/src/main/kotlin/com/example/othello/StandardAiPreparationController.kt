package com.example.othello

import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.analysis.api.StandardEvaluationDataSource
import com.example.othello.analysis.api.StandardEvaluationPreparationPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface StandardAiPreparationState {
    data object NotPrepared : StandardAiPreparationState
    data class Preparing(val phase: StandardEvaluationPreparationPhase) : StandardAiPreparationState
    data class Ready(val asset: StandardEvaluationAsset) : StandardAiPreparationState
    data class Failed(val cause: Throwable) : StandardAiPreparationState
}

internal class StandardAiPreparationController(
    private val source: StandardEvaluationDataSource,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<StandardAiPreparationState>(
        source.current()?.let(StandardAiPreparationState::Ready)
            ?: StandardAiPreparationState.NotPrepared,
    )
    val state: StateFlow<StandardAiPreparationState> = mutableState.asStateFlow()

    suspend fun prepare() = mutex.withLock {
        if (mutableState.value is StandardAiPreparationState.Ready) return@withLock
        if (!source.nativeAvailable) {
            mutableState.value = StandardAiPreparationState.Failed(
                IllegalStateException("Edax native library is unavailable"),
            )
            return@withLock
        }
        try {
            mutableState.value = StandardAiPreparationState.Preparing(
                StandardEvaluationPreparationPhase.DOWNLOADING,
            )
            val asset = source.prepare { phase ->
                mutableState.value = StandardAiPreparationState.Preparing(phase)
            }
            mutableState.value = StandardAiPreparationState.Ready(asset)
        } catch (cancelled: CancellationException) {
            mutableState.value = StandardAiPreparationState.NotPrepared
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = StandardAiPreparationState.Failed(failure)
        }
    }
}
