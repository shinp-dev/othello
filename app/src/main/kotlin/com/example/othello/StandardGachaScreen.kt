package com.example.othello

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val STANDARD_GACHA_REVEAL_DELAY_MILLIS = 720L

private sealed interface StandardGachaLoadState {
    data object Loading : StandardGachaLoadState
    data object Failed : StandardGachaLoadState

    data class Ready(
        val snapshot: StandardContentSnapshot,
        val obtainedCardIds: Set<String>,
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

    val pendingEntry = pendingCardId?.let(snapshot::card)
    val resultEntry = resultCardId?.let(snapshot::card)

    LaunchedEffect(pendingCardId) {
        val cardId = pendingCardId ?: return@LaunchedEffect
        val entry = snapshot.card(cardId) ?: run {
            pendingCardId = null
            return@LaunchedEffect
        }
        delay(STANDARD_GACHA_REVEAL_DELAY_MILLIS)
        withContext(Dispatchers.IO) {
            collectionStore.markObtained(userId, entry.card.id)
        }
        obtainedCardIds = obtainedCardIds + entry.card.id
        resultCardId = entry.card.id
        pendingCardId = null
    }

    fun draw() {
        if (pendingCardId != null || snapshot.entries.isEmpty()) return
        val draw = engine.draw(snapshot.entries, obtainedCardIds) ?: return
        pendingWasNew = draw.isNew
        resultCardId = null
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

        when {
            snapshot.entries.isEmpty() -> StandardGachaEmpty()
            pendingEntry != null -> StandardGachaMachine(revealing = true)
            resultEntry != null -> StandardGachaResultCard(
                entry = resultEntry,
                isNew = pendingWasNew,
            )
            else -> StandardGachaMachine(revealing = false)
        }

        if (snapshot.entries.isNotEmpty()) {
            Button(
                onClick = ::draw,
                enabled = pendingEntry == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        pendingEntry != null -> appString(R.string.standard_gacha_drawing)
                        resultEntry != null -> appString(R.string.standard_gacha_draw_again)
                        else -> appString(R.string.standard_gacha_draw_free)
                    },
                )
            }
        }

        OutlinedButton(
            onClick = onCollection,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(appString(R.string.standard_gacha_open_collection))
        }

        Text(
            text = if (snapshot.entries.isNotEmpty() &&
                snapshot.entries.all { it.card.id in obtainedCardIds }
            ) {
                appString(R.string.standard_gacha_complete_supporting)
            } else {
                appString(R.string.standard_gacha_new_priority_supporting)
            },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StandardGachaMachine(revealing: Boolean) {
    val transition = rememberInfiniteTransition(label = "standard-gacha-machine")
    val shake by transition.animateFloat(
        initialValue = if (revealing) -8f else 0f,
        targetValue = if (revealing) 8f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 110),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "standard-gacha-shake",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { rotationZ = shake },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = ChanrivaSpacing.section),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
        ) {
            Surface(
                modifier = Modifier.size(156.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (revealing) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            text = "?",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Text(
                text = if (revealing) {
                    appString(R.string.standard_gacha_machine_revealing)
                } else {
                    appString(R.string.standard_gacha_machine_idle)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
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
    content: @Composable Column.() -> Unit,
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
