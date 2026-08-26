package com.example.othello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.othello.designsystem.ChanrivaNavigationRow
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.profile.CurrentRatingRepository
import com.example.othello.profile.RatingSummary
import kotlinx.coroutines.CancellationException

@Composable
internal fun AccountScreen(
    userId: String,
    currentRatingRepository: CurrentRatingRepository,
    ratingAchievementStore: RatingAchievementStore,
    logoutInProgress: Boolean,
    logoutError: String?,
    onBack: () -> Unit,
    onAccountDeletion: () -> Unit,
    onLogout: () -> Unit,
) {
    var ratingSummary by remember(userId) { mutableStateOf<RatingSummary?>(null) }
    var localBest by remember(userId, ratingAchievementStore) {
        mutableStateOf(ratingAchievementStore.getBest(userId))
    }
    var loading by remember(userId) { mutableStateOf(true) }
    var ratingLoadFailed by remember(userId) { mutableStateOf(false) }

    LaunchedEffect(userId, currentRatingRepository, ratingAchievementStore) {
        loading = true
        localBest = ratingAchievementStore.getBest(userId)
        try {
            ratingSummary = currentRatingRepository.getRatingSummary()
            ratingSummary?.yesterdayRanking?.let { ranking ->
                localBest = ratingAchievementStore.recordIfBetter(userId, ranking)
            }
            ratingLoadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ratingSummary = null
            ratingLoadFailed = true
        } finally {
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        ChanrivaScreenHeader(appString(R.string.account), onBack, backLabel = appString(R.string.back))
        Text(appString(R.string.current_rating), style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                loading -> appString(R.string.loading)
                ratingSummary != null -> ratingSummary!!.currentRating.toString()
                else -> "---"
            },
            style = MaterialTheme.typography.displaySmall,
        )
        if (ratingLoadFailed) {
            Text(appString(R.string.rating_load_failed), style = MaterialTheme.typography.bodySmall)
        }

        Column(verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact)) {
            Text(appString(R.string.previous_day_ranking), style = MaterialTheme.typography.titleMedium)
            when {
                loading -> Text(appString(R.string.loading), style = MaterialTheme.typography.bodySmall)
                ratingSummary?.yesterdayRanking != null -> {
                    val ranking = requireNotNull(ratingSummary?.yesterdayRanking)
                    Text(
                        appString(
                            R.string.rank_format,
                            String.format(java.util.Locale.ROOT, "%,d", ranking.rank),
                            String.format(java.util.Locale.ROOT, "%,d", ranking.activeUserCount),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(appString(R.string.top_percentile, formatTopPercentile(ranking.topPercentile)))
                }
                else -> Text(appString(R.string.no_ranking_yet), style = MaterialTheme.typography.bodySmall)
            }
            Text(appString(R.string.rating_previous_day_note), style = MaterialTheme.typography.bodySmall)
        }

        Column(verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact)) {
            Text(appString(R.string.best_local_record), style = MaterialTheme.typography.titleMedium)
            localBest?.let { best ->
                Text(appString(R.string.top_percentile, formatTopPercentile(best.topPercentile)), style = MaterialTheme.typography.headlineSmall)
                Text(formatAchievementDate(best.achievedDate), style = MaterialTheme.typography.bodyMedium)
            } ?: Text(appString(R.string.no_record_yet), style = MaterialTheme.typography.bodySmall)
            Text(appString(R.string.best_record_note), style = MaterialTheme.typography.bodySmall)
        }

        ChanrivaNavigationRow(appString(R.string.account_deletion), onAccountDeletion)
        ChanrivaNavigationRow(
            title = appString(if (logoutInProgress) R.string.logout_in_progress else R.string.logout),
            onClick = if (logoutInProgress) null else onLogout,
        )
        logoutError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
