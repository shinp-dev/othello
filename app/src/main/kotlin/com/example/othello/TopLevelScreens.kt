package com.example.othello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.othello.designsystem.ChanrivaNavigationRow
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.research.RESEARCH_PUBLICATION_PRIVACY_COPY

@Composable
internal fun StudyScreen(
    onPositionReview: () -> Unit,
    onTheoryExploration: () -> Unit,
    onOnlineRecords: () -> Unit,
    onOfflineRecords: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        ChanrivaScreenHeader(appString(R.string.study))
        StudyCategory(appString(R.string.study_position_analysis)) {
            ChanrivaNavigationRow(
                title = appString(R.string.position_review),
                supportingText = appString(R.string.position_review_supporting),
                onClick = onPositionReview,
                emphasized = true,
            )
        }
        StudyCategory(appString(R.string.study_theory_analysis)) {
            ChanrivaNavigationRow(
                title = appString(R.string.theory_exploration),
                titleBadge = appString(R.string.theory_enthusiast_recommended),
                supportingText = appString(R.string.theory_exploration_supporting),
                onClick = onTheoryExploration,
                emphasized = true,
            )
        }
        StudyCategory(appString(R.string.study_record_analysis)) {
            ChanrivaNavigationRow(
                title = appString(R.string.online_records),
                supportingText = appString(R.string.online_records_supporting),
                onClick = onOnlineRecords,
                emphasized = true,
            )
            ChanrivaNavigationRow(
                title = appString(R.string.offline_records),
                supportingText = appString(R.string.offline_records_supporting),
                onClick = onOfflineRecords,
                emphasized = true,
            )
        }
    }
}

@Composable
private fun StudyCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(content = content)
    }
}

@Composable
internal fun MoreScreen(
    onAccount: () -> Unit,
    onResearchInfo: () -> Unit,
    onAbout: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ChanrivaScreenHeader(appString(R.string.more))
        ChanrivaNavigationRow(appString(R.string.account), onAccount)
        ChanrivaNavigationRow(appString(R.string.research_info), onResearchInfo)
        ChanrivaNavigationRow(appString(R.string.about_app), onAbout)
    }
}

@Composable
internal fun ResearchInfoScreen(
    onBack: () -> Unit,
    onResearchSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        ChanrivaScreenHeader(appString(R.string.research_info), onBack, backLabel = appString(R.string.back))
        Text(appString(R.string.research_info_copy))
        Text(appString(R.string.research_provided), style = MaterialTheme.typography.titleMedium)
        Text(appString(R.string.research_online_games))
        Text(appString(R.string.research_rules), style = MaterialTheme.typography.titleMedium)
        Text(appString(R.string.research_no_individual))
        Text(appString(R.string.research_aggregate))
        Text(appString(R.string.research_no_raw))
        Text(appString(R.string.research_after_off), style = MaterialTheme.typography.titleMedium)
        Text(appString(R.string.research_after_off_copy))
        Text(appString(R.string.research_viewing), style = MaterialTheme.typography.titleMedium)
        Text(appString(R.string.research_viewing_copy))
        Text(appString(R.string.research_privacy_copy), style = MaterialTheme.typography.bodySmall)
        ChanrivaNavigationRow(
            title = appString(R.string.open_research_settings),
            onClick = onResearchSettings,
        )
    }
}
