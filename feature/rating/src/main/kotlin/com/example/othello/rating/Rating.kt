package com.example.othello.rating

import kotlin.math.roundToInt

enum class RatingOutcome { WIN, LOSS, DRAW }
data class RatingChange(val current: Int, val peak: Int, val delta: Int)
data class RatingSnapshot(val current: Int, val peak: Int, val completedRatings: List<Int>)

interface RatingPolicy { fun apply(current: Int, opponent: Int, outcome: RatingOutcome): Int }

class EloRatingPolicy(private val kFactor: Int = 32) : RatingPolicy {
    override fun apply(current: Int, opponent: Int, outcome: RatingOutcome): Int {
        val expected = 1.0 / (1.0 + Math.pow(10.0, (opponent - current) / 400.0))
        val actual = when (outcome) { RatingOutcome.WIN -> 1.0; RatingOutcome.LOSS -> 0.0; RatingOutcome.DRAW -> 0.5 }
        return current + (kFactor * (actual - expected)).roundToInt()
    }
}

fun updateRating(current: Int, peak: Int, opponent: Int, outcome: RatingOutcome, policy: RatingPolicy): RatingChange {
    val next = policy.apply(current, opponent, outcome)
    return RatingChange(next, maxOf(peak, next), next - current)
}

sealed interface StableBand { data object Calculating : StableBand; data class Range(val low: Int, val high: Int) : StableBand }

class StableRatingPolicy(private val minimumSamples: Int = 5) {
    fun calculate(snapshot: RatingSnapshot): StableBand {
        if (snapshot.completedRatings.size < minimumSamples) return StableBand.Calculating
        val sorted = snapshot.completedRatings.sorted()
        fun percentile(p: Double): Int = sorted[((sorted.lastIndex) * p).roundToInt()]
        return StableBand.Range(percentile(0.2), percentile(0.8))
    }
}
