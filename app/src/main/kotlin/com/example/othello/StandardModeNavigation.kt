package com.example.othello

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
): AuthenticatedModeDestination? = when (current) {
    AuthenticatedModeDestination.STANDARD_HOME -> AuthenticatedModeDestination.MODE_SELECTION
    AuthenticatedModeDestination.STANDARD_AI,
    AuthenticatedModeDestination.STANDARD_WINNING_TIPS,
    AuthenticatedModeDestination.STANDARD_GACHA,
    AuthenticatedModeDestination.STANDARD_COLLECTION,
    AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON,
    AuthenticatedModeDestination.STANDARD_REAL_EVENT -> AuthenticatedModeDestination.STANDARD_HOME
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
    val backDestination = authenticatedModeBackDestination(destination)
    BackHandler(enabled = backDestination != null) {
        destination = requireNotNull(authenticatedModeBackDestination(destination))
    }

    when (destination) {
        AuthenticatedModeDestination.MODE_SELECTION -> ModeSelectionScreen(
            onSelect = { destination = destinationFor(it) },
        )
        AuthenticatedModeDestination.STANDARD_HOME -> StandardHomeScreen(
            onFeature = { destination = destinationFor(it) },
            onWinningTips = { destination = AuthenticatedModeDestination.STANDARD_WINNING_TIPS },
            onGacha = { destination = AuthenticatedModeDestination.STANDARD_GACHA },
            onCollection = { destination = AuthenticatedModeDestination.STANDARD_COLLECTION },
            onSwitchMode = { destination = AuthenticatedModeDestination.MODE_SELECTION },
        )
        AuthenticatedModeDestination.STANDARD_AI -> StandardAiRoute(
            userId = userId,
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
        )
        AuthenticatedModeDestination.STANDARD_WINNING_TIPS -> StandardWinningTipsRoute(
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
        )
        AuthenticatedModeDestination.STANDARD_GACHA -> StandardGachaRoute(
            userId = userId,
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
            onCollection = { destination = AuthenticatedModeDestination.STANDARD_COLLECTION },
        )
        AuthenticatedModeDestination.STANDARD_COLLECTION -> StandardCollectionRoute(
            userId = userId,
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
        )
        AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON -> StandardComingSoonScreen(
            feature = StandardFeature.ONLINE,
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
        )
        AuthenticatedModeDestination.STANDARD_REAL_EVENT -> StandardRealEventRoute(
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
        )
        AuthenticatedModeDestination.ADVANCED -> advancedContent {
            destination = AuthenticatedModeDestination.MODE_SELECTION
        }
    }
}

@Composable
private fun ModeSelectionScreen(onSelect: (AppMode) -> Unit) {
    StandardSurface {
        ChanrivaScreenHeader(title = appString(R.string.mode_selection_title))
        Text(
            text = appString(R.string.mode_selection_supporting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModeChoiceCard(
            title = appString(R.string.standard_mode),
            badge = appString(R.string.mode_selection_recommended),
            supportingText = appString(R.string.mode_selection_standard_supporting),
            features = appString(R.string.mode_selection_standard_features),
            highlighted = true,
            onClick = { onSelect(AppMode.STANDARD) },
        ) {
            Image(
                painter = painterResource(StandardOpponentPacks.animal.bannerDrawableRes),
                contentDescription = appString(R.string.standard_ai_pack_preview_description),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f),
                contentScale = ContentScale.Fit,
            )
        }
        ModeChoiceCard(
            title = appString(R.string.advanced_mode),
            badge = appString(R.string.mode_selection_advanced_badge),
            supportingText = appString(R.string.mode_selection_advanced_supporting),
            features = appString(R.string.mode_selection_advanced_features),
            highlighted = false,
            onClick = { onSelect(AppMode.ADVANCED) },
        ) {
            AdvancedModePreview()
        }
        Text(
            text = appString(R.string.mode_selection_switch_note),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ModeChoiceCard(
    title: String,
    badge: String,
    supportingText: String,
    features: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    visual: @Composable () -> Unit,
) {
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        ChanrivaColors.surfaceElevated
    }
    val contentColor = if (highlighted) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderModifier = if (highlighted) {
        Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            shape = MaterialTheme.shapes.extraLarge,
        )
    } else {
        Modifier
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(ChanrivaSpacing.section),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Text(
                    text = badge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlighted) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = features,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            visual()
        }
    }
}

@Composable
private fun AdvancedModePreview() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniAnalysisBoard()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = appString(R.string.mode_selection_analysis_preview),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "+3.2",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = appString(R.string.mode_selection_study_preview),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MiniAnalysisBoard() {
    val blackDiscs = setOf(3 to 3, 4 to 4, 2 to 3, 5 to 4, 2 to 5)
    val whiteDiscs = setOf(3 to 4, 4 to 3, 2 to 4, 5 to 3, 4 to 5)

    Column(
        modifier = Modifier
            .size(104.dp)
            .background(ChanrivaColors.board)
            .padding(2.dp),
    ) {
        repeat(8) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(8) { column ->
                    val coordinate = row to column
                    val discColor: Color? = when (coordinate) {
                        in blackDiscs -> ChanrivaColors.blackDisc
                        in whiteDiscs -> ChanrivaColors.whiteDisc
                        else -> null
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.35.dp, ChanrivaColors.boardGrid),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (discColor != null) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                                    .background(discColor, CircleShape)
                                    .border(0.5.dp, ChanrivaColors.discOutline, CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandardHomeScreen(
    onFeature: (StandardFeature) -> Unit,
    onWinningTips: () -> Unit,
    onGacha: () -> Unit,
    onCollection: () -> Unit,
    onSwitchMode: () -> Unit,
) {
    StandardSurface {
        ChanrivaScreenHeader(title = appString(R.string.standard_mode))
        StandardOpponentPackPreviewCard(
            pack = StandardOpponentPacks.animal,
            onClick = { onFeature(StandardFeature.AI) },
        )
        ModeCard(
            title = appString(R.string.standard_winning_tips_title),
            supportingText = appString(R.string.standard_winning_tips_home_supporting),
            onClick = onWinningTips,
        )
        ModeCard(
            title = appString(R.string.standard_gacha_title),
            supportingText = appString(R.string.standard_gacha_home_supporting),
            onClick = onGacha,
        )
        ModeCard(
            title = appString(R.string.standard_collection_title),
            supportingText = appString(R.string.standard_collection_home_supporting),
            onClick = onCollection,
        )
        ModeCard(
            title = appString(StandardFeature.REAL_EVENT.titleRes),
            supportingText = appString(StandardFeature.REAL_EVENT.supportingTextRes),
            onClick = { onFeature(StandardFeature.REAL_EVENT) },
        )
        TextButton(
            onClick = onSwitchMode,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(appString(R.string.switch_to_advanced_mode))
        }
    }
}

@Composable
private fun StandardOpponentPackPreviewCard(
    pack: StandardOpponentPackUi,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 164.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(ChanrivaSpacing.section),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
        ) {
            Text(
                text = appString(R.string.standard_ai_pack_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = appString(pack.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = appString(pack.supportingTextRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Image(
                painter = painterResource(pack.bannerDrawableRes),
                contentDescription = appString(R.string.standard_ai_pack_preview_description),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

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

@Composable
private fun ModeCard(
    title: String,
    supportingText: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp),
        colors = CardDefaults.cardColors(containerColor = ChanrivaColors.surfaceElevated),
    ) {
        Column(
            modifier = Modifier.padding(ChanrivaSpacing.section),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
