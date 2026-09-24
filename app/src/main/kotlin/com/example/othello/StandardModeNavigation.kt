package com.example.othello

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import coil3.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing

internal enum class AppMode { STANDARD, ADVANCED }

internal enum class StandardFeature(
    @StringRes val titleRes: Int,
    @StringRes val supportingTextRes: Int,
) {
    AI(R.string.standard_ai_match, R.string.standard_ai_match_supporting),
    ONLINE(R.string.standard_online_match, R.string.standard_online_match_supporting),
    REAL_EVENT(R.string.standard_real_event, R.string.standard_real_event_supporting),
}

internal enum class AuthenticatedModeDestination {
    MODE_SELECTION,
    STANDARD_HOME,
    STANDARD_AI,
    STANDARD_WINNING_TIPS,
    STANDARD_GACHA,
    STANDARD_COLLECTION,
    STANDARD_ONLINE_COMING_SOON,
    STANDARD_REAL_EVENT,
    ADVANCED,
}

internal fun initialAuthenticatedModeDestination(): AuthenticatedModeDestination =
    AuthenticatedModeDestination.MODE_SELECTION

internal fun destinationFor(mode: AppMode): AuthenticatedModeDestination = when (mode) {
    AppMode.STANDARD -> AuthenticatedModeDestination.STANDARD_HOME
    AppMode.ADVANCED -> AuthenticatedModeDestination.ADVANCED
}

internal fun destinationFor(feature: StandardFeature): AuthenticatedModeDestination = when (feature) {
    StandardFeature.AI -> AuthenticatedModeDestination.STANDARD_AI
    StandardFeature.ONLINE -> AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON
    StandardFeature.REAL_EVENT -> AuthenticatedModeDestination.STANDARD_REAL_EVENT
}

internal fun authenticatedModeBackDestination(
    current: AuthenticatedModeDestination,
    realEventParent: AuthenticatedModeDestination = AuthenticatedModeDestination.STANDARD_HOME,
): AuthenticatedModeDestination? = when (current) {
    AuthenticatedModeDestination.STANDARD_HOME -> AuthenticatedModeDestination.MODE_SELECTION
    AuthenticatedModeDestination.STANDARD_AI,
    AuthenticatedModeDestination.STANDARD_WINNING_TIPS,
    AuthenticatedModeDestination.STANDARD_GACHA,
    AuthenticatedModeDestination.STANDARD_COLLECTION,
    AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON -> AuthenticatedModeDestination.STANDARD_HOME
    AuthenticatedModeDestination.STANDARD_REAL_EVENT -> realEventParent
    AuthenticatedModeDestination.MODE_SELECTION -> null
    AuthenticatedModeDestination.ADVANCED -> AuthenticatedModeDestination.MODE_SELECTION
}

@Composable
internal fun AuthenticatedModeRoute(
    userId: String,
    advancedContent: @Composable (onSwitchMode: () -> Unit) -> Unit,
) {
    var destination by rememberSaveable(userId) {
        mutableStateOf(initialAuthenticatedModeDestination())
    }
    var selectedPackId by rememberSaveable(userId) { mutableStateOf<String?>(null) }
    var realEventParent by rememberSaveable(userId) { mutableStateOf(AuthenticatedModeDestination.STANDARD_HOME) }
    val backDestination = authenticatedModeBackDestination(destination, realEventParent)
    BackHandler(enabled = backDestination != null) {
        destination = requireNotNull(backDestination)
    }

    when (destination) {
        AuthenticatedModeDestination.MODE_SELECTION -> ModeSelectionScreen(
            onSelect = { destination = destinationFor(it) },
            onRealEvent = {
                realEventParent = AuthenticatedModeDestination.MODE_SELECTION
                destination = AuthenticatedModeDestination.STANDARD_REAL_EVENT
            },
        )
        AuthenticatedModeDestination.ADVANCED -> advancedContent {
            destination = AuthenticatedModeDestination.MODE_SELECTION
        }
        else -> StandardBootstrapRoute(
            userId = userId,
            onBack = { destination = AuthenticatedModeDestination.MODE_SELECTION },
        ) { content, opponents, aiState, onRetryAi ->
            when (destination) {
                AuthenticatedModeDestination.STANDARD_HOME -> StandardHomeScreen(
                    opponents = opponents,
                    onPackSelected = { selectedPackId = it; destination = AuthenticatedModeDestination.STANDARD_AI },
                    onWinningTips = { destination = AuthenticatedModeDestination.STANDARD_WINNING_TIPS },
                    onGacha = { destination = AuthenticatedModeDestination.STANDARD_GACHA },
                    onCollection = { destination = AuthenticatedModeDestination.STANDARD_COLLECTION },
                    onSwitchMode = { destination = AuthenticatedModeDestination.MODE_SELECTION },
                )
                AuthenticatedModeDestination.STANDARD_AI -> StandardAiPackRoute(
                    opponents = opponents,
                    selectedPackId = selectedPackId,
                    userId = userId,
                    preparationState = aiState,
                    onRetryPreparation = onRetryAi,
                    onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
                )
                AuthenticatedModeDestination.STANDARD_WINNING_TIPS -> StandardWinningTipsRoute(
                    onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
                )
                AuthenticatedModeDestination.STANDARD_GACHA -> StandardGachaRoute(
                    userId = userId,
                    content = content,
                    onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
                    onCollection = { destination = AuthenticatedModeDestination.STANDARD_COLLECTION },
                )
                AuthenticatedModeDestination.STANDARD_COLLECTION -> StandardCollectionRoute(
                    userId = userId,
                    content = content,
                    onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
                )
                AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON -> StandardComingSoonScreen(
                    feature = StandardFeature.ONLINE,
                    onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
                )
                AuthenticatedModeDestination.STANDARD_REAL_EVENT -> StandardRealEventRoute(
                    onBack = { destination = realEventParent },
                )
                else -> error("Not a Standard destination: $destination")
            }
        }
    }
}

