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
            assertIs<StandardNaturalPlayPolicy>(config.moveSelectionPolicy)
        }
        StandardAiLevel.entries.drop(4).forEach { level ->
            val config = standardCampaignAiConfig(level)
            assertEquals(level.edaxLevel, config.edaxLevel)
            assertIs<StandardBestMovePolicy>(config.moveSelectionPolicy)
        }
    }

    @Test
    fun naturalPolicyCanVaryAmongCloseTopMovesEarly() {
        val candidates = initialCandidates()
        val state = GameState(ply = 0)

        val second = StandardNaturalPlayPolicy(StandardAiLevel.LV1) { 0.50 }.select(state, candidates)
        val third = StandardNaturalPlayPolicy(StandardAiLevel.LV1) { 0.90 }.select(state, candidates)

        assertEquals(candidates[1].move, second)
        assertEquals(candidates[2].move, third)
    }

    @Test
    fun naturalPolicyGraduallyFavorsBestMoveLateInGame() {
        val candidates = initialCandidates()
        val policy = StandardNaturalPlayPolicy(StandardAiLevel.LV1) { 0.50 }

        assertEquals(candidates[1].move, policy.select(GameState(ply = 0), candidates))
        assertEquals(candidates[0].move, policy.select(GameState(ply = 50), candidates))
    }

    @Test
    fun naturalPolicyNeverChoosesClearlyWorseMoveJustBecauseItIsRankedSecond() {
        val state = GameState()
        val legalMoves = state.legalMoves.toList()
        val candidates = listOf(
            StandardMoveCandidate(legalMoves[0], 10),
            StandardMoveCandidate(legalMoves[1], -10),
            StandardMoveCandidate(legalMoves[2], -20),
            StandardMoveCandidate(legalMoves[3], -30),
        )

        val selected = StandardNaturalPlayPolicy(StandardAiLevel.LV1) { 0.99 }.select(state, candidates)

        assertEquals(candidates[0].move, selected)
    }

    @Test
    fun higherNaturalLevelIsMoreAccurateAndReachesBestOnlyEndgameBehavior() {
        val candidates = initialCandidates()
        val early = StandardNaturalPlayPolicy(StandardAiLevel.LV4) { 0.80 }
        val late = StandardNaturalPlayPolicy(StandardAiLevel.LV4) { 0.80 }

        assertEquals(candidates[1].move, early.select(GameState(ply = 0), candidates))
        assertEquals(candidates[0].move, late.select(GameState(ply = 50), candidates))
    }

    @Test
    fun levelsOneThroughFourStillEvaluateAllCandidatesWithMappedStrength() = runBlocking {
        val provider = RecordingProvider()
        val engine = StandardAiEngine(provider)

        StandardAiLevel.entries.take(4).forEach { level ->
            val config = StandardAiConfig(
                edaxLevel = level.edaxLevel,
                moveSelectionPolicy = StandardNaturalPlayPolicy(level) { 0.50 },
            )
            val result = engine.chooseMove(GameState(), config, asset())
            assertEquals(true, result.move in GameState().legalMoves)
        }

        assertEquals(listOf(1, 2, 3, 4), provider.edaxLevels)
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

    @Test
    fun naturalPolicyIsLimitedToCampaignLevelsOneThroughFour() {
        assertFalse(runCatching { StandardNaturalPlayPolicy(StandardAiLevel.LV5) }.isSuccess)
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
