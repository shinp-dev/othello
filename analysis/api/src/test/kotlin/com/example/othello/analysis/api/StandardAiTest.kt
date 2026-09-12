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
import kotlin.test.assertTrue

class StandardAiTest {
    @Test
    fun displayLevelsAreOneThroughEightAndMapToEdaxOneThroughFourTwice() {
        assertEquals((1..8).toList(), StandardAiLevel.entries.map { it.value })
        assertEquals(listOf(1, 2, 3, 4, 1, 2, 3, 4), StandardAiLevel.entries.map { it.edaxLevel })
        assertEquals(StandardAiLevel.LV5, StandardAiLevel.LV4.next())
        assertNull(StandardAiLevel.LV8.next())
    }

    @Test
    fun campaignPresetKeepsDisplayLevelStrengthAndPersonalitySeparate() {
        StandardAiLevel.entries.forEach { level ->
            val config = standardCampaignAiConfig(level)
            assertEquals(level.edaxLevel, config.edaxLevel)
            assertEquals(StandardAiPersonalityId.NATURAL, config.personality.id)
            assertIs<StandardNaturalPlayPolicy>(config.personality.moveSelectionPolicy)
            assertIs<StandardAdaptiveThinkTimePolicy>(config.personality.thinkTimePolicy)
            assertIs<StandardAdaptiveTensionPolicy>(config.personality.tensionPolicy)
        }
    }

    @Test
    fun campaignMoveProfilesMatchBeginnerTargets() {
        val expected = listOf(
            ExpectedMoveProfile(StandardAiLevel.LV1, 8, 0.05, 0.10, 0.20, 30, 18),
            ExpectedMoveProfile(StandardAiLevel.LV2, 7, 0.10, 0.18, 0.30, 24, 14),
            ExpectedMoveProfile(StandardAiLevel.LV3, 6, 0.16, 0.28, 0.45, 18, 10),
            ExpectedMoveProfile(StandardAiLevel.LV4, 5, 0.24, 0.40, 0.60, 13, 7),
            ExpectedMoveProfile(StandardAiLevel.LV5, 8, 0.05, 0.10, 0.20, 8, 4),
            ExpectedMoveProfile(StandardAiLevel.LV6, 7, 0.10, 0.18, 0.30, 6, 3),
            ExpectedMoveProfile(StandardAiLevel.LV7, 6, 0.16, 0.28, 0.45, 4, 2),
            ExpectedMoveProfile(StandardAiLevel.LV8, 5, 0.24, 0.40, 0.60, 3, 1),
        )

        expected.forEach { target ->
            val profile = standardCampaignMoveProfile(target.level)
            assertEquals(target.candidateLimit, profile.openingWeights.size)
            assertEquals(target.candidateLimit, profile.midgameWeights.size)
            assertEquals(target.candidateLimit, profile.endgameWeights.size)
            assertEquals(target.openingBestMoveProbability, profile.openingWeights.first())
            assertEquals(target.midgameBestMoveProbability, profile.midgameWeights.first())
            assertEquals(target.endgameBestMoveProbability, profile.endgameWeights.first())
            assertEquals(target.openingMaxScoreLoss, profile.openingMaxScoreLoss)
            assertEquals(target.endgameMaxScoreLoss, profile.endgameMaxScoreLoss)
            assertEquals(1.0, profile.openingWeights.sum(), absoluteTolerance = 1e-9)
            assertEquals(1.0, profile.midgameWeights.sum(), absoluteTolerance = 1e-9)
            assertEquals(1.0, profile.endgameWeights.sum(), absoluteTolerance = 1e-9)
        }
    }

    @Test
    fun naturalPolicyCanVaryAmongCloseTopMovesEarly() {
        val candidates = initialCandidates()
        val context = StandardDecisionContext(GameState(ply = 0), candidates)

        val third = StandardNaturalPlayPolicy(StandardAiLevel.LV1) { 0.50 }.select(context)
        val fourth = StandardNaturalPlayPolicy(StandardAiLevel.LV1) { 0.90 }.select(context)

        assertEquals(candidates[2].move, third)
        assertEquals(candidates[3].move, fourth)
    }

    @Test
    fun naturalPolicyGraduallyFavorsBestMoveLateInGame() {
        val candidates = initialCandidates()
        val policy = StandardNaturalPlayPolicy(StandardAiLevel.LV1) { 0.15 }

        assertEquals(candidates[1].move, policy.select(StandardDecisionContext(GameState(ply = 0), candidates)))
        assertEquals(candidates[0].move, policy.select(StandardDecisionContext(GameState(ply = 50), candidates)))
    }

