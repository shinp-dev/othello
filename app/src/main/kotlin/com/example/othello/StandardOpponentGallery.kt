package com.example.othello

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.othello.designsystem.ChanrivaSpacing

@Composable
internal fun StandardOpponentSelectionPanel(
    installedPack: InstalledOpponentPack,
    progress: StandardAiProgress,
    selectedPlayer: Player,
    onPlayerSelected: (Player) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pack = installedPack.definition
    val selectedIndex = pack.players.indexOfFirst { it.id == selectedPlayer.id }.coerceAtLeast(0)
    val previous = pack.players.getOrNull(selectedIndex - 1)
    val current = pack.players[selectedIndex]
    val next = pack.players.getOrNull(selectedIndex + 1)
    var dragDistance = remember(selectedPlayer) { 0f }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = appString(R.string.standard_ai_animal_pack_name),
            modifier = Modifier.fillMaxWidth().testTag("standard-ai-selection-title"),
            color = FOREST_TEXT,
            fontSize = 29.sp,
            lineHeight = 35.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = appString(R.string.standard_ai_animal_pack_supporting),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .testTag("standard-ai-selection-subtitle"),
            color = FOREST_SUBTITLE,
            fontSize = when {
                LocalConfiguration.current.screenWidthDp <= 340 -> 11.sp
                LocalConfiguration.current.screenWidthDp <= 370 -> 12.sp
                else -> 13.sp
            },
            lineHeight = 20.sp,
            fontFamily = FontFamily.Serif,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("standard-ai-opponent-carousel")
                .clipToBounds()
                .pointerInput(selectedPlayer, progress.unlockedPlayerIds) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragDistance += dragAmount
                        },
                        onDragEnd = {
                            when {
                                dragDistance <= -SWIPE_THRESHOLD_PX -> next?.let(onPlayerSelected)
                                dragDistance >= SWIPE_THRESHOLD_PX -> previous?.let(onPlayerSelected)
                            }
                            dragDistance = 0f
                        },
                        onDragCancel = { dragDistance = 0f },
                    )
                },
        ) {
            val cardHeight = minOf(maxHeight * 0.76f, 356.dp).coerceAtLeast(250.dp)
            val cardWidth = minOf(maxWidth * 0.70f, 238.dp)
            val sideWidth = cardWidth * 0.91f
            val visiblePeek = minOf(58.dp, (maxWidth - cardWidth) / 2f + 8.dp)
            val centerLeft = (maxWidth - cardWidth) / 2f
            val centerRight = centerLeft + cardWidth
            val sideHeight = cardHeight * 0.86f

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cardHeight),
                ) {
                    if (previous != null) {
                        StandardOpponentCard(
                            installedPack = installedPack,
                            opponent = previous,
                            progress = progress,
                            centered = false,
                            onClick = { onPlayerSelected(previous) },
                            modifier = Modifier
                                .offset(x = centerLeft - visiblePeek)
                                .align(Alignment.CenterStart)
                                .size(width = sideWidth, height = sideHeight),
                        )
                    }
                    if (next != null) {
                        StandardOpponentCard(
                            installedPack = installedPack,
                            opponent = next,
                            progress = progress,
                            centered = false,
                            onClick = { onPlayerSelected(next) },
                            modifier = Modifier
                                .offset(x = centerRight - sideWidth + visiblePeek)
                                .align(Alignment.CenterStart)
                                .size(width = sideWidth, height = sideHeight),
                        )
                    }
                    StandardOpponentCard(
                        installedPack = installedPack,
                        opponent = current,
                        progress = progress,
                        centered = true,
                        onClick = { onPlayerSelected(current) },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(width = cardWidth, height = cardHeight),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.testTag("standard-ai-selection-dots"),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pack.players.forEachIndexed { index, player ->
                        Box(
                            modifier = Modifier
                                .size(if (index == selectedIndex) 10.dp else 8.dp)
                                .background(
                                    color = if (index == selectedIndex) GOLD_LIGHT else Color(0xFF9EA9A3).copy(alpha = 0.72f),
                                    shape = CircleShape,
                                )
                                .testTag("standard-ai-selection-dot-${player.id}"),
                        )
                    }
                }
                Text(
                    text = "${selectedIndex + 1} / ${pack.players.size}",
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .testTag("standard-ai-selection-position"),
                    color = GOLD_LIGHT,
                    fontFamily = FontFamily.Serif,
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
internal fun StandardOpponentSelectionScreen(
    installedPack: InstalledOpponentPack,
    progress: StandardAiProgress,
    selectedPlayer: Player,
    onPlayerSelected: (Player) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.animal_selection_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color(0x44001418)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = ChanrivaSpacing.page)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ForestBackButton(onClick = onBack)
            Spacer(Modifier.height(5.dp))
            StandardOpponentSelectionPanel(
                installedPack = installedPack,
                progress = progress,
                selectedPlayer = selectedPlayer,
                onPlayerSelected = onPlayerSelected,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            ForestStartButton(
                enabled = progress.isUnlocked(selectedPlayer),
                onClick = onStart,
            )
        }
    }
}

