package com.example.othello

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StandardOpponentSelectionPanel(
    progress: StandardAiProgress,
    selectedLevel: StandardAiLevel,
    onLevelSelected: (StandardAiLevel) -> Unit,
    onStart: () -> Unit,
) {
    val pack = StandardOpponentPacks.animal
    val initialPage = pack.opponents.indexOfFirst { it.level == selectedLevel }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pack.opponents.size },
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedLevel) {
        val targetPage = pack.opponents.indexOfFirst { it.level == selectedLevel }
        if (targetPage >= 0 && pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage, progress.highestUnlockedLevel) {
        val centered = pack.opponents[pagerState.currentPage]
        if (progress.isUnlocked(centered.level) && centered.level != selectedLevel) {
            onLevelSelected(centered.level)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        Text(
            text = appString(pack.titleRes),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(390.dp)
                .testTag("standard-ai-opponent-carousel"),
            contentPadding = PaddingValues(horizontal = 44.dp),
            pageSpacing = 12.dp,
            beyondBoundsPageCount = 1,
            key = { page -> pack.opponents[page].level.value },
        ) { page ->
            val opponent = pack.opponents[page]
            val centered = page == pagerState.currentPage
            val unlocked = progress.isUnlocked(opponent.level)
            val selected = opponent.level == selectedLevel

            StandardOpponentCard(
                opponent = opponent,
                progress = progress,
                centered = centered,
                onClick = {
                    when {
                        !centered -> {
                            if (unlocked) onLevelSelected(opponent.level)
                            scope.launch { pagerState.animateScrollToPage(page) }
                        }
                        unlocked && selected -> onStart()
                        unlocked -> onLevelSelected(opponent.level)
                    }
                },
            )
        }
    }
}

@Composable
private fun StandardOpponentCard(
    opponent: StandardOpponentUi,
    progress: StandardAiProgress,
    centered: Boolean,
    onClick: () -> Unit,
) {
    val unlocked = progress.isUnlocked(opponent.level)
    val cleared = progress.isCleared(opponent.level)
    val visibleName = if (unlocked) {
        appString(opponent.nameRes)
    } else {
        appString(R.string.standard_ai_opponent_unknown)
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxSize()
            .testTag("standard-ai-level-${opponent.level.value}")
            .graphicsLayer {
                val scale = if (centered) 1f else 0.88f
                scaleX = scale
                scaleY = scale
                alpha = if (centered) 1f else 0.68f
            },
        colors = CardDefaults.cardColors(
            containerColor = if (centered && unlocked) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                ChanrivaColors.surfaceElevated
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(ChanrivaSpacing.section),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(opponent.winDrawableRes),
                    contentDescription = if (unlocked) {
                        appString(
                            R.string.standard_ai_opponent_named_description,
                            appString(opponent.nameRes),
                            opponent.level.value,
                        )
                    } else {
                        appString(R.string.standard_ai_locked_opponent_description, opponent.level.value)
                    },
                    colorFilter = if (unlocked) {
                        null
                    } else {
                        ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    modifier = Modifier
                        .size(if (centered) 236.dp else 204.dp)
                        .graphicsLayer {
                            alpha = if (unlocked) 1f else 0.30f
                        },
                )

                Text(
                    text = appString(
                        R.string.standard_ai_opponent_level_name,
                        opponent.level.value,
                        visibleName,
                    ),
                    modifier = Modifier.padding(top = 18.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (centered) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }

            when {
                !unlocked -> {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(44.dp)
                            .testTag("standard-ai-state-${opponent.level.value}"),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = appString(
                                R.string.standard_ai_locked_opponent_description,
                                opponent.level.value,
                            ),
                            modifier = Modifier.padding(10.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                cleared -> {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(44.dp)
                            .testTag("standard-ai-state-${opponent.level.value}"),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = appString(R.string.standard_ai_opponent_status_cleared),
                            modifier = Modifier.padding(8.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