    @Test
    fun naturalPolicyNeverChoosesMoveBeyondConfiguredScoreLoss() {
        val state = GameState()
        val legalMoves = state.legalMoves.toList()
        val candidates = listOf(
            StandardMoveCandidate(legalMoves[0], 10),
            StandardMoveCandidate(legalMoves[1], -21),
            StandardMoveCandidate(legalMoves[2], -22),
            StandardMoveCandidate(legalMoves[3], -30),
        )

        val selected = StandardNaturalPlayPolicy(StandardAiLevel.LV1) { 0.99 }
            .select(StandardDecisionContext(state, candidates))

        assertEquals(candidates[0].move, selected)
    }

    @Test
    fun wildLevelKeepsNonBestCharacterButFiltersCatastrophicMoves() {
        val state = GameState()
        val legalMoves = state.legalMoves.toList()
        val candidates = listOf(
            StandardMoveCandidate(legalMoves[0], 10),
            StandardMoveCandidate(legalMoves[1], 5),
            StandardMoveCandidate(legalMoves[2], 1),
            StandardMoveCandidate(legalMoves[3], 0),
        )

        val selected = StandardNaturalPlayPolicy(StandardAiLevel.LV5) { 0.99 }
            .select(StandardDecisionContext(state, candidates))

        assertEquals(candidates[1].move, selected)
    }

    @Test
    fun levelsOneThroughEightEvaluateCandidatesWithMappedStrength() = runBlocking {
        val provider = RecordingProvider()
        val engine = StandardAiEngine(provider)

        StandardAiLevel.entries.forEach { level ->
            val personality = StandardAiPersonalities.natural(level) { 0.50 }
            val config = StandardAiConfig(edaxLevel = level.edaxLevel, personality = personality)
            val result = engine.chooseMove(GameState(), config, asset())
            assertEquals(true, result.move in GameState().legalMoves)
            assertEquals(StandardAiPersonalityId.NATURAL, result.personalityId)
            assertEquals(10, result.opponentBestScore)
            assertTrue(result.targetThinkTimeMs > 0L)
        }

        assertEquals(listOf(1, 2, 3, 4, 1, 2, 3, 4), provider.edaxLevels)
    }

    @Test
    fun oneLegalMoveIsSelectedWithoutCandidateEvaluationAndUsesForcedMoveTiming() = runBlocking {
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
        assertEquals(160L, result.targetThinkTimeMs)
        assertEquals(StandardTensionLevel.CALM, result.tensionLevel)
        assertNull(result.opponentBestScore)
        assertEquals(emptyList(), provider.edaxLevels)
    }

    @Test
    fun configKeepsEdaxStrengthAndPersonalityIndependent() = runBlocking {
        val personality = StandardAiPersonality(
            id = StandardAiPersonalityId.NATURAL,
            moveSelectionPolicy = StandardMoveSelectionPolicy { context -> context.rankedCandidates.last().move },
            thinkTimePolicy = StandardThinkTimePolicy { _, _ -> 777L },
            tensionPolicy = StandardTensionPolicy { StandardTensionLevel.CRITICAL },
        )
        val provider = RecordingProvider()
        val config = StandardAiConfig(edaxLevel = 3, personality = personality)

        val result = StandardAiEngine(provider).chooseMove(GameState(), config, asset())

        assertEquals(GameState().legalMoves.last(), result.move)
        assertEquals(777L, result.targetThinkTimeMs)
        assertEquals(StandardTensionLevel.CRITICAL, result.tensionLevel)
        assertEquals(listOf(3), provider.edaxLevels)
    }

    @Test
    fun adaptiveThinkingTakesLongerForAmbiguousRiskyAndNonBestDecisions() {
        val policy = StandardAdaptiveThinkTimePolicy(
            StandardAdaptiveThinkTimeProfile(
                baseMs = 400L,
                forcedMoveMs = 100L,
                ambiguousGapThreshold = 2,
                ambiguousBonusMs = 100L,
                riskyBestScoreThreshold = 3,
                riskyChoiceBonusMs = 200L,
                allNegativeBonusMs = 300L,
                manyMovesThreshold = 8,
                manyMovesBonusMs = 50L,
                nonBestChoiceBonusMs = 80L,
                endgameStartPly = 44,
                endgameBonusMs = 60L,
                maxMs = 2_000L,
            ),
        )
        val state = GameState()
        val moves = state.legalMoves.toList()
        val risky = listOf(
            StandardMoveCandidate(moves[0], 2),
            StandardMoveCandidate(moves[1], 1),
            StandardMoveCandidate(moves[2], -1),
            StandardMoveCandidate(moves[3], -5),
        )
        val context = StandardDecisionContext(state, risky)

        assertEquals(700L, policy.targetThinkTimeMs(context, moves[0]))
        assertEquals(780L, policy.targetThinkTimeMs(context, moves[1]))
    }

