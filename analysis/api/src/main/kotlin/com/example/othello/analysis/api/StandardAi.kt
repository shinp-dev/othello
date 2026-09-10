package com.example.othello.analysis.api

import com.example.othello.game.GameState
import com.example.othello.game.Position
import kotlin.math.roundToInt
import kotlin.random.Random

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

/**
 * Lv1-Lv4 campaign personality.
 *
 * Instead of mechanically choosing the second-ranked move through a fixed ply,
 * this policy gradually becomes more accurate as the game progresses. It only
 * randomizes among the top three moves whose evaluation loss is small enough,
 * so a clearly losing alternative is never selected merely because it is rank 2.
 */
class StandardNaturalPlayPolicy(
    level: StandardAiLevel,
    private val randomUnit: () -> Double = { Random.Default.nextDouble() },
) : StandardMoveSelectionPolicy {
    private val profile = profileFor(level)

    init {
        require(level.value in 1..4) { "Natural-play policy is only for Standard Lv1-Lv4" }
    }

    override fun select(
        position: GameState,
        rankedCandidates: List<StandardMoveCandidate>,
    ): Position? {
        if (rankedCandidates.isEmpty()) return null
        if (rankedCandidates.size == 1) return rankedCandidates.first().move

        val progress = (position.ply.toDouble() / FULL_STRENGTH_PLY).coerceIn(0.0, 1.0)
        val weights = DoubleArray(MAX_RANDOMIZED_CANDIDATES) { index ->
            lerp(profile.openingWeights[index], profile.endgameWeights[index], progress)
        }
        val maxScoreLoss = lerp(
            profile.openingMaxScoreLoss.toDouble(),
            profile.endgameMaxScoreLoss.toDouble(),
            progress,
        ).roundToInt()
        val bestScore = rankedCandidates.first().score

        val eligible = rankedCandidates
            .take(MAX_RANDOMIZED_CANDIDATES)
            .withIndex()
            .filter { indexed ->
                indexed.index == 0 || bestScore - indexed.value.score <= maxScoreLoss
            }

        if (eligible.size == 1) return eligible.first().value.move

        val totalWeight = eligible.sumOf { weights[it.index] }
        if (totalWeight <= 0.0) return rankedCandidates.first().move

        val draw = randomUnit().coerceIn(0.0, RANDOM_UPPER_BOUND) * totalWeight
        var cumulative = 0.0
        eligible.forEach { indexed ->
            cumulative += weights[indexed.index]
            if (draw < cumulative) return indexed.value.move
        }
        return eligible.last().value.move
    }

    private data class Profile(
        val openingWeights: DoubleArray,
        val endgameWeights: DoubleArray,
        val openingMaxScoreLoss: Int,
        val endgameMaxScoreLoss: Int,
    )

    private companion object {
        const val MAX_RANDOMIZED_CANDIDATES = 3
        const val FULL_STRENGTH_PLY = 50.0
        const val RANDOM_UPPER_BOUND = 0.999999999999

        fun profileFor(level: StandardAiLevel): Profile = when (level) {
            StandardAiLevel.LV1 -> Profile(
                openingWeights = doubleArrayOf(0.30, 0.45, 0.25),
                endgameWeights = doubleArrayOf(0.70, 0.25, 0.05),
                openingMaxScoreLoss = 10,
                endgameMaxScoreLoss = 5,
            )
            StandardAiLevel.LV2 -> Profile(
                openingWeights = doubleArrayOf(0.40, 0.45, 0.15),
                endgameWeights = doubleArrayOf(0.85, 0.15, 0.0),
                openingMaxScoreLoss = 8,
                endgameMaxScoreLoss = 4,
            )
            StandardAiLevel.LV3 -> Profile(
                openingWeights = doubleArrayOf(0.55, 0.35, 0.10),
                endgameWeights = doubleArrayOf(0.95, 0.05, 0.0),
                openingMaxScoreLoss = 6,
                endgameMaxScoreLoss = 3,
            )
            StandardAiLevel.LV4 -> Profile(
                openingWeights = doubleArrayOf(0.70, 0.30, 0.0),
                endgameWeights = doubleArrayOf(1.0, 0.0, 0.0),
                openingMaxScoreLoss = 4,
                endgameMaxScoreLoss = 2,
            )
            else -> error("Natural-play policy is only for Standard Lv1-Lv4")
        }

        fun lerp(start: Double, end: Double, progress: Double): Double =
            start + (end - start) * progress
    }
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
        StandardNaturalPlayPolicy(level)
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
