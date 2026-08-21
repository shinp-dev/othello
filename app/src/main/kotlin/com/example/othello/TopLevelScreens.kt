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
import androidx.compose.ui.Modifier
import com.example.othello.designsystem.ChanrivaNavigationRow
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.research.RESEARCH_PUBLICATION_PRIVACY_COPY
import com.example.othello.research.ResearchConsent

@Composable
internal fun StudyScreen(
    onOnlineRecords: () -> Unit,
    onOfflineRecords: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ChanrivaScreenHeader("検討")
        ChanrivaNavigationRow(
            title = "オンライン棋譜",
            supportingText = "サーバー上の対局棋譜",
            onClick = onOnlineRecords,
        )
        ChanrivaNavigationRow(
            title = "オフライン棋譜",
            supportingText = "この端末に保存した棋譜",
            onClick = onOfflineRecords,
        )
    }
}

@Composable
internal fun MoreScreen(
    canDeleteAccount: Boolean,
    onResearchInfo: () -> Unit,
    onPrivacy: () -> Unit,
    onAccountDeletion: (() -> Unit)?,
    onAbout: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ChanrivaScreenHeader("その他")
        ChanrivaNavigationRow("研究データについて", onResearchInfo)
        ChanrivaNavigationRow("プライバシーポリシー", onPrivacy)
        ChanrivaNavigationRow("アカウント削除", onAccountDeletion)
        if (!canDeleteAccount) {
            Text("アカウント削除にはログインが必要です", style = MaterialTheme.typography.bodySmall)
        }
        ChanrivaNavigationRow("このアプリについて", onAbout)
    }
}

@Composable
internal fun ResearchInfoScreen(
    onBack: () -> Unit,
    onResearchSettings: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        ChanrivaScreenHeader("研究データについて", onBack)
        Text("人間が実際に選んだ手を、個人単位では公開せず、集合・統計データとして研究に役立てます。")
        Text(RESEARCH_PUBLICATION_PRIVACY_COPY, style = MaterialTheme.typography.bodySmall)
        Text("研究参加時の同意内容", style = MaterialTheme.typography.titleMedium)
        ResearchConsent.statements.forEach { statement -> Text("・$statement") }
        if (onResearchSettings != null) {
            OutlinedButton(onClick = onResearchSettings, modifier = Modifier.fillMaxWidth()) {
                Text("研究参加設定を開く")
            }
        } else {
            Text("研究参加設定にはログインが必要です", style = MaterialTheme.typography.bodySmall)
        }
    }
}
