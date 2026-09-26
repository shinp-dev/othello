package com.example.othello.profile

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

private val TOKYO_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

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

fun tokyoYesterday(clock: Clock = Clock.systemUTC()): LocalDate =
    LocalDate.now(clock.withZone(TOKYO_ZONE)).minusDays(1)

fun YesterdayRanking.isTokyoYesterday(clock: Clock = Clock.systemUTC()): Boolean =
    LocalDate.parse(snapshotDate) == tokyoYesterday(clock)

interface CurrentRatingRepository {
    suspend fun getCurrentRating(): Int

    /** Additive summary API; old callers can continue reading only current rating. */
    suspend fun getRatingSummary(): RatingSummary = RatingSummary(getCurrentRating(), null)
}

/** A selectable display-name row returned from the active name catalog. */
data class PlayDisplayName(
    val id: Long,
    val displayName: String,
    val isRare: Boolean,
    val sortOrder: Int,
)

/** Distinguishes an absent profile from a failed authenticated query. */
sealed interface PlayProfileLookup {
    data class Exists(val displayNameId: Long) : PlayProfileLookup
    data object Missing : PlayProfileLookup
    data class Failed(val cause: Throwable) : PlayProfileLookup
}

/**
 * Play-profile operations always act on the SDK's current authenticated user.
 * The caller cannot choose a user id for a profile lookup or insert.
 */
interface PlayProfileRepository {
    suspend fun currentAuthenticatedUserId(): String
    suspend fun findCurrentUserProfile(): PlayProfileLookup
    suspend fun getActiveDisplayNames(): List<PlayDisplayName>
    suspend fun insertCurrentUserProfile(displayNameId: Long)
}
