package com.example.othello

import android.content.Context
import com.example.othello.profile.YesterdayRanking
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class LocalBestRating(
    val topPercentile: Double,
    val achievedDate: String,
) {
    init {
        require(topPercentile.isFinite() && topPercentile > 0.0 && topPercentile <= 100.0)
        require(runCatching { LocalDate.parse(achievedDate) }.isSuccess)
    }
}

internal fun betterLocalBest(current: LocalBestRating?, candidate: LocalBestRating): LocalBestRating = when {
    current == null -> candidate
    candidate.topPercentile < current.topPercentile -> candidate
    else -> current
}

/** Device-only best ranking, namespaced by Supabase Auth user ID. */
class RatingAchievementStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getBest(userId: String): LocalBestRating? {
        val keys = ratingAchievementPreferenceKeys(userId)
        val percentile = preferences.getString(keys.percentile, null)?.toDoubleOrNull() ?: return null
        val date = preferences.getString(keys.date, null)?.takeIf(String::isNotBlank) ?: return null
        return runCatching { LocalBestRating(percentile, date) }.getOrNull()
    }

    fun recordIfBetter(userId: String, ranking: YesterdayRanking): LocalBestRating {
        val candidate = LocalBestRating(ranking.topPercentile, ranking.snapshotDate)
        val current = getBest(userId)
        val next = betterLocalBest(current, candidate)
        if (next != current) {
            val keys = ratingAchievementPreferenceKeys(userId)
            preferences.edit()
                .putString(keys.percentile, next.topPercentile.toString())
                .putString(keys.date, next.achievedDate)
                .apply()
        }
        return next
    }

    private companion object {
        const val FILE_NAME = "chanriva_rating_achievements"
    }
}

internal data class RatingAchievementPreferenceKeys(val percentile: String, val date: String)

internal fun ratingAchievementPreferenceKeys(userId: String): RatingAchievementPreferenceKeys {
    require(userId.isNotBlank())
    return RatingAchievementPreferenceKeys(
        percentile = "best_${userId}_percentile",
        date = "best_${userId}_date",
    )
}

internal fun formatTopPercentile(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value)

internal fun formatYesterdayRanking(ranking: YesterdayRanking): String =
    "上位 ${formatTopPercentile(ranking.topPercentile)}　${String.format(Locale.ROOT, "%,d", ranking.rank)} / ${String.format(Locale.ROOT, "%,d", ranking.activeUserCount)}位"

internal fun formatAchievementDate(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT))
}.getOrDefault(isoDate.replace('-', '/'))