    @Test
    fun negativeAlternativesDoNotCauseHesitationWhenBestMoveIsClearlyGood() {
        val policy = StandardAiPersonalities.serious().thinkTimePolicy
        val state = GameState()
        val moves = state.legalMoves.toList()
        val clear = listOf(
            StandardMoveCandidate(moves[0], 12),
            StandardMoveCandidate(moves[1], 8),
            StandardMoveCandidate(moves[2], -2),
            StandardMoveCandidate(moves[3], -15),
        )

        val target = policy.targetThinkTimeMs(StandardDecisionContext(state, clear), moves[0])

        assertEquals(500L, target)
    }

    @Test
    fun allNegativePositionAddsMoreHesitation() {
        val policy = StandardAiPersonalities.natural(StandardAiLevel.LV1) { 0.0 }.thinkTimePolicy
        val state = GameState()
        val moves = state.legalMoves.toList()
        val candidates = listOf(
            StandardMoveCandidate(moves[0], -1),
            StandardMoveCandidate(moves[1], -2),
            StandardMoveCandidate(moves[2], -4),
            StandardMoveCandidate(moves[3], -8),
        )

        val target = policy.targetThinkTimeMs(StandardDecisionContext(state, candidates), moves[0])

        assertEquals(1_060L, target)
    }

    @Test
    fun adaptiveTensionLeavesClearlyGoodPositionCalm() {
        val state = GameState()
        val moves = state.legalMoves.toList()
        val candidates = listOf(
            StandardMoveCandidate(moves[0], 12),
            StandardMoveCandidate(moves[1], 8),
            StandardMoveCandidate(moves[2], -2),
            StandardMoveCandidate(moves[3], -15),
        )

        val tension = StandardAdaptiveTensionPolicy().evaluate(StandardDecisionContext(state, candidates))

        assertEquals(StandardTensionLevel.CALM, tension)
    }

    @Test
    fun adaptiveTensionMarksAmbiguousAllNegativeEndgameCritical() {
        val state = GameState(ply = 44)
        val moves = state.legalMoves.toList()
        val candidates = listOf(
            StandardMoveCandidate(moves[0], -1),
            StandardMoveCandidate(moves[1], -2),
            StandardMoveCandidate(moves[2], -4),
            StandardMoveCandidate(moves[3], -8),
        )

        val tension = StandardAdaptiveTensionPolicy().evaluate(StandardDecisionContext(state, candidates))

        assertEquals(StandardTensionLevel.CRITICAL, tension)
    }

    @Test
    fun invalidStandardEdaxStrengthIsRejected() {
        assertFalse(runCatching { StandardAiConfig(5, StandardAiPersonalities.serious()) }.isSuccess)
        assertFalse(runCatching { StandardAiConfig(8, StandardAiPersonalities.serious()) }.isSuccess)
    }

    @Test
    fun naturalPolicySupportsAllCampaignLevels() {
        assertTrue(StandardAiLevel.entries.all { runCatching { StandardNaturalPlayPolicy(it) }.isSuccess })
    }

    private fun initialCandidates(): List<StandardMoveCandidate> = rankedCandidates(GameState())

    private fun rankedCandidates(position: GameState): List<StandardMoveCandidate> =
        position.legalMoves.mapIndexed { index, move -> StandardMoveCandidate(move, 10 - index) }

    private fun asset() = StandardEvaluationAsset("standard/eval.dat", "a".repeat(64))

    private data class ExpectedMoveProfile(
        val level: StandardAiLevel,
        val candidateLimit: Int,
        val openingBestMoveProbability: Double,
        val midgameBestMoveProbability: Double,
        val endgameBestMoveProbability: Double,
        val openingMaxScoreLoss: Int,
        val endgameMaxScoreLoss: Int,
    )

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
