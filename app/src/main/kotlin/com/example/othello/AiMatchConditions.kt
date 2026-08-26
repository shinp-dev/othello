package com.example.othello

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.edax.AiMatchConfiguration
import com.example.othello.analysis.edax.ImportedAnalysisFile

@Composable
internal fun AiMatchConditions(
    configuration: AiMatchConfiguration,
    undoUsed: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                appString(
                    R.string.ai_match_condition_summary,
                    configuration.moveSettings.level,
                    configuration.moveSettings.moveTimeMs,
                    appString(if (undoUsed) R.string.undo_used else R.string.undo_unused),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            AnalysisFileConditionRow(appString(R.string.eval_short_label), configuration.evaluationData)
            AnalysisFileConditionRow(appString(R.string.book_short_label), configuration.openingBook)
        }
    }
}

@Composable
private fun AnalysisFileConditionRow(label: String, file: ImportedAnalysisFile?) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall)
        if (file == null) {
            Text(appString(R.string.not_configured), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                text = file.fileName,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = " · ${localizedAnalysisFileSize(file.sizeBytes)} · ${shortSha256(file.sha256)}",
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun shortSha256(fullSha256: String): String = fullSha256.take(SHORT_SHA_LENGTH)

private const val SHORT_SHA_LENGTH = 8
