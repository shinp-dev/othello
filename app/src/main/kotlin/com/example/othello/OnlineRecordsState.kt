package com.example.othello

import com.example.othello.records.GameRecord
import com.example.othello.records.GameRecordRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal const val ONLINE_RECORDS_TIMEOUT_MILLIS: Long = 10_000L

internal enum class OnlineRecordsErrorKind {
    FETCH,
    TIMEOUT,
}

internal sealed interface OnlineRecordsState {
    data object Idle : OnlineRecordsState
    data object Loading : OnlineRecordsState
    data class Success(val records: List<GameRecord>) : OnlineRecordsState
    data object Empty : OnlineRecordsState
    data class Error(val kind: OnlineRecordsErrorKind) : OnlineRecordsState
}

internal suspend fun loadOnlineRecords(
    repository: GameRecordRepository,
    userId: String,
    limit: Int = 50,
    timeoutMillis: Long = ONLINE_RECORDS_TIMEOUT_MILLIS,
): OnlineRecordsState = try {
    val records = withTimeout(timeoutMillis) { repository.recent(userId, limit) }
    if (records.isEmpty()) OnlineRecordsState.Empty else OnlineRecordsState.Success(records)
} catch (_: TimeoutCancellationException) {
    OnlineRecordsState.Error(OnlineRecordsErrorKind.TIMEOUT)
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    OnlineRecordsState.Error(OnlineRecordsErrorKind.FETCH)
}
