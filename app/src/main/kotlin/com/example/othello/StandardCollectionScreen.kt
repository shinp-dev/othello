package com.example.othello

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface StandardCollectionLoadState {
    data object Loading : StandardCollectionLoadState
    data object Failed : StandardCollectionLoadState

    data class Ready(
        val snapshot: StandardContentSnapshot,
        val obtainedCardIds: Set<String>,
    ) : StandardCollectionLoadState
}

@Composable
internal fun StandardCollectionRoute(
    userId: String,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as OthelloApplication
    val collectionStore = remember(context) { StandardCollectionStore(context) }
    var retryGeneration by rememberSaveable(userId) { mutableIntStateOf(0) }
    var loadState by remember(userId) {
        mutableStateOf<StandardCollectionLoadState>(StandardCollectionLoadState.Loading)
    }

    LaunchedEffect(userId, retryGeneration) {
        loadState = StandardCollectionLoadState.Loading
        loadState = withContext(Dispatchers.IO) {
            runCatching {
                StandardCollectionLoadState.Ready(
                    snapshot = application.standardContent.snapshot(),
                    obtainedCardIds = collectionStore.obtainedCardIds(userId),
                )
            }.getOrElse {
                StandardCollectionLoadState.Failed
            }
        }
    }

    when (val state = loadState) {
        StandardCollectionLoadState.Loading -> StandardCollectionLoadingScreen(onBack)
        StandardCollectionLoadState.Failed -> StandardCollectionErrorScreen(
            onBack = onBack,
            onRetry = { retryGeneration += 1 },
        )
        is StandardCollectionLoadState.Ready -> StandardCollectionScreen(
            userId = userId,
            snapshot = state.snapshot,
            obtainedCardIds = state.obtainedCardIds,
            onBack = onBack,
        )
    }
}