@Composable
internal fun StandardHomeScreen(
    opponents: OpponentPackSnapshot,
    onPackSelected: (String) -> Unit,
    onWinningTips: () -> Unit,
    onGacha: () -> Unit,
    onCollection: () -> Unit,
    onSwitchMode: () -> Unit,
) {
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ChanrivaSpacing.page, vertical = ChanrivaSpacing.compact),
        ) {
            val compact = maxHeight < 700.dp
            val itemSpacing = if (compact) 8.dp else ChanrivaSpacing.compact
            val wideCardHeight = if (compact) 82.dp else 96.dp
            val miniCardHeight = if (compact) 68.dp else 78.dp
            val miniArtworkSize = if (compact) 44.dp else 52.dp

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
            ) {
                ChanrivaScreenHeader(title = appString(R.string.standard_mode))
                OpponentHomeSectionCards(opponents, OpponentHomeSection.FEATURED, onPackSelected)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(miniCardHeight),
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                ) {
                    StandardHomeMiniFeatureCard(
                        title = appString(R.string.standard_gacha_title),
                        artworkDrawableRes = R.drawable.standard_home_gacha_icon,
                        artworkSize = miniArtworkSize,
                        onClick = onGacha,
                        modifier = Modifier.weight(1f),
                    )
                    StandardHomeMiniFeatureCard(
                        title = appString(R.string.standard_collection_title),
                        artworkDrawableRes = R.drawable.standard_home_collection_icon,
                        artworkSize = miniArtworkSize,
                        onClick = onCollection,
                        modifier = Modifier.weight(1f),
                    )
                }
                OpponentHomeSectionCards(opponents, OpponentHomeSection.CHALLENGES, onPackSelected)
                StandardHomeWideFeatureCard(
                    title = appString(R.string.standard_winning_tips_title),
                    supportingText = appString(R.string.standard_winning_tips_home_supporting),
                    artworkDrawableRes = R.drawable.standard_home_winning_tips_art,
                    onClick = onWinningTips,
                    modifier = Modifier.height(wideCardHeight),
                )
                Spacer(Modifier.height(itemSpacing))
                TextButton(
                    onClick = onSwitchMode,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(appString(R.string.switch_to_advanced_mode))
                }
            }
        }
    }
}

@Composable
private fun StandardOpponentPackPreviewCard(
    installedPack: InstalledOpponentPack,
    artworkHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pack = installedPack.definition
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = standardHomeCardBorder(),
        elevation = standardHomeCardElevation(),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = installedPack.image(pack.banner),
                contentDescription = appString(R.string.standard_ai_pack_preview_description),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(artworkHeight)
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = ChanrivaSpacing.control),
                contentScale = ContentScale.Fit,
            )
            Column(
                modifier = Modifier.padding(ChanrivaSpacing.section),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = appString(R.string.standard_ai_pack_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = opponentText(pack.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = opponentText(pack.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OpponentHomeSectionCards(snapshot: OpponentPackSnapshot, section: OpponentHomeSection, onSelect: (String) -> Unit) {
    snapshot.visiblePacks().filter { it.definition.home.section == section }.forEach { installed ->
        val hero = installed.definition.home.style == OpponentHomeStyle.HERO
        StandardOpponentPackPreviewCard(
            installedPack = installed,
            artworkHeight = if (hero) 100.dp else 44.dp,
            onClick = { onSelect(installed.definition.id) },
            modifier = Modifier.height(if (hero) 188.dp else 138.dp),
        )
    }
}

@Composable
private fun StandardHomeWideFeatureCard(
    title: String,
    supportingText: String,
    @DrawableRes artworkDrawableRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ChanrivaColors.surfaceElevated),
        border = standardHomeCardBorder(),
        elevation = standardHomeCardElevation(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(artworkDrawableRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                ChanrivaColors.surfaceElevated.copy(alpha = 0.98f),
                                ChanrivaColors.surfaceElevated.copy(alpha = 0.84f),
                                ChanrivaColors.surfaceElevated.copy(alpha = 0.34f),
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.68f)
                    .align(Alignment.CenterStart)
                    .padding(horizontal = ChanrivaSpacing.section),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StandardHomeMiniFeatureCard(
    title: String,
    @DrawableRes artworkDrawableRes: Int,
    artworkSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = ChanrivaColors.surfaceElevated),
        border = standardHomeCardBorder(),
        elevation = standardHomeCardElevation(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = ChanrivaSpacing.control),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Image(
                painter = painterResource(artworkDrawableRes),
                contentDescription = null,
                modifier = Modifier.size(artworkSize),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun standardHomeCardBorder(): BorderStroke = BorderStroke(
    width = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
)

@Composable
private fun standardHomeCardElevation() = CardDefaults.cardElevation(
    defaultElevation = 1.dp,
    pressedElevation = 0.dp,
)

@Composable
private fun StandardComingSoonScreen(
    feature: StandardFeature,
    onBack: () -> Unit,
) {
    StandardSurface {
        ChanrivaScreenHeader(
            title = appString(feature.titleRes),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
        ) {
            Text(
                text = appString(R.string.feature_coming_soon),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = appString(feature.supportingTextRes),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(appString(R.string.back_to_standard_home))
            }
        }
    }
}

@Composable
private fun StandardSurface(content: @Composable ColumnScope.() -> Unit) {
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
