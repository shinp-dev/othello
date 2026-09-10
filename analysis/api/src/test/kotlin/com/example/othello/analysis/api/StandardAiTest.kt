package com.example.othello.analysis.api

import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.Position
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class StandardAiTest {
    @Test
    fun displayLevelsAreOneThroughEightAndMapToEdaxOneThroughFourTwice() {
        assertEquals((1..8).toList(), StandardAiLevel.entries.map { it.value })
        assertEquals(listOf(1, 2, 3, 4, 1, 2, 3, 4), StandardAiLevel.entries.map { it.edaxLevel })
        assertEquals(StandardAiLevel.LV5, StandardAiLevel.LV4.next())
        assertNull(StandardAiLevel.LV8.next())
    }

    @Test
    fun campaignPresetKeepsDisplayLevelStrengthAndPolicySeparate() {
        StandardAiLevel.entries.take(4).forEach { level ->
            val config = standardCampaignAiConfig(level)
            assertEquals(level.edaxLevel, config.edaxLevel)
            assertIs<StandardHonorStudentPolicy>(config.moveSelectionPolicy)
        }
        StandardAiLevel.entries.drop(4).forEach { level ->
            val config = standardCampaignAiConfig(level)
            assertEquals(level.edaxLevel, config.edaxLevel)
            assertIs<StandardBestMovePolicy>(config.moveSelectionPolicy)
        }
    }

    @Test
    fun restrainedPolicyUsesSecondBestThroughMoveFortyAndBestFromMoveFortyOne() {
        val candidates = initialCandidates()

        assertEquals(candidates[1].move, StandardHonorStudentPolicy.select(GameState(ply = 0), candidates))
        assertEquals(candidates[1].move, StandardHonorStudentPolicy.select(GameState(ply = 39), candidates))
        assertEquals(candidates[0].move, StandardHonorStudentPolicy.select(GameState(ply = 40), candidates))
    }

    @Test
    fun levelsOneThroughFourAlwaysEvaluateCandidatesAndChangeSelectionAtMoveFortyOne() = runBlocking {
        val provider = RecordingProvider()
        val engine = StandardAiEngine(provider)

        val moveForty = engine.chooseMove(GameState(ply = 39), standardCampaignAiConfig(StandardAiLevel.LV4), asset())
        val moveFortyOne = engine.chooseMove(GameState(ply = 40), standardCampaignAiConfig(StandardAiLevel.LV4), asset())

        assertEquals(initialCandidates()[1].move, moveForty.move)
        assertEquals(initialCandidates()[0].move, moveFortyOne.move)
        assertEquals(listOf(4, 4), provider.edaxLevels)
    }

    @Test
    fun levelsFiveThroughEightEvaluateCandidatesAndAlwaysChooseBestWithMappedStrength() = runBlocking {
        val provider = RecordingProvider()
        val engine = StandardAiEngine(provider)

        val levelFive = engine.chooseMove(GameState(), standardCampaignAiConfig(StandardAiLevel.LV5), asset())
        val levelEight = engine.chooseMove(GameState(ply = 39), standardCampaignAiConfig(StandardAiLevel.LV8), asset())

        assertEquals(initialCandidates()[0].move, levelFive.move)
        assertEquals(initialCandidates()[0].move, levelEight.move)
        assertEquals(listOf(1, 4), provider.edaxLevels)
    }

    @Test
    fun oneLegalMoveIsSelectedWithoutCandidateEvaluation() = runBlocking {
        val state = GameState(
            board = Board.fromRows(listOf("WB......") + List(7) { "........" }),
            currentPlayer = Disc.WHITE,
        )
        val provider = RecordingProvider()

        val result = StandardAiEngine(provider).chooseMove(
            state,
            standardCampaignAiConfig(StandardAiLevel.LV8),
            asset(),
        )

        assertEquals(state.legalMoves.single(), result.move)
        assertEquals(emptyList(), provider.edaxLevels)
    }

    @Test
    fun configKeepsEdaxStrengthAndPersonalityIndependent() = runBlocking {
        val customPolicy = StandardMoveSelectionPolicy { _, candidates -> candidates.last().move }
        val provider = RecordingProvider()
        val config = StandardAiConfig(edaxLevel = 3, moveSelectionPolicy = customPolicy)

        val result = StandardAiEngine(provider).chooseMove(GameState(), config, asset())

        assertEquals(GameState().legalMoves.last(), result.move)
        assertEquals(listOf(3), provider.edaxLevels)
    }

    @Test
    fun invalidStandardEdaxStrengthIsRejected() {
        assertFalse(runCatching { StandardAiConfig(5, StandardBestMovePolicy) }.isSuccess)
        assertFalse(runCatching { StandardAiConfig(8, StandardBestMovePolicy) }.isSuccess)
    }

    private fun initialCandidates(): List<StandardMoveCandidate> = rankedCandidates(GameState())

    private fun rankedCandidates(position: GameState): List<StandardMoveCandidate> =
        position.legalMoves.mapIndexed { index, move -> StandardMoveCandidate(move, 10 - index) }

    private fun asset() = StandardEvaluationAsset("standard/eval.dat", "a".repeat(64))

    private inner class RecordingProvider : StandardCandidateProvider {
        val edaxLevels = mutableListOf<Int>()

        override suspend fun evaluate(
            position: GameState,
            edaxLevel: Int,
            evaluationData: StandardEvaluationAsset,
        ): StandardCandidateResult {
            edaxLevels += edaxLevel
            return StandardCandidateResult(rankedCandidates(position))
        }
    }
}
