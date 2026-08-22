package com.example.othello

import android.content.Context
import com.example.othello.profile.YesterdayRanking
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class LocalBestRating(
    val topPercentile: Double,
    val achievedDate: String,
)

internal fun betterLocalBest(current: LocalBestRating?, candidate: LocalBestRating): LocalBestRating = when {
    current == null -> candidate
    candidate.topPercentile < current.topPercentile -> candidate
    else -> current
}

/** Device-only best ranking, namespaced by Supabase Auth user ID. */
class RatingAchievementStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getBest(userId: String): LocalBestRating? {
        val percentile = preferences.getString(percentileKey(userId), null)?.toDoubleOrNull() ?: return null
        val date = preferences.getString(dateKey(userId), null)?.takeIf(String::isNotBlank) ?: return null
        return LocalBestRating(percentile, date)
    }

    fun recordIfBetter(userId: String, ranking: YesterdayRanking): LocalBestRating {
        val candidate = LocalBestRating(ranking.topPercentile, ranking.snapshotDate)
        val current = getBest(userId)
        val next = betterLocalBest(current, candidate)
        if (next != current) {
            preferences.edit()
                .putString(percentileKey(userId), next.topPercentile.toString())
                .putString(dateKey(userId), next.achievedDate)
                .apply()
        }
        return next
    }

    private fun percentileKey(userId: String) = "$KEY_PREFIX$userId$KEY_PERCENTILE_SUFFIX"
    private fun dateKey(userId: String) = "$KEY_PREFIX$userId$KEY_DATE_SUFFIX"

    private companion object {
        const val FILE_NAME = "chanriva_rating_achievements"
        const val KEY_PREFIX = "best_"
        const val KEY_PERCENTILE_SUFFIX = "_percentile"
        const val KEY_DATE_SUFFIX = "_date"
    }
}

internal fun formatTopPercentile(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value)

internal fun formatYesterdayRanking(ranking: YesterdayRanking): String =
    "上位 ${formatTopPercentile(ranking.topPercentile)}　${String.format(Locale.ROOT, "%,d", ranking.rank)} / ${String.format(Locale.ROOT, "%,d", ranking.activeUserCount)}位"

internal fun formatAchievementDate(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT))
}.getOrDefault(isoDate.replace('-', '/'))
