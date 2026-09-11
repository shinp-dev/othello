package com.example.othello

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val STANDARD_GACHA_PRESS_MILLIS = 120L
private const val STANDARD_GACHA_CRACK_MILLIS = 460L
private const val STANDARD_GACHA_BURST_MILLIS = 260L
private const val STANDARD_GACHA_PHASE_IDLE = 0
private const val STANDARD_GACHA_PHASE_PRESS = 1
private const val STANDARD_GACHA_PHASE_CRACK = 2
private const val STANDARD_GACHA_PHASE_BURST = 3

private sealed interface StandardGachaLoadState {
    data object Loading : StandardGachaLoadState
    data object Failed : StandardGachaLoadState

    data class Ready(
        val snapshot: StandardContentSnapshot,
        val obtainedCardIds: Set<String>,
        val dailyState: StandardGachaDailyState,
    ) : StandardGachaLoadState
}

@Composable
internal fun StandardGachaRoute(
    userId: String,
    onBack: () -> Unit,
    onCollection: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as OthelloApplication
    val collectionStore = remember(context) { StandardCollectionStore(context) }
    val dailyStore = remember(context) { StandardGachaDailyStore(context) }
    var retryGeneration by rememberSaveable(userId) { mutableIntStateOf(0) }
    var loadState by remember(userId) {
        mutableStateOf<StandardGachaLoadState>(StandardGachaLoadState.Loading)
    }

    LaunchedEffect(userId, retryGeneration) {
        loadState = StandardGachaLoadState.Loading
        loadState = withContext(Dispatchers.IO) {
            runCatching {
                StandardGachaLoadState.Ready(
                    snapshot = application.standardContent.snapshot(),
                    obtainedCardIds = collectionStore.obtainedCardIds(userId),
                    dailyState = dailyStore.state(userId),
                )
            }.getOrElse {
                StandardGachaLoadState.Failed
            }
        }
    }

    when (val state = loadState) {
        StandardGachaLoadState.Loading -> StandardGachaMessageScreen(
            title = appString(R.string.standard_gacha_title),
            message = appString(R.string.standard_gacha_loading),
            onBack = onBack,
        )
        StandardGachaLoadState.Failed -> StandardGachaErrorScreen(
            onBack = onBack,
            onRetry = { retryGeneration += 1 },
        )
        is StandardGachaLoadState.Ready -> StandardGachaScreen(
            userId = userId,
            snapshot = state.snapshot,
            initialObtainedCardIds = state.obtainedCardIds,
            collectionStore = collectionStore,
            dailyStore = dailyStore,
            initialDailyState = state.dailyState,
            onBack = onBack,
            onCollection = onCollection,
        )
    }
}

