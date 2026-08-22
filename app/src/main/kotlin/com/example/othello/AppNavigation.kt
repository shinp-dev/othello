package com.example.othello

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.othello.designsystem.ChanrivaColors

internal enum class AppDestination(val label: String, val shortLabel: String? = null) {
    PLAY("対局", "対"),
    STUDY("検討", "検"),
    SETTINGS("設定", "設"),
    MORE("その他", "他"),
    ONLINE_RECORDS("オンライン棋譜"),
    OFFLINE_RECORDS("オフライン棋譜"),
    REVIEW("棋譜レビュー"),
    ACCOUNT("アカウント"),
    ACCOUNT_DELETION("アカウント削除"),
    MATCH_SETTINGS("対局時設定"),
    REVIEW_SETTINGS("検討設定"),
    COMMON_SETTINGS("共通設定"),
    LOCAL_AI_SETUP("AIと対局"),
    RESEARCH_SETTINGS("研究参加"),
    RESEARCH_INFO("研究データについて"),
    ABOUT("このアプリについて"),
    OSS_LICENSES("オープンソースライセンス"),
}

internal val topLevelDestinations = listOf(
    AppDestination.PLAY,
    AppDestination.STUDY,
    AppDestination.SETTINGS,
    AppDestination.MORE,
)

internal fun AppDestination.isTopLevel(): Boolean = this in topLevelDestinations

internal fun backDestination(
    current: AppDestination,
    reviewParent: AppDestination = AppDestination.STUDY,
    commonSettingsParent: AppDestination = AppDestination.SETTINGS,
    researchSettingsParent: AppDestination = AppDestination.SETTINGS,
): AppDestination? = when (current) {
    AppDestination.PLAY -> null
    AppDestination.STUDY,
    AppDestination.SETTINGS,
    AppDestination.MORE,
    AppDestination.LOCAL_AI_SETUP -> AppDestination.PLAY
    AppDestination.ONLINE_RECORDS,
    AppDestination.OFFLINE_RECORDS -> AppDestination.STUDY
    AppDestination.REVIEW -> reviewParent
    AppDestination.RESEARCH_INFO,
    AppDestination.ABOUT -> AppDestination.MORE
    AppDestination.ACCOUNT -> AppDestination.MORE
    AppDestination.ACCOUNT_DELETION -> AppDestination.ACCOUNT
    AppDestination.MATCH_SETTINGS -> AppDestination.SETTINGS
    AppDestination.REVIEW_SETTINGS -> AppDestination.SETTINGS
    AppDestination.RESEARCH_SETTINGS -> researchSettingsParent
    AppDestination.COMMON_SETTINGS -> commonSettingsParent
    AppDestination.OSS_LICENSES -> AppDestination.ABOUT
}

@Composable
internal fun ChanrivaBottomNavigation(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
) {
    NavigationBar(containerColor = ChanrivaColors.surfaceElevated) {
        topLevelDestinations.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { Text(requireNotNull(destination.shortLabel)) },
                label = { Text(destination.label) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ChanrivaColors.accent,
                    selectedTextColor = ChanrivaColors.accent,
                    indicatorColor = ChanrivaColors.accentSoft,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
