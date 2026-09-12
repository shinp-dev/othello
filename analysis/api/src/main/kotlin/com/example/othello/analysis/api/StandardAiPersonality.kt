package com.example.othello.analysis.api

import com.example.othello.game.GameState
import com.example.othello.game.Position
import kotlin.math.roundToInt
import kotlin.random.Random

enum class StandardAiPersonalityId {
    NATURAL,
    SERIOUS,
}

enum class StandardTensionLevel {
    CALM,
    TENSE,
    CRITICAL,
}

/**
 * Shared, evaluation-backed view of the current decision.
 *
 * New personalities and presentation rules should consume this context rather than reaching
 * into Edax or UI state. This keeps move choice, thinking time, and tension reusable.
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

fun interface StandardTensionPolicy {
    /** Pure domain signal. Sound, board animation, and haptics are handled outside analysis. */
    fun evaluate(context: StandardDecisionContext): StandardTensionLevel
}

/**
 * A personality is a composition of independent decision policies.
 * Adding a personality should not require changes to StandardAiEngine or presentation code.
 */
data class StandardAiPersonality(
    val id: StandardAiPersonalityId,
    val moveSelectionPolicy: StandardMoveSelectionPolicy,
    val thinkTimePolicy: StandardThinkTimePolicy,
    val tensionPolicy: StandardTensionPolicy,
)

