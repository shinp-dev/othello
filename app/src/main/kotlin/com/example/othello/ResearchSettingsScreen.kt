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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
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
                error = it.message ?: context.getString(R.string.research_update_failed)
            }
            busy = false
        }
    }

    LaunchedEffect(repository) {
        runCatching { repository.status() }
            .onSuccess { status = it }
            .onFailure { error = it.message ?: context.getString(R.string.research_load_failed) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        SettingsHeader(appString(R.string.research_participation), onBack)
        when {
            status == null && error == null -> CircularProgressIndicator()
            status != null -> ResearchStatus(status = requireNotNull(status))
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val current = status
        val appHasCurrentConsent = current == null || current.currentConsentVersion == ResearchConsent.version
        if (!appHasCurrentConsent) {
            Text(
                appString(R.string.new_consent_available),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (current?.participationOn == true) {
            OutlinedButton(
                onClick = { update(false) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(appString(R.string.research_off)) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = consentChecked,
                    onCheckedChange = { consentChecked = it },
                    enabled = !busy && appHasCurrentConsent,
                )
                Text(appString(R.string.confirm_consent))
            }
            Button(
                onClick = { showConsentDialog = true },
                enabled = !busy && appHasCurrentConsent && current != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(appString(if (current?.reconsentRequired == true) R.string.reconsent_on else R.string.research_on))
            }
        }

        if (current?.participationOn == true) {
            TextButton(onClick = { showConsentDialog = true }, enabled = !busy) {
                Text(appString(R.string.review_consent))
            }
        }
    }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false; consentChecked = false },
            title = { Text(appString(R.string.research_consent_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appString(R.string.consent_version, ResearchConsent.version), style = MaterialTheme.typography.titleSmall)
                    localizedResearchConsentStatements().forEach { statement -> Text("• $statement") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consentChecked, onCheckedChange = { consentChecked = it })
                        Text(appString(R.string.confirm_consent))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showConsentDialog = false; consentChecked = false; update(true) },
                    enabled = consentChecked && !busy,
                ) { Text(appString(R.string.consent_on)) }
            },
            dismissButton = { TextButton(onClick = { showConsentDialog = false; consentChecked = false }) { Text(appString(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ResearchStatus(status: ResearchParticipationStatus) {
    val stateText = when {
        status.reconsentRequired -> appString(R.string.reconsent_required)
        status.participationOn -> "ON"
        else -> "OFF"
    }
    Text(appString(R.string.research_status, stateText), style = MaterialTheme.typography.titleMedium)
    Text(appString(R.string.consent_status, status.agreedConsentVersion?.let { appString(R.string.consent_version_short, it) } ?: appString(R.string.not_agreed)))
    Text(appString(R.string.research_link, if (status.researchSubjectLinked) appString(R.string.status_complete) else appString(R.string.status_ready)))
    Text(
        appString(R.string.research_progress, status.qualifyingGameCount, status.requiredGameCount, status.windowDays) +
            if (status.canViewResearchData) "\n${appString(R.string.research_viewable)}" else "",
    )
    Text(appString(R.string.data_provision, if (status.collectionAllowed && status.collectionEnabled) appString(R.string.status_enabled) else appString(R.string.status_disabled)), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun localizedResearchConsentStatements(): List<String> = listOf(
    appString(R.string.consent_statement_1),
    appString(R.string.consent_statement_2),
    appString(R.string.consent_statement_3),
    appString(R.string.consent_statement_4),
    appString(R.string.consent_statement_5),
    appString(R.string.consent_statement_6),
    appString(R.string.consent_statement_7),
    appString(R.string.consent_statement_8),
)
