package com.example.othello.review

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PositionReviewAnalysisCoordinatorTest {
    @Test
    fun automaticAnalysisFollowsSessionNavigationAndReusesCachedPositions() = runBlocking {
        val coordinator = PositionReviewAnalysisCoordinator()
        val settings = settings()
        val engine = CountingAnalysisEngine()
        val session = PositionReviewSession(Board.initial(), Disc.BLACK)
        val initial = session.current

        resolve(coordinator, session.current, settings, engine)
        assertEquals(1, engine.analysisCount)
        assertEquals(initial, engine.positions.single())

        assertTrue(session.play(session.current.legalMoves.first()))
        val afterMove = session.current
        resolve(coordinator, session.current, settings, engine)
        assertEquals(2, engine.analysisCount)

        session.previous()
        assertIs<PositionReviewAnalysisStart.Cached>(coordinator.begin(session.current, settings))
        assertEquals(2, engine.analysisCount)

        session.next()
        assertEquals(afterMove, session.current)
        assertIs<PositionReviewAnalysisStart.Cached>(coordinator.begin(session.current, settings))
        assertEquals(2, engine.analysisCount)

        session.reset()
        assertEquals(initial, session.current)
        assertIs<PositionReviewAnalysisStart.Cached>(coordinator.begin(session.current, settings))
        assertEquals(2, engine.analysisCount)
    }

    @Test
    fun cacheRetains64EntriesAndEvictsLeastRecentlyUsedEntry() {
        assertEquals(64, PositionReviewAnalysisCoordinator.DEFAULT_CACHE_CAPACITY)
        val cache = PositionReviewAnalysisLruCache<Int, String>(64)
        repeat(64) { cache[it] = "result-$it" }

        assertEquals(64, cache.size)
        cache[64] = "result-64"

        assertEquals(64, cache.size)
        assertFalse(cache.contains(0))
        assertTrue(cache.contains(1))
        assertTrue(cache.contains(64))
    }

    @Test
    fun recentlyAccessedOldEntrySurvivesLruEviction() {
        val cache = PositionReviewAnalysisLruCache<Int, String>(64)
        repeat(64) { cache[it] = "result-$it" }

        assertEquals("result-0", cache[0])
        cache[64] = "result-64"

        assertTrue(cache.contains(0))
        assertFalse(cache.contains(1))
        assertTrue(cache.contains(64))
    }

    @Test
    fun everyAnalysisConfigurationChangeClearsOldResults() {
        val base = settings()
        val variants = listOf(
            base.copy(level = base.level + 1),
            base.copy(timePerCandidateMs = base.timePerCandidateMs + 500),
            settings(evaluationIdentity = "eval-b"),
            settings(bookIdentity = "book-b"),
        )
        val state = GameState()

        variants.forEach { changed ->
            val coordinator = PositionReviewAnalysisCoordinator()
            val request = analyzeRequest(coordinator.begin(state, base))
            assertEquals(
                PositionReviewAnalysisCompletion.ACCEPTED,
                coordinator.complete(request, state, base, resultFor(state)),
            )
            assertEquals(1, coordinator.cachedEntryCount())

            assertIs<PositionReviewAnalysisStart.Analyze>(coordinator.begin(state, changed))
            assertEquals(0, coordinator.cachedEntryCount())
        }
    }

    @Test
    fun positionChangeRejectsLateResultAndDoesNotCacheIt() {
        val coordinator = PositionReviewAnalysisCoordinator()
        val settings = settings()
        val session = PositionReviewSession(Board.initial(), Disc.BLACK)
        val initialRequest = analyzeRequest(coordinator.begin(session.current, settings))

        assertTrue(session.play(session.current.legalMoves.first()))
        val current = session.current
        val currentRequest = analyzeRequest(coordinator.begin(current, settings))

        assertEquals(
            PositionReviewAnalysisCompletion.STALE,
            coordinator.complete(initialRequest, current, settings, resultFor(initialRequest.state)),
        )
        assertEquals(0, coordinator.cachedEntryCount())
        assertEquals(
            PositionReviewAnalysisCompletion.ACCEPTED,
            coordinator.complete(currentRequest, current, settings, resultFor(current)),
        )
        assertIs<PositionReviewAnalysisStart.Cached>(coordinator.begin(current, settings))
    }

    @Test
    fun requestExecutesThePositionCapturedBeforeSessionNavigation() = runBlocking {
        val coordinator = PositionReviewAnalysisCoordinator()
        val settings = settings()
        val session = PositionReviewSession(Board.initial(), Disc.BLACK)
        val requestedState = session.current
        val request = analyzeRequest(coordinator.begin(requestedState, settings))
        val engine = CountingAnalysisEngine()

        assertTrue(session.play(session.current.legalMoves.first()))
        request.execute(engine)

        assertEquals(listOf(requestedState), engine.positions)
        assertEquals(1, engine.analysisCount)
    }

    @Test
    fun returningToAStillRejectsTheFirstLateARequest() {
        val coordinator = PositionReviewAnalysisCoordinator()
        val settings = settings()
        val session = PositionReviewSession(Board.initial(), Disc.BLACK)
        val stateA = session.current
        val firstA = analyzeRequest(coordinator.begin(stateA, settings))

        assertTrue(session.play(session.current.legalMoves.first()))
        val stateB = session.current
        val requestB = analyzeRequest(coordinator.begin(stateB, settings))
        session.previous()
        val latestA = analyzeRequest(coordinator.begin(session.current, settings))

        assertEquals(
            PositionReviewAnalysisCompletion.STALE,
            coordinator.complete(firstA, session.current, settings, resultFor(stateA)),
        )
        assertEquals(
            PositionReviewAnalysisCompletion.STALE,
            coordinator.complete(requestB, session.current, settings, resultFor(stateB)),
        )
        assertEquals(0, coordinator.cachedEntryCount())
        assertEquals(
            PositionReviewAnalysisCompletion.ACCEPTED,
            coordinator.complete(latestA, session.current, settings, resultFor(stateA)),
        )
    }

    @Test
    fun invalidatedAndFailedRequestsAreNeverCached() {
        val state = GameState()
        val settings = settings()

        val cancelledCoordinator = PositionReviewAnalysisCoordinator()
        val cancelled = analyzeRequest(cancelledCoordinator.begin(state, settings))
        cancelledCoordinator.invalidate()
        assertEquals(
            PositionReviewAnalysisCompletion.STALE,
            cancelledCoordinator.complete(cancelled, state, settings, resultFor(state)),
        )
        assertEquals(0, cancelledCoordinator.cachedEntryCount())
        assertIs<PositionReviewAnalysisStart.Analyze>(cancelledCoordinator.begin(state, settings))

        val failedCoordinator = PositionReviewAnalysisCoordinator()
        val failed = analyzeRequest(failedCoordinator.begin(state, settings))
        assertEquals(
            PositionReviewAnalysisCompletion.FAILED,
            failedCoordinator.complete(
                failed,
                state,
                settings,
                AnalysisResult(emptyList(), available = false, message = "failed"),
            ),
        )
        assertEquals(0, failedCoordinator.cachedEntryCount())
        assertIs<PositionReviewAnalysisStart.Analyze>(failedCoordinator.begin(state, settings))
    }

    @Test
    fun rapidNavigationAcceptsOnlyTheFinalRequest() {
        val coordinator = PositionReviewAnalysisCoordinator()
        val settings = settings()
        val session = PositionReviewSession(Board.initial(), Disc.BLACK)
        val requests = mutableListOf<PositionReviewAnalysisRequest>()
        requests += analyzeRequest(coordinator.begin(session.current, settings))
        assertTrue(session.play(session.current.legalMoves.first()))
        requests += analyzeRequest(coordinator.begin(session.current, settings))

        repeat(10) {
            session.previous()
            requests += analyzeRequest(coordinator.begin(session.current, settings))
            session.next()
            requests += analyzeRequest(coordinator.begin(session.current, settings))
        }

        val finalRequest = requests.last()
        requests.dropLast(1).forEach { request ->
            assertEquals(
                PositionReviewAnalysisCompletion.STALE,
                coordinator.complete(request, session.current, settings, resultFor(request.state)),
            )
        }
        assertEquals(
            PositionReviewAnalysisCompletion.ACCEPTED,
            coordinator.complete(finalRequest, session.current, settings, resultFor(finalRequest.state)),
        )
        assertIs<PositionReviewAnalysisStart.Cached>(coordinator.begin(session.current, settings))
    }

    @Test
    fun positionWithoutLegalMovesDoesNotStartAnalysis() {
        val finished = GameState(
            board = Board.fromRows(List(Board.SIZE) { "BBBBBBBB" }),
            currentPlayer = Disc.BLACK,
            consecutivePasses = 2,
        )

        assertEquals(
            PositionReviewAnalysisStart.NoLegalMoves,
            PositionReviewAnalysisCoordinator().begin(finished, settings()),
        )
    }

    private suspend fun resolve(
        coordinator: PositionReviewAnalysisCoordinator,
        state: GameState,
        settings: AnalysisSettings,
        engine: CountingAnalysisEngine,
    ): AnalysisResult = when (val start = coordinator.begin(state, settings)) {
        is PositionReviewAnalysisStart.Cached -> start.result
        is PositionReviewAnalysisStart.Analyze -> start.request.execute(engine).also { result ->
            assertEquals(
                PositionReviewAnalysisCompletion.ACCEPTED,
                coordinator.complete(start.request, state, settings, result),
            )
        }
        PositionReviewAnalysisStart.NoLegalMoves -> AnalysisResult(emptyList())
    }

    private fun analyzeRequest(start: PositionReviewAnalysisStart): PositionReviewAnalysisRequest =
        assertIs<PositionReviewAnalysisStart.Analyze>(start).request

    private fun settings(
        evaluationIdentity: String = "eval-a",
        bookIdentity: String = "book-a",
    ): AnalysisSettings = AnalysisSettings(
        level = 6,
        timePerCandidateMs = 2_000,
        evaluationData = EvaluationDataSource.Imported(AnalysisAsset("/eval.dat", evaluationIdentity)),
        bookSource = BookSource.ImportedBook(AnalysisAsset("/book.dat", bookIdentity)),
    )

    private fun resultFor(state: GameState): AnalysisResult = AnalysisResult(
        evaluations = state.legalMoves.mapIndexed { index, move ->
            MoveEvaluation(
                move,
                EvaluationScore(
                    value = index,
                    perspective = EvaluationPerspective.SIDE_TO_MOVE,
                    kind = EvaluationKind.HEURISTIC,
                ),
            )
        },
    )

    private inner class CountingAnalysisEngine : AnalysisEngine {
        var analysisCount = 0
        val positions = mutableListOf<GameState>()

        override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult {
            analysisCount++
            positions += position.state
            return resultFor(position.state)
        }
    }
}
