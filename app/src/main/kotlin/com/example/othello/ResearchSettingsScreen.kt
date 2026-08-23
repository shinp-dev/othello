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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.othello.designsystem.ChanrivaSpacing
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
    var showConsentDialog by remember { mutableStateOf(false) }

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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        SettingsHeader("研究参加", onBack)
        when {
            status == null && error == null -> CircularProgressIndicator()
            status != null -> ResearchStatus(status = requireNotNull(status))
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

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
                onClick = { showConsentDialog = true },
                enabled = !busy && appHasCurrentConsent && current != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (current?.reconsentRequired == true) "再同意して研究参加をONにする" else "研究参加をONにする")
            }
        }

        if (current?.participationOn == true) {
            TextButton(onClick = { showConsentDialog = true }, enabled = !busy) {
                Text("同意内容を確認")
            }
        }
    }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false; consentChecked = false },
            title = { Text("研究参加への同意") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("同意内容：バージョン${ResearchConsent.version}", style = MaterialTheme.typography.titleSmall)
                    ResearchConsent.statements.forEach { statement -> Text("・$statement") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consentChecked, onCheckedChange = { consentChecked = it })
                        Text("内容を確認し、同意します")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showConsentDialog = false; consentChecked = false; update(true) },
                    enabled = consentChecked && !busy,
                ) { Text("同意してONにする") }
            },
            dismissButton = { TextButton(onClick = { showConsentDialog = false; consentChecked = false }) { Text("キャンセル") } },
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
    Text("研究参加：$stateText", style = MaterialTheme.typography.titleMedium)
    Text("同意内容：${status.agreedConsentVersion?.let { "バージョン$it" } ?: "未同意"}")
    Text("研究データとの連携：${if (status.researchSubjectLinked) "完了" else "準備中"}")
    Text(
        "研究データ閲覧まで：${status.qualifyingGameCount} / ${status.requiredGameCount}局（直近${status.windowDays}日）" +
            if (status.canViewResearchData) "\n閲覧できます" else "",
    )
    Text("データ提供：${if (status.collectionAllowed && status.collectionEnabled) "有効" else "停止中"}", style = MaterialTheme.typography.bodySmall)
}
