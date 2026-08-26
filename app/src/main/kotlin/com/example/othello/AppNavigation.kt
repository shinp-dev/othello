package com.example.othello

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.example.othello.designsystem.ChanrivaColors

internal enum class AppDestination(@StringRes val labelRes: Int, @StringRes val shortLabelRes: Int? = null) {
    PLAY(R.string.play, R.string.play_short),
    STUDY(R.string.study, R.string.study_short),
    SETTINGS(R.string.settings, R.string.settings_short),
    MORE(R.string.more, R.string.more_short),
    ONLINE_RECORDS(R.string.online_records),
    OFFLINE_RECORDS(R.string.offline_records),
    POSITION_REVIEW_HOME(R.string.position_review),
    POSITION_REVIEW_INPUT(R.string.new_position_review),
    POSITION_REVIEW(R.string.position_review),
    REVIEW(R.string.review),
    ACCOUNT(R.string.account),
    ACCOUNT_DELETION(R.string.account_deletion),
    MATCH_SETTINGS(R.string.match_settings),
    AI_MATCH_SETTINGS(R.string.ai_match_settings),
    MATCH_COMMON_SETTINGS(R.string.match_common_settings),
    REVIEW_SETTINGS(R.string.review_settings),
    COMMON_SETTINGS(R.string.common_settings),
    LOCAL_AI_SETUP(R.string.local_ai_setup),
    RESEARCH_SETTINGS(R.string.research_participation),
    RESEARCH_INFO(R.string.research_info),
    ABOUT(R.string.about_app),
    OSS_LICENSES(R.string.oss_licenses),
    EDAX_LICENSE(R.string.edax_license),
    OTHER_OSS_LICENSES(R.string.other_oss_licenses),
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
    AppDestination.POSITION_REVIEW_HOME -> AppDestination.STUDY
    AppDestination.POSITION_REVIEW_INPUT,
    AppDestination.POSITION_REVIEW -> AppDestination.POSITION_REVIEW_HOME
    AppDestination.REVIEW -> reviewParent
    AppDestination.RESEARCH_INFO,
    AppDestination.ABOUT -> AppDestination.MORE
    AppDestination.ACCOUNT -> AppDestination.MORE
    AppDestination.ACCOUNT_DELETION -> AppDestination.ACCOUNT
    AppDestination.MATCH_SETTINGS -> AppDestination.SETTINGS
    AppDestination.AI_MATCH_SETTINGS,
    AppDestination.MATCH_COMMON_SETTINGS -> AppDestination.MATCH_SETTINGS
    AppDestination.REVIEW_SETTINGS -> AppDestination.SETTINGS
    AppDestination.RESEARCH_SETTINGS -> researchSettingsParent
    AppDestination.COMMON_SETTINGS -> commonSettingsParent
    AppDestination.OSS_LICENSES -> AppDestination.ABOUT
    AppDestination.EDAX_LICENSE,
    AppDestination.OTHER_OSS_LICENSES -> AppDestination.OSS_LICENSES
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
                icon = { Text(stringResource(requireNotNull(destination.shortLabelRes))) },
                label = { Text(stringResource(destination.labelRes)) },
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