@Composable
private fun StandardGachaScreen(
    userId: String,
    snapshot: StandardContentSnapshot,
    initialObtainedCardIds: Set<String>,
    collectionStore: StandardCollectionStore,
    dailyStore: StandardGachaDailyStore,
    initialDailyState: StandardGachaDailyState,
    onBack: () -> Unit,
    onCollection: () -> Unit,
) {
    val engine = remember { StandardGachaEngine() }
    var obtainedCardIds by remember(userId, snapshot) {
        mutableStateOf(initialObtainedCardIds)
    }
    var pendingCardId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var pendingWasNew by rememberSaveable(userId) { mutableStateOf(false) }
    var resultCardId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var revealPhase by rememberSaveable(userId) { mutableIntStateOf(STANDARD_GACHA_PHASE_IDLE) }
    var dailyState by remember(userId) { mutableStateOf(initialDailyState) }

    val pendingEntry = pendingCardId?.let(snapshot::card)
    val resultEntry = resultCardId?.let(snapshot::card)

    LaunchedEffect(pendingCardId) {
        val cardId = pendingCardId ?: return@LaunchedEffect
        val entry = snapshot.card(cardId) ?: run {
            pendingCardId = null
            return@LaunchedEffect
        }
        revealPhase = STANDARD_GACHA_PHASE_PRESS
        delay(STANDARD_GACHA_PRESS_MILLIS)
        revealPhase = STANDARD_GACHA_PHASE_CRACK
        delay(STANDARD_GACHA_CRACK_MILLIS)
        revealPhase = STANDARD_GACHA_PHASE_BURST
        delay(STANDARD_GACHA_BURST_MILLIS)
        withContext(Dispatchers.IO) {
            collectionStore.markObtained(userId, entry.card.id)
        }
        obtainedCardIds = obtainedCardIds + entry.card.id
        resultCardId = entry.card.id
        pendingCardId = null
        revealPhase = STANDARD_GACHA_PHASE_IDLE
    }

    fun draw() {
        if (pendingCardId != null || snapshot.entries.isEmpty()) return
        val draw = engine.draw(snapshot.entries, obtainedCardIds) ?: return
        val updatedDailyState = dailyStore.tryConsumeFreeDraw(userId) ?: return
        dailyState = updatedDailyState
        pendingWasNew = draw.isNew
        resultCardId = null
        revealPhase = STANDARD_GACHA_PHASE_PRESS
        pendingCardId = draw.entry.card.id
    }

    StandardGachaSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_gacha_title),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )

        StandardGachaProgress(
            obtainedCount = snapshot.entries.count { it.card.id in obtainedCardIds },
            totalCount = snapshot.entries.size,
        )

        Text(
            text = appString(R.string.standard_gacha_supporting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = appString(
                R.string.standard_gacha_daily_remaining,
                dailyState.remainingFreeDraws,
                STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        when {
            snapshot.entries.isEmpty() -> StandardGachaEmpty()
            pendingEntry != null -> StandardGachaMachine(
                pendingEntry = pendingEntry,
                revealPhase = revealPhase,
                canDraw = false,
                onDraw = {},
            )
            resultEntry != null -> StandardGachaResultCard(
                entry = resultEntry,
                isNew = pendingWasNew,
            )
            else -> StandardGachaMachine(
                pendingEntry = null,
                revealPhase = STANDARD_GACHA_PHASE_IDLE,
                canDraw = dailyState.canDrawForFree,
                onDraw = ::draw,
            )
        }

        if (snapshot.entries.isNotEmpty()) {
            Button(
                onClick = ::draw,
                enabled = pendingEntry == null && dailyState.canDrawForFree,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        pendingEntry != null -> appString(R.string.standard_gacha_drawing)
                        !dailyState.canDrawForFree -> appString(R.string.standard_gacha_daily_limit_reached_button)
                        resultEntry != null -> appString(R.string.standard_gacha_draw_again)
                        else -> appString(R.string.standard_gacha_draw_free)
                    },
                )
            }
        }

        OutlinedButton(
            onClick = onCollection,
            enabled = pendingEntry == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(appString(R.string.standard_gacha_open_collection))
        }

        Text(
            text = when {
                !dailyState.canDrawForFree -> appString(R.string.standard_gacha_daily_limit_reached)
                snapshot.entries.isNotEmpty() &&
                    snapshot.entries.all { it.card.id in obtainedCardIds } ->
                    appString(R.string.standard_gacha_complete_supporting)
                else -> appString(R.string.standard_gacha_duplicate_supporting)
            },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StandardGachaMachine(
    pendingEntry: StandardContentEntry?,
    revealPhase: Int,
    canDraw: Boolean,
    onDraw: () -> Unit,
) {
    val rarity = pendingEntry?.card?.rarity
    val glowColor = standardGachaGlowColor(rarity)
    val glowStrength = standardGachaGlowStrength(rarity)

    val capsuleScale by animateFloatAsState(
        targetValue = when (revealPhase) {
            STANDARD_GACHA_PHASE_PRESS -> 0.94f
            STANDARD_GACHA_PHASE_CRACK -> 1.02f
            STANDARD_GACHA_PHASE_BURST -> 1.06f
            else -> 1f
        },
        animationSpec = tween(durationMillis = 120),
        label = "standard-gacha-capsule-scale",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = when (revealPhase) {
            STANDARD_GACHA_PHASE_PRESS -> 0.10f * glowStrength
            STANDARD_GACHA_PHASE_CRACK -> 0.62f * glowStrength
            STANDARD_GACHA_PHASE_BURST -> 0.95f * glowStrength
            else -> 0f
        },
        animationSpec = tween(durationMillis = 180),
        label = "standard-gacha-glow",
    )
    val shellAlpha by animateFloatAsState(
        targetValue = if (revealPhase == STANDARD_GACHA_PHASE_BURST) 1f else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "standard-gacha-shells",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = ChanrivaSpacing.section),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
        ) {
            Box(
                modifier = Modifier
                    .size(228.dp)
                    .clickable(
                        enabled = pendingEntry == null && canDraw,
                        onClick = onDraw,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .size(216.dp)
                        .graphicsLayer {
                            alpha = glowAlpha * 0.36f
                            scaleX = 1.12f
                            scaleY = 1.12f
                        },
                    shape = CircleShape,
                    color = glowColor,
                ) {}
                Surface(
                    modifier = Modifier
                        .size(180.dp)
                        .graphicsLayer {
                            alpha = glowAlpha * 0.66f
                            scaleX = 1.08f
                            scaleY = 1.08f
                        },
                    shape = CircleShape,
                    color = glowColor,
                ) {}

                if (revealPhase == STANDARD_GACHA_PHASE_BURST) {
                    Image(
                        painter = painterResource(R.drawable.standard_gacha_capsule_left_shell_art),
                        contentDescription = null,
                        modifier = Modifier
                            .size(164.dp)
                            .offset(x = (-54).dp, y = 4.dp)
                            .graphicsLayer {
                                rotationZ = -12f
                                alpha = shellAlpha
                            },
                    )
                    Image(
                        painter = painterResource(R.drawable.standard_gacha_capsule_right_shell_art),
                        contentDescription = null,
                        modifier = Modifier
                            .size(164.dp)
                            .offset(x = 54.dp, y = (-2).dp)
                            .graphicsLayer {
                                rotationZ = 12f
                                alpha = shellAlpha
                            },
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.standard_gacha_capsule_base_art),
                        contentDescription = appString(R.string.standard_gacha_capsule_description),
                        modifier = Modifier
                            .size(192.dp)
                            .graphicsLayer {
                                scaleX = capsuleScale
                                scaleY = capsuleScale
                            },
                    )
                    if (revealPhase >= STANDARD_GACHA_PHASE_CRACK) {
                        Image(
                            painter = painterResource(R.drawable.standard_gacha_capsule_cracks_art),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(glowColor),
                            modifier = Modifier
                                .size(192.dp)
                                .graphicsLayer {
                                    scaleX = capsuleScale
                                    scaleY = capsuleScale
                                    alpha = 0.92f
                                },
                        )
                    }
                }
            }

            Text(
                text = when {
                    pendingEntry != null -> appString(R.string.standard_gacha_machine_revealing)
                    canDraw -> appString(R.string.standard_gacha_capsule_tap)
                    else -> appString(R.string.standard_gacha_daily_limit_reached_button)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun standardGachaGlowColor(rarity: StandardContentRarity?): Color = when (rarity) {
    StandardContentRarity.COMMON -> Color(0xFFDCEBFF)
    StandardContentRarity.RARE -> Color(0xFFFFD76A)
    StandardContentRarity.SPECIAL -> Color(0xFFFF5A66)
    null -> Color.Transparent
}

private fun standardGachaGlowStrength(rarity: StandardContentRarity?): Float = when (rarity) {
    StandardContentRarity.COMMON -> 0.55f
    StandardContentRarity.RARE -> 0.82f
    StandardContentRarity.SPECIAL -> 1f
    null -> 0f
}

@Composable
private fun StandardGachaResultCard(
    entry: StandardContentEntry,
    isNew: Boolean,
) {
    val bitmap = remember(entry.imageFile?.absolutePath) {
        entry.imageFile
            ?.takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
            ?.asImageBitmap()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ChanrivaColors.surfaceElevated),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ChanrivaSpacing.section),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
        ) {
            if (isNew) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = appString(R.string.standard_gacha_new),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            } else {
                Text(
                    text = appString(R.string.standard_gacha_duplicate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                modifier = Modifier.size(128.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = entry.card.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = standardGachaTypeShortLabel(entry.card.type),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = standardGachaTypeLabel(entry.card.type),
                    style = MaterialTheme.typography.labelMedium,
                    color = ChanrivaColors.accent,
                )
                Text(
                    text = "·",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = standardGachaRarityLabel(entry.card.rarity),
                    style = MaterialTheme.typography.labelMedium,
                    color = ChanrivaColors.accent,
                )
            }

            Text(
                text = entry.card.title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = entry.card.summary,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            entry.card.attributes["author"]?.let { author ->
                Text(
                    text = appString(R.string.standard_gacha_author, author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StandardGachaProgress(
    obtainedCount: Int,
    totalCount: Int,
) {
    val progress = if (totalCount == 0) 0f else obtainedCount.toFloat() / totalCount.toFloat()
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = appString(R.string.standard_gacha_progress, obtainedCount, totalCount),
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StandardGachaEmpty() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ChanrivaColors.surfaceElevated),
    ) {
        Text(
            text = appString(R.string.standard_gacha_empty),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp, horizontal = ChanrivaSpacing.section),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StandardGachaErrorScreen(
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    StandardGachaSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_gacha_title),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            text = appString(R.string.standard_gacha_load_error),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(appString(R.string.retry))
        }
    }
}

@Composable
private fun StandardGachaMessageScreen(
    title: String,
    message: String,
    onBack: () -> Unit,
) {
    StandardGachaSurface {
        ChanrivaScreenHeader(
            title = title,
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun standardGachaTypeLabel(type: StandardContentCardType): String = appString(
    when (type) {
        StandardContentCardType.TRIVIA -> R.string.standard_collection_category_trivia
        StandardContentCardType.BOOK -> R.string.standard_collection_category_book
        StandardContentCardType.PERSON -> R.string.standard_collection_category_person
        StandardContentCardType.HISTORY -> R.string.standard_collection_category_history
        StandardContentCardType.COLLAB -> R.string.standard_collection_category_collab
    },
)

@Composable
private fun standardGachaTypeShortLabel(type: StandardContentCardType): String = appString(
    when (type) {
        StandardContentCardType.TRIVIA -> R.string.standard_collection_short_trivia
        StandardContentCardType.BOOK -> R.string.standard_collection_short_book
        StandardContentCardType.PERSON -> R.string.standard_collection_short_person
        StandardContentCardType.HISTORY -> R.string.standard_collection_short_history
        StandardContentCardType.COLLAB -> R.string.standard_collection_short_collab
    },
)

@Composable
private fun standardGachaRarityLabel(rarity: StandardContentRarity): String = appString(
    when (rarity) {
        StandardContentRarity.COMMON -> R.string.standard_collection_rarity_common
        StandardContentRarity.RARE -> R.string.standard_collection_rarity_rare
        StandardContentRarity.SPECIAL -> R.string.standard_collection_rarity_special
    },
)

@Composable
private fun StandardGachaSurface(
    content: @Composable ColumnScope.() -> Unit,
) {
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
