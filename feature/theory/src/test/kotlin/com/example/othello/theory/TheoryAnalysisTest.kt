package com.example.othello.theory

import com.example.othello.analysis.api.AnalysisAsset
import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.analysis.api.EvaluationPerspective
import com.example.othello.analysis.api.EvaluationScore
import com.example.othello.analysis.api.MoveEvaluation
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class TheoryAnalysisTest {
    @Test
    fun initialAndMovedPositionsAnalyzeAutomaticallyThenAUsesCache() = runBlocking {
        val cache = MemoryCache()
        val coordinator = TheoryAnalysisCoordinator(cache)
        val engine = CountingEngine()
        val settings = settings()
        val stateA = GameState()

        resolve(coordinator, stateA, settings, engine)
        val stateB = (stateA.play(stateA.legalMoves.first()) as MoveOutcome.Played).state
        resolve(coordinator, stateB, settings, engine)
        val returnedToA = stateA.copy(ply = 99)
        val cached = coordinator.begin(returnedToA, settings)

        assertIs<TheoryAnalysisStart.Cached>(cached)
        assertEquals(2, engine.analysisCount)
        assertEquals(2, cache.putCount)
    }

    @Test
    fun cacheKeyIncludesEveryResultChangingSettingButNotTreePath() {
        val state = GameState()
        val base = TheoryAnalysisCacheKey.from(state, settings())

        assertEquals(base, TheoryAnalysisCacheKey.from(state.copy(ply = 42), settings()))
        assertFalse(base == TheoryAnalysisCacheKey.from(state, settings().copy(level = 7)))
        assertFalse(base == TheoryAnalysisCacheKey.from(state, settings().copy(timePerCandidateMs = 2_500)))
        assertFalse(base == TheoryAnalysisCacheKey.from(state, settings(evaluation = "eval-b")))
        assertFalse(base == TheoryAnalysisCacheKey.from(state, settings(book = "book-b")))
    }

    @Test
    fun lateResultAfterNavigationIsNeitherDisplayedNorCached() = runBlocking {
        val cache = MemoryCache()
        val coordinator = TheoryAnalysisCoordinator(cache)
        val settings = settings()
        val stateA = GameState()
        val requestA = analyzeRequest(coordinator.begin(stateA, settings))
        val stateB = (stateA.play(stateA.legalMoves.first()) as MoveOutcome.Played).state
        val requestB = analyzeRequest(coordinator.begin(stateB, settings))

        assertEquals(
            TheoryAnalysisCompletion.STALE,
            coordinator.complete(requestA, stateB, settings, resultFor(stateA)),
        )
        assertEquals(0, cache.putCount)
        assertEquals(
            TheoryAnalysisCompletion.ACCEPTED,
            coordinator.complete(requestB, stateB, settings, resultFor(stateB)),
        )
        assertEquals(1, cache.putCount)
    }

    @Test
    fun returningToAStillRejectsTheFirstSlowARequest() = runBlocking {
        val cache = MemoryCache()
        val coordinator = TheoryAnalysisCoordinator(cache)
        val settings = settings()
        val stateA = GameState()
        val firstA = analyzeRequest(coordinator.begin(stateA, settings))
        val stateB = (stateA.play(stateA.legalMoves.first()) as MoveOutcome.Played).state
        analyzeRequest(coordinator.begin(stateB, settings))
        val latestA = analyzeRequest(coordinator.begin(stateA, settings))

        assertEquals(
            TheoryAnalysisCompletion.STALE,
            coordinator.complete(firstA, stateA, settings, resultFor(stateA)),
        )
        assertEquals(0, cache.putCount)
        assertEquals(
            TheoryAnalysisCompletion.ACCEPTED,
            coordinator.complete(latestA, stateA, settings, resultFor(stateA)),
        )
    }

    @Test
    fun invalidatedCancelledAndFailedResultsAreNeverCached() = runBlocking {
        val cache = MemoryCache()
        val coordinator = TheoryAnalysisCoordinator(cache)
        val state = GameState()
        val settings = settings()
        val cancelled = analyzeRequest(coordinator.begin(state, settings))
        coordinator.invalidate()

        assertEquals(
            TheoryAnalysisCompletion.STALE,
            coordinator.complete(cancelled, state, settings, resultFor(state)),
        )
        val failed = analyzeRequest(coordinator.begin(state, settings))
        assertEquals(
            TheoryAnalysisCompletion.FAILED,
            coordinator.complete(failed, state, settings, AnalysisResult(emptyList(), available = false)),
        )
        assertEquals(0, cache.putCount)
        assertNull(cache.get(TheoryAnalysisCacheKey.from(state, settings)))
    }

    @Test
    fun incompleteResultCannotEnterCache() = runBlocking {
        val cache = MemoryCache()
        val coordinator = TheoryAnalysisCoordinator(cache)
        val state = GameState()
        val settings = settings()
        val request = analyzeRequest(coordinator.begin(state, settings))

        assertEquals(
            TheoryAnalysisCompletion.FAILED,
            coordinator.complete(
                request,
                state,
                settings,
                AnalysisResult(resultFor(state).evaluations.drop(1)),
            ),
        )
        assertEquals(0, cache.putCount)
    }

    @Test
    fun settingsChangeBeforeCompletionRejectsAndDoesNotCacheOldResult() = runBlocking {
        val cache = MemoryCache()
        val coordinator = TheoryAnalysisCoordinator(cache)
        val state = GameState()
        val originalSettings = settings()
        val request = analyzeRequest(coordinator.begin(state, originalSettings))

        assertEquals(
            TheoryAnalysisCompletion.STALE,
            coordinator.complete(
                request,
                state,
                originalSettings.copy(level = originalSettings.level + 1),
                resultFor(state),
            ),
        )
        assertEquals(0, cache.putCount)
    }

    @Test
    fun navigationDuringAcceptedCacheWriteRemovesOnlyTheStaleWrite() = runBlocking {
        val cache = BlockingPutCache()
        val coordinator = TheoryAnalysisCoordinator(cache)
        val settings = settings()
        val stateA = GameState()
        val keyA = TheoryAnalysisCacheKey.from(stateA, settings)
        val requestA = analyzeRequest(coordinator.begin(stateA, settings))
        val completion = async {
            coordinator.complete(requestA, stateA, settings, resultFor(stateA))
        }
        cache.putStarted.await()
        val stateB = (stateA.play(stateA.legalMoves.first()) as MoveOutcome.Played).state
        analyzeRequest(coordinator.begin(stateB, settings))
        cache.releasePut.complete(Unit)

        assertEquals(TheoryAnalysisCompletion.STALE, completion.await())
        assertFalse(coordinator.isCurrent(requestA, stateB, settings))
        assertNull(cache.get(keyA))
        assertEquals(1, cache.removeOwnedCount)
    }

    @Test
    fun rapidNavigationAcceptsOnlyTheFinalPosition() = runBlocking {
        val cache = MemoryCache()
        val coordinator = TheoryAnalysisCoordinator(cache)
        val settings = settings()
        val stateA = GameState()
        val stateB = (stateA.play(stateA.legalMoves.first()) as MoveOutcome.Played).state
        val requests = buildList {
            repeat(20) {
                add(analyzeRequest(coordinator.begin(stateA, settings)))
                add(analyzeRequest(coordinator.begin(stateB, settings)))
            }
        }

        requests.dropLast(1).forEach { request ->
            assertEquals(
                TheoryAnalysisCompletion.STALE,
                coordinator.complete(request, stateB, settings, resultFor(request.state)),
            )
        }
        assertEquals(
            TheoryAnalysisCompletion.ACCEPTED,
            coordinator.complete(requests.last(), stateB, settings, resultFor(stateB)),
        )
        assertEquals(1, cache.putCount)
    }

    @Test
    fun terminalPositionDoesNotReadCacheOrStartEngine() = runBlocking {
        val cache = MemoryCache()
        val terminal = GameState(
            board = Board.fromRows(List(Board.SIZE) { "BBBBBBBB" }),
            currentPlayer = Disc.BLACK,
            consecutivePasses = 2,
        )

        assertEquals(
            TheoryAnalysisStart.NoLegalMoves,
            TheoryAnalysisCoordinator(cache).begin(terminal, settings()),
        )
        assertEquals(0, cache.getCount)
    }

    @Test
    fun cacheJsonRoundTripKeepsKeyAndScoreMetadata() {
        val state = GameState()
        val key = TheoryAnalysisCacheKey.from(state, settings())
        val result = resultFor(state)

        val decoded = TheoryAnalysisCacheJson.decode(TheoryAnalysisCacheJson.encode(key, result), key)

        assertEquals(result, decoded)
    }

    private suspend fun resolve(
        coordinator: TheoryAnalysisCoordinator,
        state: GameState,
        settings: AnalysisSettings,
        engine: CountingEngine,
    ): AnalysisResult = when (val start = coordinator.begin(state, settings)) {
        is TheoryAnalysisStart.Cached -> start.result
        is TheoryAnalysisStart.Analyze -> start.request.execute(engine).also { result ->
            assertEquals(
                TheoryAnalysisCompletion.ACCEPTED,
                coordinator.complete(start.request, state, settings, result),
            )
        }
        TheoryAnalysisStart.NoLegalMoves -> AnalysisResult(emptyList())
        TheoryAnalysisStart.Stale -> error("unexpected stale start")
    }

    private fun analyzeRequest(start: TheoryAnalysisStart): TheoryAnalysisRequest =
        assertIs<TheoryAnalysisStart.Analyze>(start).request

    private fun settings(
        evaluation: String = "eval-a",
        book: String = "book-a",
    ): AnalysisSettings = AnalysisSettings(
        level = 6,
        timePerCandidateMs = 2_000,
        evaluationData = EvaluationDataSource.Imported(AnalysisAsset("/eval.dat", evaluation)),
        bookSource = BookSource.ImportedBook(AnalysisAsset("/book.dat", book)),
    )

    private fun resultFor(state: GameState): AnalysisResult = AnalysisResult(
        evaluations = state.legalMoves.mapIndexed { index, move ->
            MoveEvaluation(
                move = move,
                score = EvaluationScore(
                    value = index - 2,
                    perspective = EvaluationPerspective.SIDE_TO_MOVE,
                    kind = EvaluationKind.HEURISTIC,
                    searchedDepth = 6,
                    selectivityPercent = 100,
                ),
            )
        },
    )

    private inner class CountingEngine : AnalysisEngine {
        var analysisCount = 0

        override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult {
            analysisCount++
            return resultFor(position.state)
        }
    }

    private class MemoryCache : TheoryAnalysisCache {
        private val entries = mutableMapOf<TheoryAnalysisCacheKey, AnalysisResult>()
        private val owners = mutableMapOf<TheoryAnalysisCacheKey, TheoryAnalysisCacheWriteToken>()
        private var nextToken = 0L
        var getCount = 0
        var putCount = 0

        override suspend fun get(key: TheoryAnalysisCacheKey): AnalysisResult? {
            getCount++
            return entries[key]
        }

        override suspend fun put(
            key: TheoryAnalysisCacheKey,
            result: AnalysisResult,
        ): TheoryAnalysisCacheWriteToken {
            putCount++
            entries[key] = result
            return TheoryAnalysisCacheWriteToken((++nextToken).toString()).also { owners[key] = it }
        }

        override suspend fun remove(key: TheoryAnalysisCacheKey) {
            entries.remove(key)
            owners.remove(key)
        }

        override suspend fun removeIfOwned(
            key: TheoryAnalysisCacheKey,
            token: TheoryAnalysisCacheWriteToken,
        ) {
            if (owners[key] == token) {
                entries.remove(key)
                owners.remove(key)
            }
        }

        override suspend fun clear() {
            entries.clear()
            owners.clear()
        }
    }

    private class BlockingPutCache : TheoryAnalysisCache {
        val putStarted = CompletableDeferred<Unit>()
        val releasePut = CompletableDeferred<Unit>()
        private val entries = mutableMapOf<TheoryAnalysisCacheKey, AnalysisResult>()
        private val owners = mutableMapOf<TheoryAnalysisCacheKey, TheoryAnalysisCacheWriteToken>()
        var removeOwnedCount = 0

        override suspend fun get(key: TheoryAnalysisCacheKey): AnalysisResult? = entries[key]

        override suspend fun put(
            key: TheoryAnalysisCacheKey,
            result: AnalysisResult,
        ): TheoryAnalysisCacheWriteToken {
            putStarted.complete(Unit)
            releasePut.await()
            entries[key] = result
            return TheoryAnalysisCacheWriteToken("blocking-write").also { owners[key] = it }
        }

        override suspend fun remove(key: TheoryAnalysisCacheKey) {
            entries.remove(key)
            owners.remove(key)
        }

        override suspend fun removeIfOwned(
            key: TheoryAnalysisCacheKey,
            token: TheoryAnalysisCacheWriteToken,
        ) {
            if (owners[key] == token) {
                entries.remove(key)
                owners.remove(key)
                removeOwnedCount++
            }
        }

        override suspend fun clear() {
            entries.clear()
            owners.clear()
        }
    }
}
