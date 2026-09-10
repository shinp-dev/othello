package com.example.othello

import com.example.othello.analysis.api.StandardAiEngine
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.analysis.api.StandardCandidateProvider
import com.example.othello.analysis.api.StandardCandidateResult
import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.analysis.api.StandardMoveCandidate
import com.example.othello.analysis.api.StandardTensionLevel
import com.example.othello.analysis.api.standardCampaignAiConfig
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.Position
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandardLocalAiCoordinatorTest {
    private val evaluation = StandardEvaluationAsset("/standard/eval.dat", "sha256")

    @Test
    fun passesDedicatedLevelEvaluationAndTensionAndWaitsOnlyForRemainingTargetTime() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        assertTrue(match.play(Position(2, 3)))
        val provider = RecordingProvider()
        val waits = mutableListOf<Long>()
        val tensions = mutableListOf<StandardTensionLevel>()
        val bestScores = mutableListOf<Int?>()
        val times = ArrayDeque(listOf(1_000L, 1_200L))
        val coordinator = StandardLocalAiCoordinator(
            match = match,
            engine = StandardAiEngine(provider),
            config = standardCampaignAiConfig(StandardAiLevel.LV3),
            evaluationData = evaluation,
            monotonicMillis = { times.removeFirst() },
            waitMillis = { waits += it },
            onDecisionReady = { tension, opponentBestScore ->
                tensions += tension
                bestScores += opponentBestScore
            },
        )

        assertTrue(coordinator.play())

        assertEquals(3, provider.edaxLevel)
        assertEquals(evaluation, provider.evaluation)
        assertEquals(provider.selectedMove, match.viewState.moves.last())
        assertEquals(listOf(StandardTensionLevel.CALM), tensions)
        assertEquals(listOf(100), bestScores)
        assertEquals(listOf(320L), waits)
        assertFalse(match.viewState.aiThinking)
    }

    @Test
    fun doesNotAddDelayWhenEvaluationAlreadyExceedsTargetThinkingTime() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        assertTrue(match.play(Position(2, 3)))
        val waits = mutableListOf<Long>()
        val times = ArrayDeque(listOf(1_000L, 2_000L))
        val coordinator = StandardLocalAiCoordinator(
            match = match,
            engine = StandardAiEngine(RecordingProvider()),
            config = standardCampaignAiConfig(StandardAiLevel.LV3),
            evaluationData = evaluation,
            monotonicMillis = { times.removeFirst() },
            waitMillis = { waits += it },
        )

        assertTrue(coordinator.play())

        assertTrue(waits.isEmpty())
        assertFalse(match.viewState.aiThinking)
    }

    @Test
    fun cancellationForUndoRejectsACompletedLateSearch() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        val initial = match.viewState.game
        assertTrue(match.play(initial.legalMoves.first()))
        val provider = BlockingProvider()
        val coordinator = StandardLocalAiCoordinator(
            match = match,
            engine = StandardAiEngine(provider),
            config = standardCampaignAiConfig(StandardAiLevel.LV1),
            evaluationData = evaluation,
            monotonicMillis = { 0L },
            waitMillis = {},
        )
        val search = async(start = CoroutineStart.DEFAULT) { coordinator.play() }
        provider.started.await()

        coordinator.cancelForUndo()
        assertTrue(match.undo() != null)
        provider.release.complete(Unit)

        assertFalse(search.await())
        assertEquals(1, provider.cancelCalls)
        assertEquals(initial, match.viewState.game)
        assertTrue(match.viewState.moves.isEmpty())
        assertTrue(match.viewState.undoUsed)
        assertFalse(match.viewState.aiThinking)
    }

    private class RecordingProvider : StandardCandidateProvider {
        var edaxLevel: Int? = null
        var evaluation: StandardEvaluationAsset? = null
        var selectedMove: Position? = null

        override suspend fun evaluate(
            position: GameState,
            edaxLevel: Int,
            evaluationData: StandardEvaluationAsset,
        ): StandardCandidateResult {
            this.edaxLevel = edaxLevel
            evaluation = evaluationData
            // Keep the coordinator contract deterministic. Personality randomness belongs
            // to analysis:api tests; alternatives here are intentionally far behind.
            val ranked = position.legalMoves.mapIndexed { index, move ->
                StandardMoveCandidate(move, score = 100 - index * 100)
            }
            selectedMove = ranked.first().move
            return StandardCandidateResult(ranked)
        }
    }

    private class BlockingProvider : StandardCandidateProvider {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelCalls = 0

        override suspend fun evaluate(
            position: GameState,
            edaxLevel: Int,
            evaluationData: StandardEvaluationAsset,
        ): StandardCandidateResult {
            started.complete(Unit)
            release.await()
            return StandardCandidateResult(
                position.legalMoves.map { StandardMoveCandidate(it, score = 0) },
            )
        }

        override fun cancel() {
            cancelCalls++
        }
    }
}
