package com.example.othello.analysis.edax

import com.example.othello.analysis.api.AnalysisAsset
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ProductionAnalysisEngineTest {
    @Test
    fun reviewAnalysisDefaultsAndTimeBoundsAreExplicit() {
        assertEquals(6, AnalysisSettings().level)
        assertEquals(2_000, AnalysisSettings().timePerCandidateMs)
        AnalysisSettings(level = 1, timePerCandidateMs = 500)
        AnalysisSettings(level = 18, timePerCandidateMs = 10_000)

        assertFailsWith<IllegalArgumentException> { AnalysisSettings(timePerCandidateMs = 499) }
        assertFailsWith<IllegalArgumentException> { AnalysisSettings(timePerCandidateMs = 501) }
        assertFailsWith<IllegalArgumentException> { AnalysisSettings(timePerCandidateMs = 10_001) }
    }

    @Test
    fun missingEvaluationDataNeverReturnsFakeScores() = runBlocking {
        val engine = ProductionAnalysisEngine(FakeGateway())
        val result = engine.analyze(ReviewPosition(GameState()), AnalysisSettings())

        assertFalse(result.available)
        assertTrue(result.evaluations.isEmpty())
        assertTrue(result.message.orEmpty().contains("評価データ"))
    }

    @Test
    fun boardSideLegalMovesAndResultKindsAreMapped() = runBlocking {
        val gateway = FakeGateway(
            moves = listOf(
                NativeMove(19, 6, EvaluationKind.EXACT, 3, 100),
                NativeMove(26, 2, EvaluationKind.HEURISTIC, 8, 73),
                NativeMove(37, -4, EvaluationKind.BOOK, null, 100),
                NativeMove(44, 0, EvaluationKind.HEURISTIC, 8, 100),
            ),
        )
        val engine = ProductionAnalysisEngine(gateway)
        val result = engine.analyze(ReviewPosition(GameState()), settings("eval-a"))

        assertTrue(result.available)
        assertEquals(setOf(19, 26, 37, 44), result.evaluations.map { it.move.index() }.toSet())
        assertEquals(0, gateway.side)
        assertEquals(2, gateway.player.countOneBits())
        assertEquals(2, gateway.opponent.countOneBits())
        assertEquals(null, gateway.bookPath)
        assertEquals(2_000, gateway.timePerCandidateMs)
        assertEquals(EvaluationKind.EXACT, result.evaluations.first().score.kind)
    }

    @Test
    fun arbitraryImportedBoardUsesTheExistingEdaxBoundary() = runBlocking {
        val board = Board.fromRows(listOf("WB......") + List(7) { "........" })
        val state = GameState(board = board, currentPlayer = Disc.WHITE)
        val gateway = FakeGateway(moves = listOf(NativeMove(2, 3, EvaluationKind.HEURISTIC, 4, 100)))

        val result = ProductionAnalysisEngine(gateway).analyze(ReviewPosition(state), settings("imported"))

        assertTrue(result.available)
        assertEquals(state.legalMoves, result.evaluations.map { it.move }.toSet())
        assertEquals(1, gateway.side)
        assertEquals(1, gateway.player.countOneBits())
        assertEquals(1, gateway.opponent.countOneBits())
    }

    @Test
    fun dataIdentityInvalidatesSmallMemoryCache() = runBlocking {
        val gateway = FakeGateway()
        val engine = ProductionAnalysisEngine(gateway)
        engine.analyze(ReviewPosition(GameState()), settings("eval-a"))
        engine.analyze(ReviewPosition(GameState()), settings("eval-a"))
        engine.analyze(ReviewPosition(GameState()), settings("eval-b"))

        assertEquals(2, gateway.calls.get())
    }

    @Test
    fun bookIdentityRemainsPartOfSmallMemoryCacheKey() = runBlocking {
        val gateway = FakeGateway()
        val engine = ProductionAnalysisEngine(gateway)

        engine.analyze(ReviewPosition(GameState()), settings("eval-a", bookIdentity = "book-a"))
        engine.analyze(ReviewPosition(GameState()), settings("eval-a", bookIdentity = "book-a"))
        engine.analyze(ReviewPosition(GameState()), settings("eval-a", bookIdentity = "book-b"))

        assertEquals(2, gateway.calls.get())
    }

    @Test
    fun candidateTimeIsPartOfCacheKeyAndIsPassedToGateway() = runBlocking {
        val gateway = FakeGateway()
        val engine = ProductionAnalysisEngine(gateway)

        engine.analyze(ReviewPosition(GameState()), settings("eval-a", timePerCandidateMs = 2_000))
        engine.analyze(ReviewPosition(GameState()), settings("eval-a", timePerCandidateMs = 2_000))
        engine.analyze(ReviewPosition(GameState()), settings("eval-a", timePerCandidateMs = 10_000))
        engine.analyze(ReviewPosition(GameState()), settings("eval-a", timePerCandidateMs = 10_000))

        assertEquals(2, gateway.calls.get())
        assertEquals(10_000, gateway.timePerCandidateMs)
    }

    @Test
    fun coroutineCancellationReachesNativeGateway() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val gateway = FakeGateway(onAnalyze = {
            entered.countDown()
            cancelled.await(5, TimeUnit.SECONDS)
            emptyList()
        }, onCancel = { cancelled.countDown() })
        val engine = ProductionAnalysisEngine(gateway)
        val job = launch(Dispatchers.Default) { engine.analyze(ReviewPosition(GameState()), settings("eval-a")) }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(gateway.cancelCalls.get() > 0)
    }

    @Test
    fun startingANewAnalysisCancelsThePreviousRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val gateway = FakeGateway(onAnalyze = {
            entered.countDown()
            released.await(5, TimeUnit.SECONDS)
            emptyList()
        }, onCancel = { released.countDown() })
        val engine = ProductionAnalysisEngine(gateway)
        val first = launch(Dispatchers.Default) { engine.analyze(ReviewPosition(GameState()), settings("eval-a")) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = launch(Dispatchers.Default) { engine.analyze(ReviewPosition(GameState()), settings("eval-b")) }
        repeat(50) {
            if (gateway.cancelCalls.get() > 0) return@repeat
            kotlinx.coroutines.delay(10)
        }
        assertTrue(gateway.cancelCalls.get() > 0)
        first.cancelAndJoin()
        second.cancelAndJoin()
    }

    @Test
    fun cancelledQueuedRequestsNeverRunOrOverwriteActiveNativeCancellation() = runBlocking {
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val blockerId = EdaxExecution.requestSequence.incrementAndGet()
        val blocker = launch(Dispatchers.Default) {
            EdaxExecution.executeCancellable(blockerId, { _ -> }) {
                blockerEntered.countDown()
                releaseBlocker.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS))

        val staleRuns = AtomicInteger()
        val queuedNativeCancels = AtomicInteger()
        suspend fun enqueueAndCancel(): Unit {
            val requestId = EdaxExecution.requestSequence.incrementAndGet()
            val request = launch(Dispatchers.Default) {
                EdaxExecution.executeCancellable(requestId, { queuedNativeCancels.incrementAndGet() }) {
                    staleRuns.incrementAndGet()
                }
            }
            awaitPendingRequest(requestId)
            request.cancelAndJoin()
            assertFalse(EdaxExecution.hasPendingRequest(requestId))
        }

        enqueueAndCancel()
        enqueueAndCancel()

        val latestRuns = AtomicInteger()
        val latestId = EdaxExecution.requestSequence.incrementAndGet()
        val latest = launch(Dispatchers.Default) {
            EdaxExecution.executeCancellable(latestId, { _ -> }) { latestRuns.incrementAndGet() }
        }
        awaitPendingRequest(latestId)
        releaseBlocker.countDown()
        blocker.join()
        latest.join()

        assertEquals(0, staleRuns.get())
        assertEquals(0, queuedNativeCancels.get())
        assertEquals(1, latestRuns.get())
    }

    @Test
    fun cancellationBeforeExecutorRegistrationPreventsTheRequestFromRunning() = runBlocking {
        val requestId = EdaxExecution.requestSequence.incrementAndGet()
        val runs = AtomicInteger()

        EdaxExecution.cancel(requestId)
        assertFailsWith<CancellationException> {
            EdaxExecution.executeCancellable(requestId, { _ -> }) { runs.incrementAndGet() }
        }
        EdaxExecution.forgetCancellation(requestId)

        assertEquals(0, runs.get())
    }

    private suspend fun awaitPendingRequest(requestId: Long) {
        repeat(100) {
            if (EdaxExecution.hasPendingRequest(requestId)) return
            kotlinx.coroutines.delay(10)
        }
        throw AssertionError("Edax request $requestId was not queued")
    }

    private fun settings(
        identity: String,
        timePerCandidateMs: Int = 2_000,
        bookIdentity: String? = null,
    ) = AnalysisSettings(
        timePerCandidateMs = timePerCandidateMs,
        evaluationData = EvaluationDataSource.Imported(AnalysisAsset("/private/eval-$identity.dat", identity)),
        bookSource = bookIdentity?.let {
            BookSource.ImportedBook(AnalysisAsset("/private/book-$it.dat", it))
        } ?: BookSource.None,
    )

    private class FakeGateway(
        private val moves: List<NativeMove> = listOf(
            NativeMove(19, 1, EvaluationKind.HEURISTIC, 8, 100),
            NativeMove(26, 1, EvaluationKind.HEURISTIC, 8, 100),
            NativeMove(37, 1, EvaluationKind.HEURISTIC, 8, 100),
            NativeMove(44, 1, EvaluationKind.HEURISTIC, 8, 100),
        ),
        private val onAnalyze: (() -> List<NativeMove>)? = null,
        private val onCancel: (() -> Unit)? = null,
    ) : EdaxGateway {
        override val available = true
        override val version = "Edax test"
        val calls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        var player = 0L
        var opponent = 0L
        var side = -1
        var timePerCandidateMs = -1
        var bookPath: String? = "unset"

        override fun validateEvaluationData(path: String): String? = null
        override fun validateBook(path: String): String? = null

        override fun analyze(
            player: Long,
            opponent: Long,
            side: Int,
            level: Int,
            timePerCandidateMs: Int,
            evaluationDataPath: String,
            bookPath: String?,
            requestId: Long,
        ): List<NativeMove> {
            calls.incrementAndGet()
            this.player = player
            this.opponent = opponent
            this.side = side
            this.timePerCandidateMs = timePerCandidateMs
            this.bookPath = bookPath
            return onAnalyze?.invoke() ?: moves
        }

        override fun cancel(requestId: Long) {
            cancelCalls.incrementAndGet()
            onCancel?.invoke()
        }
    }
}
