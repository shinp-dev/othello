package com.example.othello

import androidx.annotation.DrawableRes
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class ChanrivaNameCandidate(
    val id: String,
    val displayName: String,
    val isRare: Boolean,
    @DrawableRes val plateRes: Int,
)

private val NameGold = Color(0xFFFFDA74)
private val NameIvory = Color(0xFFFFF8E8)
private val NameDeepGreen = Color(0xCC062A28)
private val NameDeepBlue = Color(0xD9081C35)

private fun initialChanrivaNameCandidates() = listOf(
    ChanrivaNameCandidate(
        id = "soft-sunlight",
        displayName = "やわらかひなた",
        isRare = false,
        plateRes = R.drawable.chanriva_name_plate_emerald,
    ),
    ChanrivaNameCandidate(
        id = "gentle-mai",
        displayName = "ほんわか麻衣",
        isRare = false,
        plateRes = R.drawable.chanriva_name_plate_starry,
    ),
    ChanrivaNameCandidate(
        id = "slow-snail",
        displayName = "のんびりかたつむり",
        isRare = true,
        plateRes = R.drawable.chanriva_name_plate_rare,
    ),
)

private fun rerolledChanrivaNameCandidates() = listOf(
    ChanrivaNameCandidate(
        id = "quiet-moon-shadow",
        displayName = "しずかな月影",
        isRare = false,
        plateRes = R.drawable.chanriva_name_plate_starry,
    ),
    ChanrivaNameCandidate(
        id = "star-reader",
        displayName = "星読みしおり",
        isRare = false,
        plateRes = R.drawable.chanriva_name_plate_emerald,
    ),
    ChanrivaNameCandidate(
        id = "emerald-firefly",
        displayName = "翠玉のほたる",
        isRare = true,
        plateRes = R.drawable.chanriva_name_plate_rare,
    ),
)

@Composable
internal fun ChanrivaNameSelectionScreen(
    onBack: () -> Unit,
    onNameConfirmed: (ChanrivaNameCandidate) -> Unit,
) {
    var candidates by remember { mutableStateOf(initialChanrivaNameCandidates()) }
    var selectedId by rememberSaveable { mutableStateOf("slow-snail") }
    var hasRerolled by rememberSaveable { mutableStateOf(false) }

    val selectedCandidate = candidates.firstOrNull { it.id == selectedId } ?: candidates.last()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxWidth < 350.dp
        val horizontalPadding = if (compact) 12.dp else 16.dp
        val titleSize = if (compact) 27.sp else 30.sp
        val candidateHeight = if (compact) 68.dp else 74.dp
        val backgroundImage = rememberNameAsset(R.drawable.chanriva_name_background)

        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                bitmap = backgroundImage,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding)
                    .padding(top = 10.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NameBackButton(onBack)
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                NameSelectionHeader(titleSize = titleSize, compact = compact, hasRerolled = hasRerolled)
                Spacer(Modifier.height(if (compact) 14.dp else 18.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                ) {
                    candidates.forEach { candidate ->
                        ChanrivaNameCandidateCard(
                            candidate = candidate,
                            selected = candidate.id == selectedId,
                            height = candidateHeight,
                            onClick = { selectedId = candidate.id },
                        )
                    }
                }

                Spacer(Modifier.height(if (compact) 14.dp else 18.dp))
                NameSelectionActions(
                    compact = compact,
                    hasRerolled = hasRerolled,
                    onReroll = {
                        candidates = rerolledChanrivaNameCandidates()
                        selectedId = "emerald-firefly"
                        hasRerolled = true
                    },
                    onConfirm = { onNameConfirmed(selectedCandidate) },
                )
            }
        }
    }
}

@Composable
private fun NameBackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(5.dp, CircleShape)
                .clip(CircleShape)
                .background(NameDeepGreen)
                .border(1.5.dp, NameGold.copy(alpha = 0.92f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = appString(R.string.back),
                    tint = NameIvory,
                    modifier = Modifier.size(25.dp),
                )
            }
        }
    }
}

