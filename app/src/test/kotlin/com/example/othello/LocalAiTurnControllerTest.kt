package com.example.othello

import com.example.othello.analysis.api.AiMoveEngine
import com.example.othello.analysis.api.AiMoveResult
import com.example.othello.analysis.api.AiMoveSettings
import com.example.othello.game.GameState
import com.example.othello.game.Disc
import com.example.othello.game.Position
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class LocalAiTurnControllerTest {
    @Test
    fun passesAiLevelAndWholeMoveTimeToDedicatedEngine() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val engine = FakeEngine()

        assertTrue(LocalAiTurnController(match, engine).play(AiMoveSettings(level = 8, moveTimeMs = 10_000)))

        assertEquals(8, engine.lastSettings?.level)
        assertEquals(10_000, engine.lastSettings?.moveTimeMs)
        assertEquals(engine.chosenMove, match.viewState.moves.single())
        assertTrue(match.viewState.moves.single() in match.viewState.game.board.positionsOf(Disc.BLACK))
    }

    @Test
    fun aiWhiteUsesOnlyLegalMoves() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        val engine = FakeEngine()
        assertTrue(match.play(Position(2, 3)))

        assertTrue(LocalAiTurnController(match, engine).play(AiMoveSettings(level = 1, moveTimeMs = 500)))
        assertEquals(engine.chosenMove, match.viewState.moves.last())
        assertTrue(match.viewState.moves.last() in match.viewState.game.board.positionsOf(Disc.WHITE))
    }

    @Test
    fun unavailableAndFailedMoveSearchDoNotChangeTheGame() = runBlocking {
        val unavailableMatch = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val unavailable = object : AiMoveEngine {
            override suspend fun chooseBestMove(position: GameState, settings: AiMoveSettings) =
                AiMoveResult(move = null, available = false, message = "unavailable")
        }
        assertFalse(LocalAiTurnController(unavailableMatch, unavailable).play(AiMoveSettings()))
        assertTrue(unavailableMatch.viewState.moves.isEmpty())
        assertFalse(unavailableMatch.viewState.aiThinking)

        val failedMatch = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val failed = object : AiMoveEngine {
            override suspend fun chooseBestMove(position: GameState, settings: AiMoveSettings): AiMoveResult =
                error("analysis failure")
        }
        assertFalse(LocalAiTurnController(failedMatch, failed).play(AiMoveSettings()))
        assertTrue(failedMatch.viewState.moves.isEmpty())
        assertFalse(failedMatch.viewState.aiThinking)
    }

    @Test
    fun cancelledMoveSearchRestoresThinkingState() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val engine = object : AiMoveEngine {
            override suspend fun chooseBestMove(position: GameState, settings: AiMoveSettings): AiMoveResult =
                throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            LocalAiTurnController(match, engine).play(AiMoveSettings())
        }
        assertTrue(match.viewState.moves.isEmpty())
        assertFalse(match.viewState.aiThinking)
    }

    @Test
    fun concurrentAiRequestsProduceAtMostOneMove() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val engine = BlockingEngine()
        val controller = LocalAiTurnController(match, engine)
        val first = async(start = CoroutineStart.DEFAULT) { controller.play(AiMoveSettings()) }
        engine.started.await()

        assertFalse(controller.play(AiMoveSettings()))
        engine.release.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, match.viewState.moves.size)
    }

    private class FakeEngine : AiMoveEngine {
        var lastSettings: AiMoveSettings? = null
        var chosenMove: Position? = null
        override suspend fun chooseBestMove(position: GameState, settings: AiMoveSettings): AiMoveResult {
            lastSettings = settings
            chosenMove = position.legalMoves.last()
            return AiMoveResult(chosenMove)
        }
    }

    private class BlockingEngine : AiMoveEngine {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun chooseBestMove(position: GameState, settings: AiMoveSettings): AiMoveResult {
            started.complete(Unit)
            release.await()
            return AiMoveResult(position.legalMoves.first())
        }
    }
}
