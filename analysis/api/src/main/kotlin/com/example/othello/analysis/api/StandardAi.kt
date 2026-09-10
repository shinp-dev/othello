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

fun interface StandardMoveSelectionPolicy {
    fun select(position: GameState, rankedCandidates: List<StandardMoveCandidate>): Position?
}

/** Campaign's restrained personality: second-best through move 40, then best. */
object StandardHonorStudentPolicy : StandardMoveSelectionPolicy {
    override fun select(
        position: GameState,
        rankedCandidates: List<StandardMoveCandidate>,
    ): Position? = when {
        rankedCandidates.isEmpty() -> null
        position.ply < SECOND_BEST_END_PLY && rankedCandidates.size > 1 -> rankedCandidates[1].move
        else -> rankedCandidates.first().move
    }

    private const val SECOND_BEST_END_PLY = 40
}

/** Selects the best of all candidates ranked by the Standard provider. */
object StandardBestMovePolicy : StandardMoveSelectionPolicy {
    override fun select(position: GameState, rankedCandidates: List<StandardMoveCandidate>): Position? =
        rankedCandidates.firstOrNull()?.move
}

/** UI-independent strength and personality configuration, reusable by future online bots. */
data class StandardAiConfig(
    val edaxLevel: Int,
    val moveSelectionPolicy: StandardMoveSelectionPolicy,
) {
    init {
        require(edaxLevel in 1..4) { "Standard Edax level must be in 1..4" }
    }
}

/** The current campaign preset. Display level, Edax strength, and policy remain distinct concepts. */
fun standardCampaignAiConfig(level: StandardAiLevel): StandardAiConfig = StandardAiConfig(
    edaxLevel = level.edaxLevel,
    moveSelectionPolicy = if (level.value <= StandardAiLevel.LV4.value) {
        StandardHonorStudentPolicy
    } else {
        StandardBestMovePolicy
    },
)

data class StandardAiMoveResult(
    val move: Position?,
    val available: Boolean = true,
    val message: String? = null,
)

/** Product-level Standard AI. It never accepts Advanced eval/book settings. */
class StandardAiEngine(
    private val candidateProvider: StandardCandidateProvider,
) {
    suspend fun chooseMove(
        position: GameState,
        config: StandardAiConfig,
        evaluationData: StandardEvaluationAsset,
    ): StandardAiMoveResult {
        if (position.legalMoves.isEmpty()) return StandardAiMoveResult(null)
        if (position.legalMoves.size == 1) return StandardAiMoveResult(position.legalMoves.single())

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
        val move = config.moveSelectionPolicy.select(position, result.rankedCandidates)
            ?.takeIf { it in position.legalMoves }
        return move?.let { StandardAiMoveResult(it) }
            ?: unavailable("Standard AI selected no legal move")
    }

    private fun unavailable(message: String?) = StandardAiMoveResult(
        move = null,
        available = false,
        message = message,
    )
}
