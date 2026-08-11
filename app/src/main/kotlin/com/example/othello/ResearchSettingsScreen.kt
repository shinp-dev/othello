package com.example.othello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.othello.research.ResearchConsent
import com.example.othello.research.ResearchParticipationRepository
import com.example.othello.research.ResearchParticipationStatus
import kotlinx.coroutines.launch

@Composable
internal fun ResearchSettingsScreen(
    repository: ResearchParticipationRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ResearchParticipationStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var consentChecked by remember { mutableStateOf(false) }

    fun update(enabled: Boolean) {
        busy = true
        error = null
        scope.launch {
            runCatching {
                repository.setParticipation(
                    enabled = enabled,
                    acceptedConsentVersion = if (enabled) ResearchConsent.version else null,
                )
            }.onSuccess {
                status = it
                consentChecked = false
            }.onFailure {
                error = it.message ?: "研究参加状態を更新できませんでした"
            }
            busy = false
        }
    }

    LaunchedEffect(repository) {
        runCatching { repository.status() }
            .onSuccess { status = it }
            .onFailure { error = it.message ?: "研究参加状態を取得できませんでした" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader("研究参加", onBack)
        Text("人間が実際に選んだ手を、個人単位では公開せず、集合・統計データとして研究に役立てます。")

        when {
            status == null && error == null -> CircularProgressIndicator()
            status != null -> ResearchStatus(status = requireNotNull(status))
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("同意内容（version ${ResearchConsent.version}）", style = MaterialTheme.typography.titleMedium)
        ResearchConsent.statements.forEach { statement -> Text("・$statement") }

        val current = status
        val appHasCurrentConsent = current == null || current.currentConsentVersion == ResearchConsent.version
        if (!appHasCurrentConsent) {
            Text(
                "新しい同意内容があります。アプリを更新して確認してください。",
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (current?.participationOn == true) {
            OutlinedButton(
                onClick = { update(false) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("研究参加をOFFにする") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = consentChecked,
                    onCheckedChange = { consentChecked = it },
                    enabled = !busy && appHasCurrentConsent,
                )
                Text("上記の内容を確認し、研究参加に同意します")
            }
            Button(
                onClick = { update(true) },
                enabled = !busy && consentChecked && appHasCurrentConsent && current != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (current?.reconsentRequired == true) "再同意して研究参加をONにする" else "研究参加をONにする")
            }
        }

        Text(
            "研究棋譜の収集は準備中です。このバージョンでは研究データを収集しません。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ResearchStatus(status: ResearchParticipationStatus) {
    val stateText = when {
        status.reconsentRequired -> "再同意が必要"
        status.participationOn -> "ON"
        else -> "OFF"
    }
    Text("現在の状態: $stateText", style = MaterialTheme.typography.titleMedium)
    Text("現在のConsent version: ${status.currentConsentVersion}")
    Text("同意済みversion: ${status.agreedConsentVersion ?: "なし"}")
    Text("研究subject: ${if (status.researchSubjectLinked) "link済み" else "未作成"}")
    val periodText = when {
        !status.currentPeriodExists -> "なし"
        status.reconsentRequired -> "open（再同意まで無効）"
        else -> "進行中"
    }
    Text("参加period: $periodText")
    Text("集合研究データ閲覧資格: 未達（${status.qualifyingGameCount}/${status.requiredGameCount}局・直近${status.windowDays}日）")
}
