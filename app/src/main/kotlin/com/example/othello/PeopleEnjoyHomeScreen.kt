package com.example.othello

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.othello.designsystem.ChanrivaScreenHeader

private val PeopleGold = Color(0xFFE7C875)
private val PeopleIvory = Color(0xFFFFF8E8)
private val PeopleForest = Color(0xFF063B35)

@Composable
internal fun PeopleEnjoyHomeScreen(
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onEvents: () -> Unit,
    onCommunity: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.people_home_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(Color(0x3303131F)))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xCC082B2A))
                        .border(1.dp, PeopleGold.copy(alpha = 0.85f), CircleShape)
                        .clickable(role = Role.Button, onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "‹  ${appString(R.string.back)}",
                        color = PeopleIvory,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = appString(R.string.people_home_title),
                        color = PeopleIvory,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Spacer(Modifier.width(64.dp))
            }

            PeopleHomeCard(
                title = appString(R.string.people_play_title),
                subtitle = appString(R.string.people_play_supporting),
                imageRes = R.drawable.people_play_card,
                onClick = onPlay,
            )
            PeopleHomeCard(
                title = appString(R.string.people_events_title),
                subtitle = appString(R.string.people_events_supporting),
                imageRes = R.drawable.people_event_card,
                onClick = onEvents,
            )
            PeopleHomeCard(
                title = appString(R.string.people_social_title),
                subtitle = appString(R.string.people_social_supporting),
                imageRes = R.drawable.people_social_card,
                onClick = onCommunity,
            )
        }
    }
}

@Composable
private fun PeopleHomeCard(
    title: String,
    subtitle: String,
    imageRes: Int,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(164.dp),
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(1.25.dp, PeopleGold.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp, pressedElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Color(0xE807171B),
                        0.48f to Color(0xB50A181C),
                        0.76f to Color(0x3D071317),
                        1f to Color(0x1A071317),
                    ),
                ),
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = title,
                        color = PeopleIvory,
                        fontSize = 24.sp,
                        lineHeight = 31.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = subtitle,
                        color = Color(0xFFFFF0CE),
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(PeopleForest.copy(alpha = 0.92f), CircleShape)
                        .border(1.5.dp, PeopleGold, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "›",
                        color = PeopleIvory,
                        fontSize = 32.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PeopleComingSoonScreen(title: String, onBack: () -> Unit) {
    androidx.compose.material3.Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ChanrivaScreenHeader(title, onBack, backLabel = appString(R.string.back))
            Text(
                text = appString(R.string.feature_coming_soon),
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
