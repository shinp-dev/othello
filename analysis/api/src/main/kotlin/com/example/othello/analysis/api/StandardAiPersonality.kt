package com.example.othello.analysis.api

import com.example.othello.game.GameState
import com.example.othello.game.Position
import kotlin.math.roundToInt
import kotlin.random.Random

enum class StandardAiPersonalityId {
    NATURAL,
    SERIOUS,
}

/**
 * Shared, evaluation-backed view of the current decision.
 *
 * New personalities should consume this context rather than reaching into Edax or UI state.
 * This keeps move choice and presentation timing reusable for local play and future online bots.
 */
data class StandardDecisionContext(
    val position: GameState,
    val rankedCandidates: List<StandardMoveCandidate>,
) {
    val legalMoveCount: Int get() = position.legalMoves.size
    val isForcedMove: Boolean get() = legalMoveCount == 1
    val bestScore: Int? get() = rankedCandidates.firstOrNull()?.score
    val secondBestScore: Int? get() = rankedCandidates.getOrNull(1)?.score
    val topScoreGap: Int? get() = bestScore?.let { best -> secondBestScore?.let { best - it } }
    val hasNegativeCandidate: Boolean get() = rankedCandidates.any { it.score < 0 }
    val allCandidatesNegative: Boolean get() = rankedCandidates.isNotEmpty() && rankedCandidates.all { it.score < 0 }

    fun scoreLoss(move: Position?): Int? {
        val best = bestScore ?: return null
        val selected = rankedCandidates.firstOrNull { it.move == move } ?: return null
        return (best - selected.score).coerceAtLeast(0)
    }

    companion object {
        fun forced(position: GameState): StandardDecisionContext = StandardDecisionContext(
            position = position,
            rankedCandidates = emptyList(),
        )
    }
}

fun interface StandardMoveSelectionPolicy {
    fun select(context: StandardDecisionContext): Position?
}

fun interface StandardThinkTimePolicy {
    /** Desired total thinking time, including the time already spent evaluating candidates. */
    fun targetThinkTimeMs(context: StandardDecisionContext, selectedMove: Position?): Long
}

/**
 * A personality is a composition of independent move-selection and thinking-time behavior.
 * Adding a personality should not require changes to StandardAiEngine or the local-match adapter.
 */
data class StandardAiPersonality(
    val id: StandardAiPersonalityId,
    val moveSelectionPolicy: StandardMoveSelectionPolicy,
    val thinkTimePolicy: StandardThinkTimePolicy,
)

/** Generic weighted selector that can back many personalities without duplicating selection code. */
data class StandardWeightedMoveProfile(
    val openingWeights: List<Double>,
    val endgameWeights: List<Double>,
    val openingMaxScoreLoss: Int,
    val endgameMaxScoreLoss: Int,
    val fullStrengthPly: Double = 50.0,
) {
    init {
        require(openingWeights.isNotEmpty())
        require(openingWeights.size == endgameWeights.size)
        require(openingWeights.all { it >= 0.0 } && endgameWeights.all { it >= 0.0 })
        require(openingMaxScoreLoss >= 0 && endgameMaxScoreLoss >= 0)
        require(fullStrengthPly > 0.0)
    }
}

