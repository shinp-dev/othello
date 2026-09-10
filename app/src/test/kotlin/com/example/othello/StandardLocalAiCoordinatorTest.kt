package com.example.othello

import com.example.othello.analysis.api.StandardAiEngine
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.analysis.api.StandardCandidateProvider
import com.example.othello.analysis.api.StandardCandidateResult
import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.analysis.api.StandardMoveCandidate
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
    fun passesDedicatedLevelAndEvaluationToStandardEngine() = runBlocking {
        val match = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        assertTrue(match.play(Position(2, 3)))
        val provider = RecordingProvider()
        val coordinator = StandardLocalAiCoordinator(
            match = match,
            engine = StandardAiEngine(provider),
            config = standardCampaignAiConfig(StandardAiLevel.LV3),
            evaluationData = evaluation,
        )

        assertTrue(coordinator.play())

        assertEquals(3, provider.edaxLevel)
        assertEquals(evaluation, provider.evaluation)
        assertEquals(provider.selectedMove, match.viewState.moves.last())
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
            // Keep this coordinator contract deterministic. Natural-play randomness is
            // covered in analysis:api tests; here the alternatives are intentionally
            // far enough behind that the policy must choose the best candidate.
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