@Composable
private fun StandardCollectionLoadingScreen(onBack: () -> Unit) {
    StandardCollectionSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_collection_title),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            text = appString(R.string.standard_collection_loading),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StandardCollectionErrorScreen(
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    StandardCollectionSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_collection_title),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            text = appString(R.string.standard_collection_load_error),
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
private fun StandardCollectionScreen(
    userId: String,
    snapshot: StandardContentSnapshot,
    obtainedCardIds: Set<String>,
    onBack: () -> Unit,
) {
    var selectedFilterName by rememberSaveable(userId) {
        mutableStateOf(StandardCollectionFilter.ALL.name)
    }
    var selectedCardId by rememberSaveable(userId) { mutableStateOf<String?>(null) }

    val filters = remember(snapshot.entries) {
        availableStandardCollectionFilters(snapshot.entries)
    }
    val selectedFilter = filters.firstOrNull { it.name == selectedFilterName }
        ?: StandardCollectionFilter.ALL
    val filteredEntries = remember(snapshot.entries, obtainedCardIds, selectedFilter) {
        filterStandardCollectionEntries(
            entries = snapshot.entries,
            obtainedCardIds = obtainedCardIds,
            filter = selectedFilter,
        )
    }
    val obtainedCount = remember(snapshot.entries, obtainedCardIds) {
        standardCollectionObtainedCount(snapshot.entries, obtainedCardIds)
    }

    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ChanrivaSpacing.page),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
        ) {
            item {
                Spacer(Modifier.height(ChanrivaSpacing.page))
                ChanrivaScreenHeader(
                    title = appString(R.string.standard_collection_title),
                    onBack = onBack,
                    backLabel = appString(R.string.back),
                )
            }
            item {
                StandardCollectionProgress(
                    obtainedCount = obtainedCount,
                    totalCount = snapshot.entries.size,
                )
            }
            item {
                Text(
                    text = appString(R.string.standard_collection_supporting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                StandardCollectionFilters(
                    filters = filters,
                    selected = selectedFilter,
                    onSelected = { selectedFilterName = it.name },
                )
            }
            if (filteredEntries.isEmpty()) {
                item {
                    Text(
                        text = appString(R.string.standard_collection_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                items(
                    items = filteredEntries,
                    key = { it.card.id },
                ) { entry ->
                    val obtained = entry.card.id in obtainedCardIds
                    StandardCollectionCard(
                        entry = entry,
                        obtained = obtained,
                        onOpen = if (obtained) {
                            { selectedCardId = entry.card.id }
                        } else {
                            null
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(ChanrivaSpacing.page)) }
        }
    }

    selectedCardId
        ?.let(snapshot::card)
        ?.takeIf { it.card.id in obtainedCardIds }
        ?.let { entry ->
            StandardCollectionDetailDialog(
                entry = entry,
                onDismiss = { selectedCardId = null },
            )
        }
}

@Composable
private fun StandardCollectionProgress(
    obtainedCount: Int,
    totalCount: Int,
) {
    val progress = if (totalCount == 0) 0f else obtainedCount.toFloat() / totalCount.toFloat()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(ChanrivaSpacing.section),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
        ) {
            Text(
                text = appString(
                    R.string.standard_collection_progress,
                    obtainedCount,
                    totalCount,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (obtainedCount == 0) {
                    appString(R.string.standard_collection_progress_empty)
                } else {
                    appString(R.string.standard_collection_progress_active)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun StandardCollectionFilters(
    filters: List<StandardCollectionFilter>,
    selected: StandardCollectionFilter,
    onSelected: (StandardCollectionFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelected(filter) },
                label = { Text(appString(filter.labelRes)) },
            )
        }
    }
}

@Composable
private fun StandardCollectionCard(
    entry: StandardContentEntry,
    obtained: Boolean,
    onOpen: (() -> Unit)?,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ChanrivaSpacing.card),
            horizontalArrangement = Arrangement.spacedBy(ChanrivaSpacing.card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StandardCollectionArtwork(
                entry = entry,
                obtained = obtained,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = appString(entry.card.type.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = ChanrivaColors.accent,
                )
                Text(
                    text = if (obtained) entry.card.title else appString(R.string.standard_collection_unknown_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (obtained) {
                        entry.card.summary
                    } else {
                        appString(R.string.standard_collection_locked_supporting)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (obtained) {
                        appString(R.string.standard_collection_obtained)
                    } else {
                        appString(R.string.standard_collection_unobtained)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (obtained) ChanrivaColors.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (onOpen != null) {
        Card(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ChanrivaColors.surfaceElevated),
            content = { content() },
        )
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = ChanrivaColors.surfaceElevated.copy(alpha = 0.72f),
            ),
            content = { content() },
        )
    }
}

@Composable
private fun StandardCollectionArtwork(
    entry: StandardContentEntry,
    obtained: Boolean,
) {
    val bitmap = remember(entry.imageFile?.absolutePath, obtained) {
        if (obtained) {
            entry.imageFile
                ?.takeIf { it.isFile }
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?.asImageBitmap()
        } else {
            null
        }
    }

    Surface(
        modifier = Modifier.size(72.dp),
        shape = MaterialTheme.shapes.medium,
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
                    text = if (obtained) {
                        appString(entry.card.type.shortLabelRes)
                    } else {
                        "?"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StandardCollectionDetailDialog(
    entry: StandardContentEntry,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.card.title)
                Text(
                    text = appString(entry.card.rarity.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = ChanrivaColors.accent,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(entry.card.summary)
                entry.card.body?.let { Text(it) }
                entry.card.attributes["author"]?.let {
                    StandardCollectionMetadataRow(
                        label = appString(R.string.standard_collection_author),
                        value = it,
                    )
                }
                entry.card.attributes["publisher"]?.let {
                    StandardCollectionMetadataRow(
                        label = appString(R.string.standard_collection_publisher),
                        value = it,
                    )
                }
                entry.card.attributes["isbn"]?.let {
                    StandardCollectionMetadataRow(
                        label = appString(R.string.standard_collection_isbn),
                        value = it,
                    )
                }
                entry.card.attributes["published"]?.let {
                    StandardCollectionMetadataRow(
                        label = appString(R.string.standard_collection_published),
                        value = it,
                    )
                }
                entry.card.sourceLabel?.let {
                    StandardCollectionMetadataRow(
                        label = appString(R.string.standard_collection_source),
                        value = it,
                    )
                }
                entry.card.sourceUrl?.let { url ->
                    OutlinedButton(
                        onClick = { context.openStandardCollectionUrl(url) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(appString(R.string.standard_collection_open_source))
                    }
                }
                entry.card.externalUrl
                    ?.takeIf { it != entry.card.sourceUrl }
                    ?.let { url ->
                        OutlinedButton(
                            onClick = { context.openStandardCollectionUrl(url) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(appString(R.string.standard_collection_open_external))
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(appString(R.string.standard_collection_close))
            }
        },
    )
}

@Composable
private fun StandardCollectionMetadataRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun android.content.Context.openStandardCollectionUrl(url: String) {
    runCatching {
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private val StandardCollectionFilter.labelRes: Int
    get() = when (this) {
        StandardCollectionFilter.ALL -> R.string.standard_collection_filter_all
        StandardCollectionFilter.TRIVIA -> R.string.standard_collection_category_trivia
        StandardCollectionFilter.BOOK -> R.string.standard_collection_category_book
        StandardCollectionFilter.HISTORY -> R.string.standard_collection_category_history
        StandardCollectionFilter.PERSON -> R.string.standard_collection_category_person
        StandardCollectionFilter.COLLAB -> R.string.standard_collection_category_collab
        StandardCollectionFilter.UNOBTAINED -> R.string.standard_collection_filter_unobtained
    }

private val StandardContentCardType.labelRes: Int
    get() = when (this) {
        StandardContentCardType.TRIVIA -> R.string.standard_collection_category_trivia
        StandardContentCardType.BOOK -> R.string.standard_collection_category_book
        StandardContentCardType.PERSON -> R.string.standard_collection_category_person
        StandardContentCardType.HISTORY -> R.string.standard_collection_category_history
        StandardContentCardType.COLLAB -> R.string.standard_collection_category_collab
    }

private val StandardContentCardType.shortLabelRes: Int
    get() = when (this) {
        StandardContentCardType.TRIVIA -> R.string.standard_collection_short_trivia
        StandardContentCardType.BOOK -> R.string.standard_collection_short_book
        StandardContentCardType.PERSON -> R.string.standard_collection_short_person
        StandardContentCardType.HISTORY -> R.string.standard_collection_short_history
        StandardContentCardType.COLLAB -> R.string.standard_collection_short_collab
    }

private val StandardContentRarity.labelRes: Int
    get() = when (this) {
        StandardContentRarity.COMMON -> R.string.standard_collection_rarity_common
        StandardContentRarity.RARE -> R.string.standard_collection_rarity_rare
        StandardContentRarity.SPECIAL -> R.string.standard_collection_rarity_special
    }

@Composable
private fun StandardCollectionSurface(
    content: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
        ) {
            content()
        }
    }
}
