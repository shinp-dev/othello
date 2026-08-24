package com.example.othello

import com.example.othello.game.Position
import com.example.othello.records.FinishReason
import com.example.othello.records.GameRecord
import com.example.othello.records.GameRecordRepository
import com.example.othello.records.MatchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class OnlineRecordsStateTest {
    private val record = GameRecord(
        matchId = "match",
        players = listOf("black", "white"),
        moves = listOf(Position(2, 3)),
        result = MatchResult.BLACK_WIN,
        startedAtEpochMillis = 0,
        finishedAtEpochMillis = 60_000,
        timeControl = "5m",
        finishReason = FinishReason.NORMAL,
    )

    @Test
    fun loadingToSuccessIsExplicit() = runBlocking {
        assertEquals(
            OnlineRecordsState.Success(listOf(record)),
            loadOnlineRecords(FakeRepository { listOf(record) }, "user"),
        )
    }

    @Test
    fun loadingToEmptyIsExplicit() = runBlocking {
        assertEquals(
            OnlineRecordsState.Empty,
            loadOnlineRecords(FakeRepository { emptyList() }, "user"),
        )
    }

    @Test
    fun loadingToErrorIsExplicit() = runBlocking {
        assertEquals(
            OnlineRecordsState.Error(OnlineRecordsErrorKind.FETCH),
            loadOnlineRecords(FakeRepository { error("server unavailable") }, "user"),
        )
    }

    @Test
    fun timeoutBecomesError() = runBlocking {
        assertEquals(
            OnlineRecordsState.Error(OnlineRecordsErrorKind.TIMEOUT),
            loadOnlineRecords(FakeRepository { delay(Long.MAX_VALUE); emptyList() }, "user", timeoutMillis = 20),
        )
    }

    @Test
    fun retryCanRecoverFromError() = runBlocking {
        var attempt = 0
        val repository = FakeRepository {
            attempt += 1
            if (attempt == 1) error("temporary") else listOf(record)
        }

        assertEquals(OnlineRecordsState.Error(OnlineRecordsErrorKind.FETCH), loadOnlineRecords(repository, "user"))
        assertEquals(OnlineRecordsState.Success(listOf(record)), loadOnlineRecords(repository, "user"))
    }

    @Test
    fun cancellationDoesNotBecomeUserVisibleError() = runBlocking {
        val state = CompletableDeferred<OnlineRecordsState>()
        val job = launch {
            try {
                state.complete(loadOnlineRecords(FakeRepository { delay(Long.MAX_VALUE); emptyList() }, "user"))
            } catch (_: CancellationException) {
                // Screen cancellation is intentionally not converted to Error.
            }
        }
        yield()
        job.cancel()
        job.join()

        assertTrue(!state.isCompleted)
    }

    @Test
    fun errorAndLoadingAreDifferentExclusiveStates() {
        val error: OnlineRecordsState = OnlineRecordsState.Error(OnlineRecordsErrorKind.FETCH)
        val loading: OnlineRecordsState = OnlineRecordsState.Loading

        assertEquals(OnlineRecordsState.Error::class, error::class)
        assertEquals(OnlineRecordsState.Loading::class, loading::class)
    }

    private class FakeRepository(private val load: suspend () -> List<GameRecord>) : GameRecordRepository {
        override suspend fun recent(userId: String, limit: Int): List<GameRecord> = load()

        override suspend fun get(matchId: String): GameRecord = error("unused")
    }
}