@Composable
private fun NameSelectionHeader(
    titleSize: androidx.compose.ui.unit.TextUnit,
    compact: Boolean,
    hasRerolled: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = appString(R.string.chanriva_name_selection_title),
            modifier = Modifier.fillMaxWidth(),
            color = NameIvory,
            fontSize = titleSize,
            lineHeight = titleSize * 1.18f,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        Text(
            text = appString(R.string.chanriva_name_selection_supporting),
            color = NameIvory.copy(alpha = 0.94f),
            fontSize = if (compact) 14.sp else 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(7.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = NameGold,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(5.dp))
            Text(
                text = if (hasRerolled) {
                    appString(R.string.chanriva_name_selection_rerolled)
                } else {
                    appString(R.string.chanriva_name_selection_reroll_available)
                },
                color = NameGold,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ChanrivaNameCandidateCard(
    candidate: ChanrivaNameCandidate,
    selected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(20.dp)
    val compact = height < 70.dp
    val plateImage = rememberNameAsset(candidate.plateRes)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .shadow(if (selected) 8.dp else 2.dp, cardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button }
            .then(
                if (selected) Modifier.border(2.dp, NameGold, cardShape)
                else Modifier.border(1.dp, NameGold.copy(alpha = 0.68f), cardShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = plateImage,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        if (selected) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, NameGold.copy(alpha = 0.08f), Color.Transparent),
                    ),
                ),
            )
        }
        Text(
            text = candidate.displayName,
            modifier = Modifier.padding(horizontal = if (compact) 32.dp else 48.dp),
            color = NameIvory,
            fontSize = if (compact) 21.sp else 24.sp,
            lineHeight = if (compact) 25.sp else 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (candidate.isRare) {
            RareBadge()
        }
    }
}

@Composable
private fun BoxScope.RareBadge() {
    val badgeImage = rememberNameAsset(R.drawable.chanriva_name_rare_badge)
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = (-5).dp, y = (-8).dp)
            .size(width = 112.dp, height = 56.dp),
    ) {
        Image(
            bitmap = badgeImage,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Text(
            text = appString(R.string.chanriva_name_selection_rare),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 9.dp, top = 1.dp),
            color = NameIvory,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun rememberNameAsset(@DrawableRes resourceId: Int): ImageBitmap {
    val resources = LocalContext.current.resources
    return remember(resources, resourceId) {
        val bytes = resources.openRawResource(resourceId).use { it.readBytes() }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "Unable to decode Chanriva name selection image resource: $resourceId"
        }
        bitmap.asImageBitmap()
    }
}

@Composable
private fun NameSelectionActions(
    compact: Boolean,
    hasRerolled: Boolean,
    onReroll: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
    ) {
        NameActionButton(
            text = if (hasRerolled) {
                appString(R.string.chanriva_name_selection_reroll_used)
            } else {
                appString(R.string.chanriva_name_selection_reroll)
            },
            icon = { Icon(Icons.Default.Cached, contentDescription = null, modifier = Modifier.size(21.dp)) },
            enabled = !hasRerolled,
            primary = false,
            onClick = onReroll,
        )
        NameActionButton(
            text = appString(R.string.chanriva_name_selection_confirm),
            icon = { Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(24.dp)) },
            enabled = true,
            primary = true,
            onClick = onConfirm,
        )
    }
}

@Composable
private fun NameActionButton(
    text: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    val background = if (primary) {
        Brush.horizontalGradient(listOf(Color(0xF006493F), Color(0xF00B5144), Color(0xF006493F)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xE9081B31), Color(0xE9162B43), Color(0xE9081B31)))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth(if (primary) 0.86f else 0.72f)
            .height(if (primary) 56.dp else 48.dp)
            .shadow(if (enabled) 5.dp else 0.dp, shape)
            .clip(shape)
            .background(background)
            .border(
                width = if (primary) 1.7.dp else 1.dp,
                color = if (enabled) NameGold else NameGold.copy(alpha = 0.35f),
                shape = shape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val contentColor = if (enabled) NameIvory else NameIvory.copy(alpha = 0.43f)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) {
            icon()
            Spacer(Modifier.size(8.dp))
            Text(
                text = text,
                color = contentColor,
                fontSize = if (primary) 20.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}
