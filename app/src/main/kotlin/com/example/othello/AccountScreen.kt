package com.example.othello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
        ChanrivaScreenHeader("アカウント", onBack)
        Text("現在レート", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                loading -> "取得中…"
                ratingSummary != null -> ratingSummary!!.currentRating.toString()
                else -> "---"
            },
            style = MaterialTheme.typography.displaySmall,
        )
        if (ratingLoadFailed) {
            Text("レートを取得できませんでした", style = MaterialTheme.typography.bodySmall)
        }

        Column(verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact)) {
            Text("前日順位", style = MaterialTheme.typography.titleMedium)
            when {
                loading -> Text("取得中…", style = MaterialTheme.typography.bodySmall)
                ratingSummary?.yesterdayRanking != null -> {
                    val ranking = requireNotNull(ratingSummary?.yesterdayRanking)
                    Text(
                        "${String.format(java.util.Locale.ROOT, "%,d", ranking.rank)} / ${String.format(java.util.Locale.ROOT, "%,d", ranking.activeUserCount)}位",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text("上位 ${formatTopPercentile(ranking.topPercentile)}")
                }
                else -> Text("順位データはまだありません", style = MaterialTheme.typography.bodySmall)
            }
            Text("※日本時間の前日終了時点のレートをもとに算出", style = MaterialTheme.typography.bodySmall)
        }

        Column(verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact)) {
            Text("この端末の最高記録", style = MaterialTheme.typography.titleMedium)
            localBest?.let { best ->
                Text("上位 ${formatTopPercentile(best.topPercentile)}", style = MaterialTheme.typography.headlineSmall)
                Text(formatAchievementDate(best.achievedDate), style = MaterialTheme.typography.bodyMedium)
            } ?: Text("まだ記録がありません", style = MaterialTheme.typography.bodySmall)
            Text("※この画面を開いて確認した前日順位のみ記録されます", style = MaterialTheme.typography.bodySmall)
        }

        ChanrivaNavigationRow("アカウント削除", onAccountDeletion)
        OutlinedButton(onClick = onLogout, enabled = !logoutInProgress, modifier = Modifier.fillMaxWidth()) {
            Text(if (logoutInProgress) "ログアウト中…" else "ログアウト")
        }
        logoutError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
