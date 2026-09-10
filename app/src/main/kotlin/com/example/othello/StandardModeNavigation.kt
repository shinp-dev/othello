package com.example.othello

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.painterResource
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
    STANDARD_COLLECTION,
    STANDARD_ONLINE_COMING_SOON,
    STANDARD_REAL_EVENT_COMING_SOON,
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
    StandardFeature.REAL_EVENT -> AuthenticatedModeDestination.STANDARD_REAL_EVENT_COMING_SOON
}

internal fun authenticatedModeBackDestination(
    current: AuthenticatedModeDestination,
): AuthenticatedModeDestination? = when (current) {
    AuthenticatedModeDestination.STANDARD_HOME -> AuthenticatedModeDestination.MODE_SELECTION
    AuthenticatedModeDestination.STANDARD_AI,
    AuthenticatedModeDestination.STANDARD_COLLECTION,
    AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON,
    AuthenticatedModeDestination.STANDARD_REAL_EVENT_COMING_SOON -> AuthenticatedModeDestination.STANDARD_HOME
    AuthenticatedModeDestination.MODE_SELECTION,
    AuthenticatedModeDestination.ADVANCED -> null
}

@Composable
internal fun AuthenticatedModeRoute(
    userId: String,
    advancedContent: @Composable () -> Unit,
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
            onCollection = { destination = AuthenticatedModeDestination.STANDARD_COLLECTION },
            onSwitchMode = { destination = AuthenticatedModeDestination.MODE_SELECTION },
        )
        AuthenticatedModeDestination.STANDARD_AI -> StandardAiRoute(
            userId = userId,
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
        )
        AuthenticatedModeDestination.STANDARD_COLLECTION -> StandardCollectionRoute(
            userId = userId,
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
        )
        AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON -> StandardComingSoonScreen(
            feature = StandardFeature.ONLINE,
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
        )
        AuthenticatedModeDestination.STANDARD_REAL_EVENT_COMING_SOON -> StandardComingSoonScreen(
            feature = StandardFeature.REAL_EVENT,
            onBack = { destination = AuthenticatedModeDestination.STANDARD_HOME },
        )
        AuthenticatedModeDestination.ADVANCED -> advancedContent()
    }
}

@Composable
private fun ModeSelectionScreen(onSelect: (AppMode) -> Unit) {
    StandardSurface {
        ChanrivaScreenHeader(title = appString(R.string.choose_mode))
        Text(
            text = appString(R.string.choose_mode_supporting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModeCard(
            title = appString(R.string.standard_mode),
            supportingText = appString(R.string.standard_mode_supporting),
            onClick = { onSelect(AppMode.STANDARD) },
        )
        ModeCard(
            title = appString(R.string.advanced_mode),
            supportingText = appString(R.string.advanced_mode_supporting),
            onClick = { onSelect(AppMode.ADVANCED) },
        )
    }
}

@Composable
private fun StandardHomeScreen(
    onFeature: (StandardFeature) -> Unit,
    onCollection: () -> Unit,
    onSwitchMode: () -> Unit,
) {
    StandardSurface {
        ChanrivaScreenHeader(title = appString(R.string.standard_mode))
        ModeCard(
            title = appString(StandardFeature.AI.titleRes),
            supportingText = appString(StandardFeature.AI.supportingTextRes),
            onClick = { onFeature(StandardFeature.AI) },
        )
        StandardOpponentPackPreviewCard(
            pack = StandardOpponentPacks.animal,
            onClick = { onFeature(StandardFeature.AI) },
        )
        ModeCard(
            title = appString(R.string.standard_collection_title),
            supportingText = appString(R.string.standard_collection_home_supporting),
            onClick = onCollection,
        )
        listOf(StandardFeature.ONLINE, StandardFeature.REAL_EVENT).forEach { feature ->
            ModeCard(
                title = appString(feature.titleRes),
                supportingText = appString(feature.supportingTextRes),
                onClick = { onFeature(feature) },
            )
        }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pack.previewLevels.forEach { level ->
                    val opponent = pack.opponent(level)
                    Image(
                        painter = painterResource(opponent.winDrawableRes),
                        contentDescription = appString(R.string.standard_ai_pack_preview_description),
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
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
