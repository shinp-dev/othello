package com.example.othello

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    StandardTipsIndexSurface {
        StandardTipsIndexHeader(onBack = onBack)
        Text(
            text = appString(R.string.standard_winning_tips_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = TipsIvory.copy(alpha = 0.9f),
            lineHeight = 21.sp,
        )
        StandardWinningTipTier.entries.forEach { tier ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StandardTipTierSectionHeader(tier)
                standardWinningTips.filter { it.tier == tier }.forEach { tip ->
                    StandardWinningTipIndexCard(tip = tip, onClick = { onOpenTip(tip) })
                }
            }
        }
    }
}

private val TipsGold = Color(0xFFE4C273)
private val TipsIvory = Color(0xFFFFF5DB)
private val TipsDeepGreen = Color(0xE8072525)

@Composable
private fun StandardTipsIndexSurface(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.standard_winning_tips_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x4D020917),
                    0.38f to Color(0x38020A14),
                    1f to Color(0x59030A10),
                ),
            ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun StandardTipsIndexHeader(onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xD9081D24))
                .border(1.dp, TipsGold.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = "‹  ${appString(R.string.back)}",
                color = TipsIvory,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(42.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFFFFE7A3), TipsGold, Color.Transparent)),
                        RoundedCornerShape(2.dp),
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = appString(R.string.standard_winning_tips_title),
                color = TipsIvory,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tier == StandardWinningTipTier.EXPERT) 66.dp else 58.dp)
            .semantics { heading() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Image(
            painter = painterResource(R.drawable.standard_winning_tips_tier_banner),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 30.dp)
                .offset(y = (-6).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (tier == StandardWinningTipTier.BASIC) "▤" else "▤▤",
                modifier = Modifier.width(44.dp),
                style = MaterialTheme.typography.titleMedium,
                color = TipsGold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = appString(tier.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TipsIvory,
                )
                if (tier == StandardWinningTipTier.EXPERT) {
                    Text(
                        text = appString(R.string.standard_winning_tips_expert_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = TipsIvory.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StandardWinningTipIndexCard(tip: StandardWinningTip, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val titleSize = if (LocalConfiguration.current.screenWidthDp <= 320) 16.sp else 18.sp
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(88.dp),
        shape = shape,
        border = BorderStroke(1.dp, TipsGold.copy(alpha = 0.88f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = TipsDeepGreen),
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.standard_winning_tips_card_frame),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color(0xA807191D)))
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = appString(tip.titleRes),
                        color = TipsIvory,
                        fontSize = titleSize,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = appString(tip.leadRes),
                        color = TipsIvory.copy(alpha = 0.82f),
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "›",
                    modifier = Modifier.size(26.dp),
                    color = TipsGold,
                    fontSize = 31.sp,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StandardTipTierBadge(tier: StandardWinningTipTier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = appString(tier.titleRes),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
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
