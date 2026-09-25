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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON -> AuthenticatedModeDestination.STANDARD_HOME
    AuthenticatedModeDestination.STANDARD_REAL_EVENT -> AuthenticatedModeDestination.MODE_SELECTION
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
    val backDestination = authenticatedModeBackDestination(destination)
    BackHandler(enabled = backDestination != null) {
        destination = requireNotNull(backDestination)
    }

    when (destination) {
        AuthenticatedModeDestination.MODE_SELECTION -> ModeSelectionScreen(
            onSelect = { destination = destinationFor(it) },
            onRealEvent = { destination = AuthenticatedModeDestination.STANDARD_REAL_EVENT },
        )
        AuthenticatedModeDestination.ADVANCED -> advancedContent {
            destination = AuthenticatedModeDestination.MODE_SELECTION
        }
        AuthenticatedModeDestination.STANDARD_REAL_EVENT -> StandardRealEventRoute(
            onBack = { destination = AuthenticatedModeDestination.MODE_SELECTION },
        )
        else -> StandardBootstrapRoute(
            userId = userId,
            onBack = { destination = AuthenticatedModeDestination.MODE_SELECTION },
        ) { content, opponents, aiState, onRetryAi ->
            when (destination) {
                AuthenticatedModeDestination.STANDARD_HOME -> StandardHomeScreen(
                    opponents = opponents,
                    onPackSelected = { selectedPackId = it; destination = AuthenticatedModeDestination.STANDARD_AI },
                    onWinningTips = { destination = AuthenticatedModeDestination.STANDARD_WINNING_TIPS },
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
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.standard_screen_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f)))
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 54.dp, bottom = 16.dp),
        ) {
            val animalPack = requireNotNull(opponents.pack("animal")) { "Animal opponent pack is required" }
            val kingPack = requireNotNull(opponents.pack("lione-boss")) { "King opponent pack is required" }
            val featuredCardHeight = maxOf(170.dp, maxWidth * 0.55f)

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = appString(R.string.standard_home_heading),
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                StandardHomeFeatureCard(
                    title = appString(R.string.standard_home_animals_title),
                    supportingText = appString(R.string.standard_home_animals_supporting),
                    backgroundDrawableRes = R.drawable.card_animals_bg,
                    heroImage = animalPack.image(animalPack.definition.banner),
                    onClick = { onPackSelected(animalPack.definition.id) },
                    modifier = Modifier.fillMaxWidth().height(featuredCardHeight),
                )
                StandardHomeFeatureCard(
                    title = appString(R.string.standard_home_king_title),
                    supportingText = appString(R.string.standard_home_king_supporting),
                    backgroundDrawableRes = R.drawable.card_lion_bg,
                    heroImage = kingPack.image(kingPack.definition.banner),
                    onClick = { onPackSelected(kingPack.definition.id) },
                    modifier = Modifier.fillMaxWidth().height(featuredCardHeight),
                )
                StandardHomeTipsCard(
                    title = appString(R.string.standard_winning_tips_title),
                    supportingText = appString(R.string.standard_home_tips_supporting),
                    onClick = onWinningTips,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
            }
        }
    }
}

@Composable
private fun StandardHomeFeatureCard(
    title: String,
    supportingText: String,
    @DrawableRes backgroundDrawableRes: Int,
    heroImage: java.io.File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = standardHomeCardBorder(),
        elevation = standardHomeCardElevation(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(backgroundDrawableRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xE6001010),
                            Color(0xB5001010),
                            Color(0x52001010),
                            Color(0x10001010),
                        ),
                    ),
                ),
            )
            AsyncImage(
                model = heroImage,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.88f)
                    .height(maxHeight * 0.62f),
                contentScale = ContentScale.Fit,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.74f)
                    .padding(start = 18.dp, top = 18.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = if (maxWidth < 320.dp) 21.sp else if (maxWidth < 370.dp) 24.sp else 27.sp,
                        lineHeight = if (maxWidth < 320.dp) 25.sp else 31.sp,
                    ),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
                    color = Color.White.copy(alpha = 0.96f),
                    maxLines = 3,
                    overflow = TextOverflow.Clip,
                )
            }
            StandardHomeArrow(Modifier.align(Alignment.TopEnd).padding(14.dp))
        }
    }
}

@Composable
private fun StandardHomeTipsCard(
    title: String,
    supportingText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = standardHomeCardBorder(),
        elevation = standardHomeCardElevation(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.card_tips_bg),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Color(0xE6001010), Color(0xA8001010), Color(0x30001010), Color(0x08001010)),
                    ),
                ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.74f)
                    .padding(start = 18.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = Color.White.copy(alpha = 0.96f),
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                )
            }
            StandardHomeArrow(Modifier.align(Alignment.CenterEnd).padding(14.dp))
        }
    }
}

@Composable
private fun StandardHomeArrow(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(42.dp)
            .background(Color(0xD9004B45), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.76f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "›",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun standardHomeCardBorder(): BorderStroke = BorderStroke(
    width = 1.dp,
    color = Color(0xFFB7E7C9).copy(alpha = 0.9f),
)

@Composable
private fun standardHomeCardElevation() = CardDefaults.cardElevation(
    defaultElevation = 3.dp,
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