class StandardWeightedMoveSelectionPolicy(
    private val profile: StandardWeightedMoveProfile,
    private val randomUnit: () -> Double = { Random.Default.nextDouble() },
) : StandardMoveSelectionPolicy {
    override fun select(context: StandardDecisionContext): Position? {
        val rankedCandidates = context.rankedCandidates
        if (rankedCandidates.isEmpty()) return context.position.legalMoves.singleOrNull()
        if (rankedCandidates.size == 1) return rankedCandidates.first().move

        val progress = (context.position.ply.toDouble() / profile.fullStrengthPly).coerceIn(0.0, 1.0)
        val candidateLimit = profile.openingWeights.size
        val weights = DoubleArray(candidateLimit) { index ->
            lerp(profile.openingWeights[index], profile.endgameWeights[index], progress)
        }
        val maxScoreLoss = lerp(
            profile.openingMaxScoreLoss.toDouble(),
            profile.endgameMaxScoreLoss.toDouble(),
            progress,
        ).roundToInt()
        val bestScore = rankedCandidates.first().score
        val eligible = rankedCandidates
            .take(candidateLimit)
            .withIndex()
            .filter { indexed -> indexed.index == 0 || bestScore - indexed.value.score <= maxScoreLoss }

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

    private companion object {
        const val RANDOM_UPPER_BOUND = 0.999999999999

        fun lerp(start: Double, end: Double, progress: Double): Double =
            start + (end - start) * progress
    }
}

/** Current Lv1-Lv4 campaign personality, implemented as a data-driven weighted selector. */
class StandardNaturalPlayPolicy(
    level: StandardAiLevel,
    randomUnit: () -> Double = { Random.Default.nextDouble() },
) : StandardMoveSelectionPolicy {
    private val delegate: StandardMoveSelectionPolicy

    init {
        require(level.value in 1..4) { "Natural-play policy is only for Standard Lv1-Lv4" }
        delegate = StandardWeightedMoveSelectionPolicy(profileFor(level), randomUnit)
    }

    override fun select(context: StandardDecisionContext): Position? = delegate.select(context)

    private companion object {
        fun profileFor(level: StandardAiLevel): StandardWeightedMoveProfile = when (level) {
            StandardAiLevel.LV1 -> StandardWeightedMoveProfile(
                openingWeights = listOf(0.30, 0.45, 0.25),
                endgameWeights = listOf(0.70, 0.25, 0.05),
                openingMaxScoreLoss = 10,
                endgameMaxScoreLoss = 5,
            )
            StandardAiLevel.LV2 -> StandardWeightedMoveProfile(
                openingWeights = listOf(0.40, 0.45, 0.15),
                endgameWeights = listOf(0.85, 0.15, 0.0),
                openingMaxScoreLoss = 8,
                endgameMaxScoreLoss = 4,
            )
            StandardAiLevel.LV3 -> StandardWeightedMoveProfile(
                openingWeights = listOf(0.55, 0.35, 0.10),
                endgameWeights = listOf(0.95, 0.05, 0.0),
                openingMaxScoreLoss = 6,
                endgameMaxScoreLoss = 3,
            )
            StandardAiLevel.LV4 -> StandardWeightedMoveProfile(
                openingWeights = listOf(0.70, 0.30, 0.0),
                endgameWeights = listOf(1.0, 0.0, 0.0),
                openingMaxScoreLoss = 4,
                endgameMaxScoreLoss = 2,
            )
            else -> error("Natural-play policy is only for Standard Lv1-Lv4")
        }
    }
}

object StandardBestMovePolicy : StandardMoveSelectionPolicy {
    override fun select(context: StandardDecisionContext): Position? =
        context.rankedCandidates.firstOrNull()?.move ?: context.position.legalMoves.singleOrNull()
}

/**
 * Data-driven thinking-time behavior. Values are target total durations, not extra sleeps.
 * The local adapter subtracts actual Edax evaluation time before delaying.
 */
data class StandardAdaptiveThinkTimeProfile(
    val baseMs: Long,
    val forcedMoveMs: Long,
    val ambiguousGapThreshold: Int,
    val ambiguousBonusMs: Long,
    val riskyBestScoreThreshold: Int,
    val riskyChoiceBonusMs: Long,
    val allNegativeBonusMs: Long,
    val manyMovesThreshold: Int,
    val manyMovesBonusMs: Long,
    val nonBestChoiceBonusMs: Long,
    val endgameStartPly: Int,
    val endgameBonusMs: Long,
    val minMs: Long = 100L,
    val maxMs: Long = 1_500L,
) {
    init {
        require(baseMs >= 0L && forcedMoveMs >= 0L)
        require(ambiguousGapThreshold >= 0 && manyMovesThreshold >= 1 && endgameStartPly >= 0)
        require(
            listOf(
                ambiguousBonusMs,
                riskyChoiceBonusMs,
                allNegativeBonusMs,
                manyMovesBonusMs,
                nonBestChoiceBonusMs,
                endgameBonusMs,
            ).all { it >= 0L },
        )
        require(minMs >= 0L && maxMs >= minMs)
    }
}

class StandardAdaptiveThinkTimePolicy(
    private val profile: StandardAdaptiveThinkTimeProfile,
) : StandardThinkTimePolicy {
    override fun targetThinkTimeMs(context: StandardDecisionContext, selectedMove: Position?): Long {
        if (context.legalMoveCount <= 1) {
            return profile.forcedMoveMs.coerceIn(profile.minMs, profile.maxMs)
        }

        var target = profile.baseMs
        if (context.topScoreGap?.let { it <= profile.ambiguousGapThreshold } == true) {
            target += profile.ambiguousBonusMs
        }
        if (context.allCandidatesNegative) {
            target += profile.allNegativeBonusMs
        } else if (
            context.hasNegativeCandidate &&
            context.bestScore?.let { it <= profile.riskyBestScoreThreshold } == true
        ) {
            target += profile.riskyChoiceBonusMs
        }
        if (context.legalMoveCount >= profile.manyMovesThreshold) {
            target += profile.manyMovesBonusMs
        }
        if (context.scoreLoss(selectedMove)?.let { it > 0 } == true) {
            target += profile.nonBestChoiceBonusMs
        }
        if (context.position.ply >= profile.endgameStartPly) {
            target += profile.endgameBonusMs
        }
        return target.coerceIn(profile.minMs, profile.maxMs)
    }
}

/** Built-in personalities used by the Standard campaign. Future personalities compose the same two policies. */
object StandardAiPersonalities {
    fun natural(
        level: StandardAiLevel,
        randomUnit: () -> Double = { Random.Default.nextDouble() },
    ): StandardAiPersonality = StandardAiPersonality(
        id = StandardAiPersonalityId.NATURAL,
        moveSelectionPolicy = StandardNaturalPlayPolicy(level, randomUnit),
        thinkTimePolicy = StandardAdaptiveThinkTimePolicy(
            StandardAdaptiveThinkTimeProfile(
                baseMs = 520L,
                forcedMoveMs = 160L,
                ambiguousGapThreshold = 2,
                ambiguousBonusMs = 180L,
                riskyBestScoreThreshold = 3,
                riskyChoiceBonusMs = 220L,
                allNegativeBonusMs = 360L,
                manyMovesThreshold = 8,
                manyMovesBonusMs = 100L,
                nonBestChoiceBonusMs = 120L,
                endgameStartPly = 44,
                endgameBonusMs = 80L,
                maxMs = 1_300L,
            ),
        ),
    )

    fun serious(): StandardAiPersonality = StandardAiPersonality(
        id = StandardAiPersonalityId.SERIOUS,
        moveSelectionPolicy = StandardBestMovePolicy,
        thinkTimePolicy = StandardAdaptiveThinkTimePolicy(
            StandardAdaptiveThinkTimeProfile(
                baseMs = 500L,
                forcedMoveMs = 140L,
                ambiguousGapThreshold = 2,
                ambiguousBonusMs = 240L,
                riskyBestScoreThreshold = 3,
                riskyChoiceBonusMs = 180L,
                allNegativeBonusMs = 300L,
                manyMovesThreshold = 8,
                manyMovesBonusMs = 120L,
                nonBestChoiceBonusMs = 0L,
                endgameStartPly = 44,
                endgameBonusMs = 120L,
                maxMs = 1_300L,
            ),
        ),
    )
}
