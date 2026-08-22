package com.example.othello.analysis.edax

import com.example.othello.analysis.api.AiMoveSettings
import com.example.othello.analysis.api.AnalysisAsset
import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import com.example.othello.game.GameState
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Test

class ProductionAiMoveEngineTest {
    @Test
    fun aiSettingsDefaultsAndBoundariesAreEnforced() {
        assertEquals(1, AiMoveSettings().level)
        assertEquals(2_000, AiMoveSettings().moveTimeMs)
        AiMoveSettings(level = 1, moveTimeMs = 500)
        AiMoveSettings(level = 8, moveTimeMs = 10_000)
        assertFailsWith<IllegalArgumentException> { AiMoveSettings(level = 0) }
        assertFailsWith<IllegalArgumentException> { AiMoveSettings(level = 9) }
        assertFailsWith<IllegalArgumentException> { AiMoveSettings(moveTimeMs = 499) }
        assertFailsWith<IllegalArgumentException> { AiMoveSettings(moveTimeMs = 10_001) }
    }

    @Test
    fun dedicatedGatewayReceivesAiLevelMoveTimeAndSharedAssets() = runBlocking {
        val position = GameState()
        val expected = position.legalMoves.last().index()
        val gateway = FakeAiMoveGateway(expected)
        val result = ProductionAiMoveEngine(gateway).chooseBestMove(
            position,
            settings(level = 8, moveTimeMs = 10_000, withBook = true),
        )

        assertTrue(result.available)
        assertEquals(expected, result.move?.index())
        assertEquals(8, gateway.level)
        assertEquals(10_000, gateway.moveTimeMs)
        assertEquals("/private/shared-eval.dat", gateway.evaluationDataPath)
        assertEquals("/private/shared-book.dat", gateway.bookPath)
        assertEquals(1, gateway.calls.get())
    }

    @Test
    fun illegalOrMissingNativeMoveNeverReachesTheMatch() = runBlocking {
        val illegal = ProductionAiMoveEngine(FakeAiMoveGateway(0))
            .chooseBestMove(GameState(), settings())
        assertFalse(illegal.available)
        assertNull(illegal.move)
        assertTrue(illegal.message.orEmpty().contains("illegal"))

        val missingEvaluation = ProductionAiMoveEngine(FakeAiMoveGateway(19))
            .chooseBestMove(GameState(), AiMoveSettings())
        assertFalse(missingEvaluation.available)
        assertNull(missingEvaluation.move)
    }

    @Test
    fun explicitNativeCancellationIsNotReturnedAsAMove() = runBlocking {
        assertFailsWith<CancellationException> {
            ProductionAiMoveEngine(FakeAiMoveGateway(-1)).chooseBestMove(GameState(), settings())
        }
        Unit
    }

    @Test
    fun coroutineCancellationReachesDedicatedNativeGateway() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val gateway = object : AiMoveGateway {
            override val available = true

            override fun chooseBestMove(
                player: Long,
                opponent: Long,
                side: Int,
                level: Int,
                moveTimeMs: Int,
                evaluationDataPath: String,
                bookPath: String?,
                requestId: Long,
            ): Int {
                entered.countDown()
                cancelled.await(5, TimeUnit.SECONDS)
                return -1
            }

            override fun cancel(requestId: Long) {
                cancelled.countDown()
            }
        }
        val engine = ProductionAiMoveEngine(gateway)
        val job = launch(Dispatchers.Default) { engine.chooseBestMove(GameState(), settings()) }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertEquals(0L, cancelled.count)
    }

    private fun settings(
        level: Int = 1,
        moveTimeMs: Int = 2_000,
        withBook: Boolean = false,
    ) = AiMoveSettings(
        level = level,
        moveTimeMs = moveTimeMs,
        evaluationData = EvaluationDataSource.Imported(
            AnalysisAsset("/private/shared-eval.dat", "shared-eval"),
        ),
        bookSource = if (withBook) {
            BookSource.ImportedBook(AnalysisAsset("/private/shared-book.dat", "shared-book"))
        } else {
            BookSource.None
        },
    )

    private class FakeAiMoveGateway(
        private val square: Int,
    ) : AiMoveGateway {
        override val available = true
        val calls = AtomicInteger()
        var level = -1
        var moveTimeMs = -1
        var evaluationDataPath: String? = null
        var bookPath: String? = null

        override fun chooseBestMove(
            player: Long,
            opponent: Long,
            side: Int,
            level: Int,
            moveTimeMs: Int,
            evaluationDataPath: String,
            bookPath: String?,
            requestId: Long,
        ): Int {
            calls.incrementAndGet()
            this.level = level
            this.moveTimeMs = moveTimeMs
            this.evaluationDataPath = evaluationDataPath
            this.bookPath = bookPath
            return square
        }

        override fun cancel(requestId: Long) = Unit
    }
}
