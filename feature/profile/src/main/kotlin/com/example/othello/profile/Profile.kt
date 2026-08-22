package com.example.othello.profile

import java.time.LocalDate

interface AccountDeletionRepository {
    /** Queues a server-side deletion/anonymization request; Android never receives service-role authority. */
    suspend fun requestDeletion(): String
}

data class YesterdayRanking(
    val snapshotDate: String,
    val rank: Int,
    val activeUserCount: Int,
    val topPercentile: Double,
) {
    init {
        require(runCatching { LocalDate.parse(snapshotDate) }.isSuccess)
        require(rank > 0)
        require(activeUserCount >= rank)
        require(topPercentile > 0.0 && topPercentile <= 100.0)
    }
}

data class RatingSummary(
    val currentRating: Int,
    val yesterdayRanking: YesterdayRanking?,
)

interface CurrentRatingRepository {
    suspend fun getCurrentRating(): Int

    /** Additive summary API; old callers can continue reading only current rating. */
    suspend fun getRatingSummary(): RatingSummary = RatingSummary(getCurrentRating(), null)
}
