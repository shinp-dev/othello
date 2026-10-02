package com.example.othello

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.othello.designsystem.ChanrivaColors

private val DeepBackgroundTop = Color(0xFF071525)
private val DeepBackgroundBottom = Color(0xFF070B12)
private val DeepBattle = Color(0xFF37A7D2)
private val DeepStudy = Color(0xFF8168E7)
private val DeepSettings = Color(0xFFD9B75E)
private val DeepAction = Color(0xFF5F9DFF)
private val DeepDisabled = Color(0xFF718096)
private val DeepPanel = Color(0xE60B1522)

@Composable
internal fun DeepEnjoyScreen(
    failedLocalRecordSaveCount: Int,
    onRetryFailedLocalRecords: () -> Unit,
    onLocalAiStart: () -> Unit,
    onLocalHumanStart: () -> Unit,
    onPositionReview: () -> Unit,
    onTheoryExploration: () -> Unit,
    onOfflineRecords: () -> Unit,
    onMatchSettings: () -> Unit,
    onReviewSettings: () -> Unit,
    onCommonSettings: () -> Unit,
    onMore: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        DeepBackgroundTop,
                        Color(0xFF0A1221),
                        DeepBackgroundBottom,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DeepEnjoyHeader(onMore)

            DeepSectionCard(
                title = appString(R.string.deep_battle_section),
                supportingText = appString(R.string.deep_battle_supporting),
                iconRes = R.drawable.ic_deep_battle,
                accent = DeepBattle,
            ) {
                DeepActionRow(
                    title = appString(R.string.play_against_ai),
                    iconRes = R.drawable.ic_deep_ai,
                    onClick = onLocalAiStart,
                )
                DeepActionRow(
                    title = appString(R.string.two_player_match),
                    iconRes = R.drawable.ic_deep_two_player,
                    onClick = onLocalHumanStart,
                )
                DeepActionRow(
                    title = appString(R.string.play_online),
                    iconRes = R.drawable.ic_deep_online,
                    onClick = null,
                )
            }

            DeepSectionCard(
                title = appString(R.string.deep_review_section),
                supportingText = appString(R.string.deep_review_supporting),
                iconRes = R.drawable.ic_deep_review,
                accent = DeepStudy,
            ) {
                DeepActionRow(
                    title = appString(R.string.position_review),
                    iconRes = R.drawable.ic_deep_position,
                    onClick = onPositionReview,
                )
                DeepActionRow(
                    title = appString(R.string.theory_exploration),
                    iconRes = R.drawable.ic_deep_theory,
                    onClick = onTheoryExploration,
                )
                DeepActionRow(
                    title = appString(R.string.online_records),
                    iconRes = R.drawable.ic_deep_online_record,
                    onClick = null,
                )
                DeepActionRow(
                    title = appString(R.string.offline_records),
                    iconRes = R.drawable.ic_deep_offline_record,
                    onClick = onOfflineRecords,
                )
                DeepActionRow(
                    title = appString(R.string.research_participation),
                    iconRes = R.drawable.ic_deep_research,
                    onClick = null,
                )
            }

            DeepSectionCard(
                title = appString(R.string.deep_settings_section),
                supportingText = appString(R.string.deep_settings_supporting),
                iconRes = R.drawable.ic_deep_settings,
                accent = DeepSettings,
            ) {
                DeepActionRow(
                    title = appString(R.string.match_settings),
                    iconRes = R.drawable.ic_deep_sliders,
                    onClick = onMatchSettings,
                )
                DeepActionRow(
                    title = appString(R.string.review_settings),
                    iconRes = R.drawable.ic_deep_review_settings,
                    onClick = onReviewSettings,
                )
                DeepActionRow(
                    title = appString(R.string.common_settings),
                    iconRes = R.drawable.ic_deep_settings,
                    onClick = onCommonSettings,
                )
            }

            if (failedLocalRecordSaveCount > 0) {
                DeepRecoveryCard(
                    count = failedLocalRecordSaveCount,
                    onRetry = onRetryFailedLocalRecords,
                )
            }
        }
    }
}

@Composable
private fun DeepEnjoyHeader(onMore: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(width = 4.dp, height = 58.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF7185FF)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = appString(R.string.enjoy_deeply),
                color = ChanrivaColors.textPrimary,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = appString(R.string.deep_enjoy_supporting),
                color = Color(0xFF94A4BC),
                fontSize = 15.sp,
                lineHeight = 20.sp,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF14243A))
                .border(1.dp, Color(0xFF2A405D), CircleShape)
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_deep_profile),
                contentDescription = appString(R.string.more),
                tint = Color(0xFFD8E5F7),
                modifier = Modifier.size(25.dp),
            )
        }
    }
}

@Composable
private fun DeepSectionCard(
    title: String,
    supportingText: String,
    @DrawableRes iconRes: Int,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DeepPanel)
            .border(1.dp, accent.copy(alpha = 0.42f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(accent.copy(alpha = 0.09f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(27.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = ChanrivaColors.textPrimary,
                    fontSize = 21.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = supportingText,
                    color = Color(0xFF91A0B5),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        content()
    }
}

@Composable
private fun DeepActionRow(
    title: String,
    @DrawableRes iconRes: Int,
    onClick: (() -> Unit)?,
) {
    val enabled = onClick != null
    val foreground = if (enabled) ChanrivaColors.textPrimary else ChanrivaColors.textDisabled
    val iconTint = if (enabled) Color(0xFF68A9FF) else DeepDisabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(enabled = enabled, onClick = { onClick?.invoke() })
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(25.dp),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            text = title,
            color = foreground,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painter = painterResource(
                if (enabled) R.drawable.ic_deep_chevron else R.drawable.ic_deep_close,
            ),
            contentDescription = if (enabled) null else appString(R.string.deep_feature_closed),
            tint = if (enabled) DeepAction else DeepDisabled,
            modifier = Modifier.size(if (enabled) 22.dp else 24.dp),
        )
    }
}

@Composable
private fun DeepRecoveryCard(
    count: Int,
    onRetry: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF15131A))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = appString(R.string.local_record_operation_failed, count),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = appString(R.string.retry_record_operation),
            color = Color(0xFF94B8FF),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onRetry),
        )
    }
}