@Composable
private fun ColumnScope.ForestBackButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.Start)
            .height(40.dp)
            .clickable(onClick = onClick)
            .testTag("standard-ai-selection-back"),
        color = Color(0xEE062D31),
        contentColor = FOREST_TEXT,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GOLD_DARK.copy(alpha = 0.9f)),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = GOLD_LIGHT, modifier = Modifier.size(20.dp))
            Text(appString(R.string.back), color = FOREST_TEXT, fontFamily = FontFamily.Serif, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ColumnScope.ForestStartButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(bottom = 3.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("standard-ai-start"),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.animal_selection_cta),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            OthelloDiscMark()
            Text(
                text = appString(R.string.standard_ai_animal_start),
                modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
                color = FOREST_TEXT,
                fontFamily = FontFamily.Serif,
                fontSize = 19.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = GOLD_LIGHT, modifier = Modifier.size(20.dp))
        }
        if (!enabled) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
    }
}

@Composable
private fun OthelloDiscMark() {
    Box(Modifier.size(width = 43.dp, height = 36.dp)) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .size(27.dp)
                .background(Color(0xFF101116), CircleShape),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(27.dp)
                .background(Color(0xFFF3F2E8), CircleShape),
        )
    }
}

@Composable
private fun StandardOpponentCard(
    installedPack: InstalledOpponentPack,
    opponent: Player,
    progress: StandardAiProgress,
    centered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlocked = progress.isUnlocked(opponent)
    val order = installedPack.definition.players.indexOfFirst { it.id == opponent.id } + 1
    val requiredPlayer = opponent.requires.firstOrNull()?.let(installedPack.definition::player)
    val unlockCondition = requiredPlayer?.let {
        appString(R.string.standard_ai_unlock_condition, opponentText(it.name))
    }.orEmpty()

    Box(
        modifier = modifier
            .testTag("standard-ai-player-${installedPack.definition.id}-${opponent.id}")
            .clickable(onClick = onClick)
            .graphicsLayer { alpha = if (centered) 1f else 0.92f },
    ) {
        Image(
            painter = painterResource(if (unlocked) R.drawable.animal_selection_frame else R.drawable.animal_selection_locked_frame),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )

        AsyncImage(
            model = installedPack.image(opponent.portrait),
            contentDescription = if (unlocked) {
                appString(R.string.standard_ai_opponent_named_description, opponentText(opponent.name), order)
            } else {
                appString(R.string.standard_ai_locked_opponent_description, order)
            },
            contentScale = ContentScale.Fit,
            colorFilter = if (unlocked) null else ColorFilter.tint(Color(0xFF11191D)),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.83f)
                .fillMaxSize(0.72f)
                .padding(top = 15.dp, bottom = if (centered) 38.dp else 26.dp)
                .graphicsLayer { alpha = if (unlocked) 1f else 0.92f },
        )

        if (!unlocked) {
            Box(Modifier.fillMaxSize().background(Color(0x55000A11)))
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = appString(R.string.standard_ai_locked_opponent_description, order),
                tint = Color(0xFFE4E7E3),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 14.dp)
                    .size(if (centered) 35.dp else 25.dp)
                    .testTag("standard-ai-state-${installedPack.definition.id}-${opponent.id}"),
            )
        }

        Text(
            text = order.toString(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 20.dp, top = 13.dp),
            color = FOREST_TEXT,
            fontFamily = FontFamily.Serif,
            fontSize = if (centered) 19.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )

        if (unlocked) {
            Text(
                text = opponentText(opponent.name),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.76f)
                    .padding(bottom = if (centered) 24.dp else 17.dp),
                color = FOREST_TEXT,
                fontFamily = FontFamily.Serif,
                fontSize = if (centered) 19.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = unlockCondition,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.80f)
                    .padding(bottom = if (centered) 16.dp else 10.dp)
                    .testTag("standard-ai-unlock-condition-${opponent.id}"),
                color = FOREST_TEXT,
                fontFamily = FontFamily.Serif,
                fontSize = if (centered) 12.sp else 8.sp,
                lineHeight = if (centered) 15.sp else 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val FOREST_TEXT = Color(0xFFFFF6D8)
private val FOREST_SUBTITLE = Color(0xFFE7E4D5)
private val GOLD_LIGHT = Color(0xFFFFD77A)
private val GOLD_DARK = Color(0xFFC99437)
private const val SWIPE_THRESHOLD_PX = 72f
