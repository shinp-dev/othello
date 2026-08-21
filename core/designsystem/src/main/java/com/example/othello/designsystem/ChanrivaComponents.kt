package com.example.othello.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ChanrivaScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            OutlinedButton(onClick = onBack, enabled = backEnabled) { Text("戻る") }
            Spacer(Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 2.dp, height = 22.dp).background(ChanrivaColors.accent))
            Spacer(Modifier.size(ChanrivaSpacing.control))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun ChanrivaNavigationRow(
    title: String,
    onClick: (() -> Unit)?,
    supportingText: String? = null,
) {
    val enabled = onClick != null
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { role = Role.Button }
                .clickable(enabled = enabled) { onClick?.invoke() }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else ChanrivaColors.textDisabled,
                )
                supportingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else ChanrivaColors.textDisabled,
                    )
                }
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = if (enabled) ChanrivaColors.accent else ChanrivaColors.textDisabled,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun ChanrivaDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.24f),
            disabledContentColor = Color.White.copy(alpha = 0.38f),
        ),
        content = { content() },
    )
}
