package com.example.othello

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing

@Composable
internal fun StandardWinningTipsRoute(onBack: () -> Unit) {
    var selectedTipId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedIndex = standardWinningTips.indexOfFirst { it.id == selectedTipId }

    BackHandler(enabled = selectedIndex >= 0) {
        selectedTipId = null
    }

    if (selectedIndex < 0) {
        StandardWinningTipsIndexScreen(
            onBack = onBack,
            onOpenTip = { selectedTipId = it.id },
        )
    } else {
        StandardWinningTipDetailScreen(
            tipIndex = selectedIndex,
            onBack = { selectedTipId = null },
            onPrevious = {
                selectedTipId = standardWinningTips.getOrNull(selectedIndex - 1)?.id
            },
            onNext = {
                selectedTipId = standardWinningTips.getOrNull(selectedIndex + 1)?.id
            },
        )
    }
}

@Composable
private fun StandardWinningTipsIndexScreen(
    onBack: () -> Unit,
    onOpenTip: (StandardWinningTip) -> Unit,
) {
    StandardTipsSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_winning_tips_title),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            text = appString(R.string.standard_winning_tips_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StandardWinningTipTier.entries.forEach { tier ->
            StandardTipTierSectionHeader(tier)
            if (tier == StandardWinningTipTier.EXPERT) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = appString(R.string.standard_winning_tips_expert_supporting),
                        modifier = Modifier.padding(ChanrivaSpacing.control),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            standardWinningTips.filter { it.tier == tier }.forEach { tip ->
                Card(
                    onClick = { onOpenTip(tip) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ChanrivaColors.surfaceElevated),
                ) {
                    Column(
                        modifier = Modifier.padding(ChanrivaSpacing.section),
                        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
                    ) {
                        if (tier == StandardWinningTipTier.EXPERT) {
                            StandardTipTierBadge(tier)
                        }
                        Text(
                            text = appString(tip.titleRes),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = appString(tip.leadRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StandardWinningTipDetailScreen(
    tipIndex: Int,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val tip = standardWinningTips[tipIndex]
    StandardTipsSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_winning_tips_title),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            text = appString(R.string.standard_winning_tips_progress, tipIndex + 1, standardWinningTips.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StandardTipTierBadge(tip.tier)
        Text(
            text = appString(tip.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = appString(tip.leadRes),
            style = MaterialTheme.typography.titleMedium,
        )
        StandardTipBoard(tip.boardExample)
        Text(
            text = appString(tip.boardCaptionRes),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
        Text(
            text = appString(tip.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(ChanrivaSpacing.section),
                verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
            ) {
                Text(
                    text = appString(R.string.standard_winning_tips_takeaway_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = appString(tip.takeawayRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
        ) {
            OutlinedButton(
                onClick = onPrevious,
                enabled = tipIndex > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(appString(R.string.standard_winning_tips_previous))
            }
            Button(
                onClick = onNext,
                enabled = tipIndex < standardWinningTips.lastIndex,
                modifier = Modifier.weight(1f),
            ) {
                Text(appString(R.string.standard_winning_tips_next))
            }
        }
    }
}

@Composable
private fun StandardTipTierSectionHeader(tier: StandardWinningTipTier) {
    val containerColor = when (tier) {
        StandardWinningTipTier.BASIC -> MaterialTheme.colorScheme.primaryContainer
        StandardWinningTipTier.STEP_UP -> MaterialTheme.colorScheme.tertiaryContainer
        StandardWinningTipTier.EXPERT -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (tier) {
        StandardWinningTipTier.BASIC -> MaterialTheme.colorScheme.onPrimaryContainer
        StandardWinningTipTier.STEP_UP -> MaterialTheme.colorScheme.onTertiaryContainer
        StandardWinningTipTier.EXPERT -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = appString(tier.titleRes),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

@Composable
private fun StandardTipTierBadge(tier: StandardWinningTipTier) {
    val containerColor = when (tier) {
        StandardWinningTipTier.BASIC -> MaterialTheme.colorScheme.primaryContainer
        StandardWinningTipTier.STEP_UP -> MaterialTheme.colorScheme.tertiaryContainer
        StandardWinningTipTier.EXPERT -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (tier) {
        StandardWinningTipTier.BASIC -> MaterialTheme.colorScheme.onPrimaryContainer
        StandardWinningTipTier.STEP_UP -> MaterialTheme.colorScheme.onTertiaryContainer
        StandardWinningTipTier.EXPERT -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = appString(tier.titleRes),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
private fun StandardTipsSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
            content = content,
        )
    }
}
