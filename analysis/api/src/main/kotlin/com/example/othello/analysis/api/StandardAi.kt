package com.example.othello.analysis.api

import com.example.othello.game.GameState
import com.example.othello.game.Position

enum class StandardAiLevel(
    val value: Int,
    val edaxLevel: Int,
) {
    LV1(1, 1),
    LV2(2, 2),
    LV3(3, 3),
    LV4(4, 4),
    LV5(5, 1),
    LV6(6, 2),
    LV7(7, 3),
    LV8(8, 4),
    ;

    fun next(): StandardAiLevel? = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromValue(value: Int): StandardAiLevel = entries.single { it.value == value }
    }
}

data class StandardEvaluationAsset(
    val appPrivatePath: String,
    val identitySha256: String,
)

enum class StandardEvaluationPreparationPhase { DOWNLOADING, EXTRACTING, VALIDATING }

interface StandardEvaluationDataSource {
    val nativeAvailable: Boolean
    fun current(): StandardEvaluationAsset?
    suspend fun prepare(
        onPhase: suspend (StandardEvaluationPreparationPhase) -> Unit = {},
    ): StandardEvaluationAsset
}

data class StandardMoveCandidate(
    val move: Position,
    val score: Int,
)

data class StandardCandidateResult(
    val rankedCandidates: List<StandardMoveCandidate>,
    val available: Boolean = true,
    val message: String? = null,
)

interface StandardCandidateProvider {
    suspend fun evaluate(
        position: GameState,
        edaxLevel: Int,
        evaluationData: StandardEvaluationAsset,
    ): StandardCandidateResult

    fun cancel() = Unit
}

/** UI-independent strength + personality configuration, reusable by future online bots. */
data class StandardAiConfig(
    val edaxLevel: Int,
    val personality: StandardAiPersonality,
) {
    init {
        require(edaxLevel in 1..4) { "Standard Edax level must be in 1..4" }
    }
}

/** Current campaign preset. Display level, Edax strength, and personality remain distinct concepts. */
fun standardCampaignAiConfig(level: StandardAiLevel): StandardAiConfig = StandardAiConfig(
    edaxLevel = level.edaxLevel,
    personality = StandardAiPersonalities.natural(level),
)

data class StandardAiMoveResult(
    val move: Position?,
    val targetThinkTimeMs: Long = 0L,
    val personalityId: StandardAiPersonalityId? = null,
    val tensionLevel: StandardTensionLevel = StandardTensionLevel.CALM,
    /**
     * Best evaluation from the AI side-to-move perspective for this decision.
     * Null for forced moves, which intentionally skip candidate evaluation.
     */
    val opponentBestScore: Int? = null,
    val available: Boolean = true,
    val message: String? = null,
)

/**
 * Product-level Standard AI. It always evaluates through the Standard candidate provider,
 * delegates behavior to the configured personality, and never accepts Advanced eval/book settings.
 */
class StandardAiEngine(
    private val candidateProvider: StandardCandidateProvider,
) {
    suspend fun chooseMove(
        position: GameState,
        config: StandardAiConfig,
        evaluationData: StandardEvaluationAsset,
    ): StandardAiMoveResult {
        if (position.legalMoves.isEmpty()) return StandardAiMoveResult(null)
        if (position.legalMoves.size == 1) {
            val move = position.legalMoves.single()
            val context = StandardDecisionContext.forced(position)
            return StandardAiMoveResult(
                move = move,
                targetThinkTimeMs = config.personality.thinkTimePolicy.targetThinkTimeMs(context, move),
                personalityId = config.personality.id,
                tensionLevel = config.personality.tensionPolicy.evaluate(context),
            )
        }

        return chooseFromRankedCandidates(position, config, evaluationData)
    }

    fun cancel() = candidateProvider.cancel()

    private suspend fun chooseFromRankedCandidates(
        position: GameState,
        config: StandardAiConfig,
        evaluationData: StandardEvaluationAsset,
    ): StandardAiMoveResult {
        val result = candidateProvider.evaluate(position, config.edaxLevel, evaluationData)
        if (!result.available) return unavailable(result.message)
        val candidateMoves = result.rankedCandidates.map { it.move }
        if (candidateMoves.toSet() != position.legalMoves || candidateMoves.size != position.legalMoves.size) {
            return unavailable("Standard AI candidate set is invalid")
        }

        val context = StandardDecisionContext(position, result.rankedCandidates)
        val move = config.personality.moveSelectionPolicy.select(context)
            ?.takeIf { it in position.legalMoves }
            ?: return unavailable("Standard AI selected no legal move")
        return StandardAiMoveResult(
            move = move,
            targetThinkTimeMs = config.personality.thinkTimePolicy.targetThinkTimeMs(context, move),
            personalityId = config.personality.id,
            tensionLevel = config.personality.tensionPolicy.evaluate(context),
            opponentBestScore = context.bestScore,
        )
    }

    private fun unavailable(message: String?) = StandardAiMoveResult(
        move = null,
        available = false,
        message = message,
    )
}
