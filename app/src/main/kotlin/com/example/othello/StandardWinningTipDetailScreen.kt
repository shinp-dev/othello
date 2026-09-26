package com.example.othello

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DetailGold = Color(0xFFE9C879)
private val DetailIvory = Color(0xFFFFF4DE)
private val DetailNavy = Color(0xED061522)

@Composable
internal fun StandardWinningTipDetailScreen(
    tipIndex: Int,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val tip = standardWinningTips[tipIndex]
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val compactSpacing = screenWidth <= 320
    val boardMaxSize = when {
        screenWidth <= 320 -> 220.dp
        screenWidth <= 360 -> 240.dp
        else -> 260.dp
    }
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.winning_tip_detail_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0xA605101C),
                    0.32f to Color(0x72050F1B),
                    0.68f to Color(0xAA04101B),
                    1f to Color(0xD9040D18),
                ),
            ),
        )

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactSpacing) 4.dp else 6.dp),
            ) {
                DetailHeader(
                    index = tipIndex + 1,
                    count = standardWinningTips.size,
                    tier = appString(tip.tier.titleRes),
                    onBack = onBack,
                )
                Text(
                    text = appString(tip.titleRes),
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                    color = DetailIvory,
                    fontSize = 24.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = appString(tip.leadRes),
                    modifier = Modifier.fillMaxWidth(),
                    color = DetailIvory.copy(alpha = 0.94f),
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                )

                StandardTipBoard(
                    example = tip.boardExample,
                    modifier = Modifier.widthIn(max = boardMaxSize).fillMaxWidth().align(Alignment.CenterHorizontally),
                )

                DetailBoardCaption(text = appString(tip.boardCaptionRes))
                Text(
                    text = appString(tip.bodyRes),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    color = DetailIvory.copy(alpha = 0.96f),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
                DetailTakeaway(text = appString(tip.takeawayRes))
            }
            DetailNavigation(
                previousLabel = appString(R.string.standard_winning_tips_previous),
                nextLabel = appString(R.string.standard_winning_tips_next),
                previousEnabled = tipIndex > 0,
                nextEnabled = tipIndex < standardWinningTips.lastIndex,
                onPrevious = onPrevious,
                onNext = onNext,
            )
        }
    }
}

@Composable
private fun DetailHeader(index: Int, count: Int, tier: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(DetailNavy)
                .border(1.dp, DetailGold.copy(alpha = 0.88f), RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("‹  ${appString(R.string.back)}", color = DetailIvory, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(22.dp).background(Color(0xFFD64D65), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Text(
                text = appString(R.string.standard_winning_tips_title),
                color = DetailIvory,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = appString(R.string.standard_winning_tips_progress, index, count),
            color = DetailIvory,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xC9341729))
                .border(1.dp, Color(0xFFCE8D83), RoundedCornerShape(18.dp))
                .padding(horizontal = 13.dp, vertical = 5.dp),
        ) {
            Text(tier, color = DetailIvory, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun DetailBoardCaption(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE4081623))
            .border(1.dp, DetailGold.copy(alpha = 0.58f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        text.split('\n').forEach { line ->
            Text(line, color = DetailIvory, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Start)
        }
    }
}

@Composable
private fun DetailTakeaway(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xE7441828), Color(0xF2251020))),
            )
            .border(1.dp, DetailGold, RoundedCornerShape(15.dp))
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✦", color = DetailGold, fontSize = 18.sp)
            Text(
                text = appString(R.string.standard_winning_tips_takeaway_label),
                color = DetailGold,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(text, color = DetailIvory, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailNavigation(
    previousLabel: String,
    nextLabel: String,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NavigationButton(
            label = previousLabel,
            enabled = previousEnabled,
            primary = false,
            modifier = Modifier.weight(1f),
            onClick = onPrevious,
        )
        NavigationButton(
            label = nextLabel,
            enabled = nextEnabled,
            primary = true,
            trailingChevron = true,
            modifier = Modifier.weight(1f),
            onClick = onNext,
        )
    }
}

@Composable
private fun NavigationButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    trailingChevron: Boolean = false,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(shape)
            .background(
                if (primary) Brush.verticalGradient(listOf(Color(0xFF9D2639), Color(0xFF651927)))
                else Brush.verticalGradient(listOf(Color(0xED081A2A), Color(0xF0061320))),
            )
            .border(1.dp, DetailGold.copy(alpha = if (primary) 0.96f else 0.78f), shape)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (!trailingChevron) Text("‹", color = DetailIvory, fontSize = 20.sp)
            Text(
                text = label,
                color = if (enabled) DetailIvory else DetailIvory.copy(alpha = 0.42f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingChevron) Text("›", color = DetailIvory, fontSize = 20.sp)
        }
    }
}
