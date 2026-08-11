package com.example.othello.analysis.edax

import com.example.othello.analysis.api.AnalysisAsset
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.EvaluationDataSource
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.GameState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ProductionAnalysisEngineTest {
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
        assertEquals(EvaluationKind.EXACT, result.evaluations.first().score.kind)
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

    private fun settings(identity: String) = AnalysisSettings(
        evaluationData = EvaluationDataSource.Imported(AnalysisAsset("/private/eval-$identity.dat", identity)),
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
        var bookPath: String? = "unset"

        override fun validateEvaluationData(path: String): String? = null
        override fun validateBook(path: String): String? = null

        override fun analyze(
            player: Long,
            opponent: Long,
            side: Int,
            level: Int,
            evaluationDataPath: String,
            bookPath: String?,
            requestId: Long,
        ): List<NativeMove> {
            calls.incrementAndGet()
            this.player = player
            this.opponent = opponent
            this.side = side
            this.bookPath = bookPath
            return onAnalyze?.invoke() ?: moves
        }

        override fun cancel(requestId: Long) {
            cancelCalls.incrementAndGet()
            onCancel?.invoke()
        }
    }
}