/** Generic weighted selector that can back many personalities without duplicating selection code. */
data class StandardWeightedMoveProfile(
    val openingWeights: List<Double>,
    val midgameWeights: List<Double>,
    val endgameWeights: List<Double>,
    val openingMaxScoreLoss: Int,
    val endgameMaxScoreLoss: Int,
    val midgamePly: Double = 25.0,
    val endgamePly: Double = 50.0,
) {
    init {
        require(openingWeights.isNotEmpty())
        require(openingWeights.size == midgameWeights.size)
        require(openingWeights.size == endgameWeights.size)
        require(
            openingWeights.all { it >= 0.0 } &&
                midgameWeights.all { it >= 0.0 } &&
                endgameWeights.all { it >= 0.0 },
        )
        require(openingMaxScoreLoss >= 0 && endgameMaxScoreLoss >= 0)
        require(midgamePly > 0.0 && endgamePly > midgamePly)
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

        val ply = context.position.ply.toDouble().coerceAtLeast(0.0)
        val candidateLimit = profile.openingWeights.size
        val weights = when {
            ply <= profile.midgamePly -> interpolateWeights(
                profile.openingWeights,
                profile.midgameWeights,
                (ply / profile.midgamePly).coerceIn(0.0, 1.0),
            )
            else -> interpolateWeights(
                profile.midgameWeights,
                profile.endgameWeights,
                ((ply - profile.midgamePly) / (profile.endgamePly - profile.midgamePly))
                    .coerceIn(0.0, 1.0),
            )
        }
        val maxScoreLoss = lerp(
            profile.openingMaxScoreLoss.toDouble(),
            profile.endgameMaxScoreLoss.toDouble(),
            (ply / profile.endgamePly).coerceIn(0.0, 1.0),
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

        fun interpolateWeights(
            start: List<Double>,
            end: List<Double>,
            progress: Double,
        ): DoubleArray = DoubleArray(start.size) { index ->
            lerp(start[index], end[index], progress)
        }

        fun lerp(start: Double, end: Double, progress: Double): Double =
            start + (end - start) * progress
    }
}

/**
 * Initial animal-pack campaign tuning.
 *
 * Lv1-Lv4 are intentionally beginner-friendly: they consider more ranked moves and permit
 * larger evaluation losses. Lv5-Lv8 keep the corresponding animal's miss probability and
 * candidate count, while sharply reducing the maximum evaluation loss so the wild versions
 * retain their character without playing catastrophic mistakes.
 */
internal fun standardCampaignMoveProfile(level: StandardAiLevel): StandardWeightedMoveProfile = when (level) {
    StandardAiLevel.LV1 -> campaignMoveProfile(
        candidateLimit = 8,
        openingBestMoveProbability = 0.05,
        midgameBestMoveProbability = 0.10,
        endgameBestMoveProbability = 0.20,
        openingMaxScoreLoss = 30,
        endgameMaxScoreLoss = 18,
    )
    StandardAiLevel.LV2 -> campaignMoveProfile(
        candidateLimit = 7,
        openingBestMoveProbability = 0.10,
        midgameBestMoveProbability = 0.18,
        endgameBestMoveProbability = 0.30,
        openingMaxScoreLoss = 24,
        endgameMaxScoreLoss = 14,
    )
    StandardAiLevel.LV3 -> campaignMoveProfile(
        candidateLimit = 6,
        openingBestMoveProbability = 0.16,
        midgameBestMoveProbability = 0.28,
        endgameBestMoveProbability = 0.45,
        openingMaxScoreLoss = 18,
        endgameMaxScoreLoss = 10,
    )
    StandardAiLevel.LV4 -> campaignMoveProfile(
        candidateLimit = 5,
        openingBestMoveProbability = 0.24,
        midgameBestMoveProbability = 0.40,
        endgameBestMoveProbability = 0.60,
        openingMaxScoreLoss = 13,
        endgameMaxScoreLoss = 7,
    )
    StandardAiLevel.LV5 -> campaignMoveProfile(
        candidateLimit = 8,
        openingBestMoveProbability = 0.05,
        midgameBestMoveProbability = 0.10,
        endgameBestMoveProbability = 0.20,
        openingMaxScoreLoss = 8,
        endgameMaxScoreLoss = 4,
    )
    StandardAiLevel.LV6 -> campaignMoveProfile(
        candidateLimit = 7,
        openingBestMoveProbability = 0.10,
        midgameBestMoveProbability = 0.18,
        endgameBestMoveProbability = 0.30,
        openingMaxScoreLoss = 6,
        endgameMaxScoreLoss = 3,
    )
    StandardAiLevel.LV7 -> campaignMoveProfile(
        candidateLimit = 6,
        openingBestMoveProbability = 0.16,
        midgameBestMoveProbability = 0.28,
        endgameBestMoveProbability = 0.45,
        openingMaxScoreLoss = 4,
        endgameMaxScoreLoss = 2,
    )
    StandardAiLevel.LV8 -> campaignMoveProfile(
        candidateLimit = 5,
        openingBestMoveProbability = 0.24,
        midgameBestMoveProbability = 0.40,
        endgameBestMoveProbability = 0.60,
        openingMaxScoreLoss = 3,
        endgameMaxScoreLoss = 1,
    )
}

private fun campaignMoveProfile(
    candidateLimit: Int,
    openingBestMoveProbability: Double,
    midgameBestMoveProbability: Double,
    endgameBestMoveProbability: Double,
    openingMaxScoreLoss: Int,
    endgameMaxScoreLoss: Int,
): StandardWeightedMoveProfile = StandardWeightedMoveProfile(
    openingWeights = phaseWeights(candidateLimit, openingBestMoveProbability),
    midgameWeights = phaseWeights(candidateLimit, midgameBestMoveProbability),
    endgameWeights = phaseWeights(candidateLimit, endgameBestMoveProbability),
    openingMaxScoreLoss = openingMaxScoreLoss,
    endgameMaxScoreLoss = endgameMaxScoreLoss,
)

private fun phaseWeights(candidateLimit: Int, bestMoveProbability: Double): List<Double> {
    require(candidateLimit >= 2)
    require(bestMoveProbability in 0.0..1.0)
    val nonBestWeight = (1.0 - bestMoveProbability) / (candidateLimit - 1)
    return buildList(candidateLimit) {
        add(bestMoveProbability)
        repeat(candidateLimit - 1) { add(nonBestWeight) }
    }
}

/** Current Lv1-Lv8 campaign personality, implemented as a data-driven weighted selector. */
class StandardNaturalPlayPolicy(
    level: StandardAiLevel,
    randomUnit: () -> Double = { Random.Default.nextDouble() },
) : StandardMoveSelectionPolicy {
    private val delegate: StandardMoveSelectionPolicy =
        StandardWeightedMoveSelectionPolicy(standardCampaignMoveProfile(level), randomUnit)

    override fun select(context: StandardDecisionContext): Position? = delegate.select(context)
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

/** Tuneable, UI-independent mapping from evaluated positions to presentation tension. */
data class StandardAdaptiveTensionProfile(
    val ambiguousGapThreshold: Int = 2,
    val riskyBestScoreThreshold: Int = 3,
    val lowMobilityThreshold: Int = 3,
    val endgameStartPly: Int = 44,
    val tenseScore: Int = 2,
    val criticalScore: Int = 4,
) {
    init {
        require(ambiguousGapThreshold >= 0)
        require(lowMobilityThreshold >= 2)
        require(endgameStartPly >= 0)
        require(tenseScore >= 1)
        require(criticalScore > tenseScore)
    }
}

class StandardAdaptiveTensionPolicy(
    private val profile: StandardAdaptiveTensionProfile = StandardAdaptiveTensionProfile(),
) : StandardTensionPolicy {
    override fun evaluate(context: StandardDecisionContext): StandardTensionLevel {
        if (context.legalMoveCount <= 1) return StandardTensionLevel.CALM

        var tensionScore = 0
        if (context.topScoreGap?.let { it <= profile.ambiguousGapThreshold } == true) tensionScore += 2
        if (context.allCandidatesNegative) {
            tensionScore += 2
        } else if (
            context.hasNegativeCandidate &&
            context.bestScore?.let { it <= profile.riskyBestScoreThreshold } == true
        ) {
            tensionScore += 1
        }
        if (context.legalMoveCount <= profile.lowMobilityThreshold) tensionScore += 1
        if (context.position.ply >= profile.endgameStartPly) tensionScore += 1

        return when {
            tensionScore >= profile.criticalScore -> StandardTensionLevel.CRITICAL
            tensionScore >= profile.tenseScore -> StandardTensionLevel.TENSE
            else -> StandardTensionLevel.CALM
        }
    }
}

/** Built-in personalities used by the Standard campaign. Future personalities compose the same policies. */
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
        tensionPolicy = StandardAdaptiveTensionPolicy(),
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
        tensionPolicy = StandardAdaptiveTensionPolicy(),
    )
}
