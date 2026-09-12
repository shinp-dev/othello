package com.example.othello

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.gif.repeatCount
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val STANDARD_GACHA_PRESS_MILLIS = 280L
private const val STANDARD_GACHA_CRACK_MILLIS = 320L
private const val STANDARD_GACHA_BURST_MILLIS = 920L
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
    val gachaEffects = rememberStandardGachaEffects()
    val hostView = LocalView.current
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
        gachaEffects.reveal(entry.card.rarity, hostView)
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

        if (resultEntry == null) {
            Text(
                text = appString(R.string.standard_gacha_supporting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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

        StandardGachaProgress(
            obtainedCount = snapshot.entries.count { it.card.id in obtainedCardIds },
            totalCount = snapshot.entries.size,
        )

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
                        resultEntry != null -> appString(
                            R.string.standard_gacha_draw_again_remaining,
                            dailyState.remainingFreeDraws,
                            STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT,
                        )
                        else -> appString(
                            R.string.standard_gacha_draw_free_remaining,
                            dailyState.remainingFreeDraws,
                            STANDARD_GACHA_DAILY_FREE_DRAW_LIMIT,
                        )
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

        if (resultEntry == null) {
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val revealRequest = remember(context) {
        ImageRequest.Builder(context)
            .data(R.drawable.standard_gacha_capsule_reveal)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .repeatCount(0)
            .build()
    }
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

                if (pendingEntry != null) {
                    AsyncImage(
                        model = revealRequest,
                        contentDescription = appString(R.string.standard_gacha_capsule_description),
                        placeholder = painterResource(R.drawable.standard_gacha_capsule_reveal),
                        error = painterResource(R.drawable.standard_gacha_capsule_reveal),
                        modifier = Modifier.size(228.dp),
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.standard_gacha_capsule_reveal),
                        contentDescription = appString(R.string.standard_gacha_capsule_description),
                        modifier = Modifier.size(228.dp),
                    )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ChanrivaColors.surfaceElevated),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = ChanrivaSpacing.section),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
        ) {
            Text(
                text = if (isNew) {
                    appString(R.string.standard_gacha_new)
                } else {
                    appString(R.string.standard_gacha_duplicate)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isNew) ChanrivaColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StandardContentArtwork(
                entry = entry,
                obtained = true,
                modifier = Modifier.size(184.dp),
            )

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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
            trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
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
