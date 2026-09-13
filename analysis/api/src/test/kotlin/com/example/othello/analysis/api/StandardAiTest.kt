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
    fun naturalPolicyCanVaryAmongCloseTopMovesEarly() {
        val candidates = initialCandidates()
        val context = StandardDecisionContext(GameState(ply = 0), candidates)

        val third = StandardWeightedMoveSelectionPolicy(profile()) { 0.50 }.select(context)
        val fourth = StandardWeightedMoveSelectionPolicy(profile()) { 0.90 }.select(context)

        assertEquals(candidates[2].move, third)
        assertEquals(candidates[3].move, fourth)
    }

    @Test
    fun naturalPolicyGraduallyFavorsBestMoveLateInGame() {
        val candidates = initialCandidates()
        val policy = StandardWeightedMoveSelectionPolicy(profile()) { 0.15 }

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

        val selected = StandardWeightedMoveSelectionPolicy(profile()) { 0.99 }
            .select(StandardDecisionContext(state, candidates))

        assertEquals(candidates[0].move, selected)
    }

    @Test
    fun lowScoreLossProfileFiltersCatastrophicMoves() {
        val state = GameState()
        val legalMoves = state.legalMoves.toList()
        val candidates = listOf(
            StandardMoveCandidate(legalMoves[0], 10),
            StandardMoveCandidate(legalMoves[1], 5),
            StandardMoveCandidate(legalMoves[2], 1),
            StandardMoveCandidate(legalMoves[3], 0),
        )

        val selected = StandardWeightedMoveSelectionPolicy(profile().copy(openingMaxScoreLoss = 8, endgameMaxScoreLoss = 4)) { 0.99 }
            .select(StandardDecisionContext(state, candidates))

        assertEquals(candidates[1].move, selected)
    }

    @Test
    fun allAllowedStrengthsEvaluateCandidates() = runBlocking {
        val provider = RecordingProvider()
        val engine = StandardAiEngine(provider)

        (1..4).forEach { strength ->
            val personality = personality()
            val config = StandardAiConfig(edaxLevel = strength, personality = personality)
            val result = engine.chooseMove(GameState(), config, asset())
            assertEquals(true, result.move in GameState().legalMoves)
            assertEquals(StandardAiPersonalityId.NATURAL, result.personalityId)
            assertEquals(10, result.opponentBestScore)
            assertTrue(result.targetThinkTimeMs > 0L)
        }

        assertEquals(listOf(1, 2, 3, 4), provider.edaxLevels)
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
            StandardAiConfig(2, personality()),
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
        val policy = personality().thinkTimePolicy
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

    private fun initialCandidates(): List<StandardMoveCandidate> = rankedCandidates(GameState())

    private fun rankedCandidates(position: GameState): List<StandardMoveCandidate> =
        position.legalMoves.mapIndexed { index, move -> StandardMoveCandidate(move, 10 - index) }

    private fun asset() = StandardEvaluationAsset("standard/eval.dat", "a".repeat(64))

    private fun profile() = StandardWeightedMoveProfile(
        listOf(0.05) + List(7) { 0.95 / 7 }, listOf(0.10) + List(7) { 0.90 / 7 },
        listOf(0.20) + List(7) { 0.80 / 7 }, 30, 18,
    )

    private fun personality() = StandardAiPersonality(
        StandardAiPersonalityId.NATURAL, StandardWeightedMoveSelectionPolicy(profile()) { 0.5 },
        StandardAdaptiveThinkTimePolicy(StandardAdaptiveThinkTimeProfile(
            520, 160, 2, 180, 3, 220, 360, 8, 100, 120, 44, 80, maxMs = 1300,
        )), StandardAdaptiveTensionPolicy(),
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
