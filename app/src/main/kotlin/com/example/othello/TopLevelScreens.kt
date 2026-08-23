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
    onAccount: () -> Unit,
    onResearchInfo: () -> Unit,
    onPrivacy: () -> Unit,
    onAbout: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ChanrivaScreenHeader("その他")
        ChanrivaNavigationRow("アカウント", onAccount)
        ChanrivaNavigationRow("研究データについて", onResearchInfo)
        ChanrivaNavigationRow("プライバシーポリシー", onPrivacy, trailingLabel = "Web")
        ChanrivaNavigationRow("このアプリについて", onAbout)
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
        ChanrivaScreenHeader("研究データについて", onBack)
        Text("人間が実際に選んだ手を集め、リバーシの傾向や意思決定を研究するためのデータとして役立てます。")
        Text("提供するもの", style = MaterialTheme.typography.titleMedium)
        Text("研究参加中に成立した対象のオンライン対局データを提供します。")
        Text("公開・利用のルール", style = MaterialTheme.typography.titleMedium)
        Text("・個別プレイヤー単位では公開しません。")
        Text("・集合・統計データとして利用・公開します。")
        Text("・研究用のrawデータを一般公開しません。")
        Text("参加をOFFにした後", style = MaterialTheme.typography.titleMedium)
        Text("OFFにすると、それ以降に成立する対局からの新しい提供を停止します。参加中に提供済みのデータは、個人との対応を切った集合研究データとして利用を続ける場合があります。アカウント削除後も同様です。")
        Text("集合研究データの閲覧", style = MaterialTheme.typography.titleMedium)
        Text("研究参加と所定の提供条件を満たした場合に閲覧できます。")
        Text(RESEARCH_PUBLICATION_PRIVACY_COPY, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onResearchSettings, modifier = Modifier.fillMaxWidth()) {
            Text("研究参加設定を開く")
        }
    }
}
