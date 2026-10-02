package com.example.othello

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SocialAccent = Color(0xFF65E0D5)
private val SocialAccentSoft = Color(0xFF4EC7D9)
private val SocialIvory = Color(0xFFFFF8E8)
private val SocialPanel = Color(0xD90A1D2C)
private val SocialRow = Color(0xB90B2234)

private data class PeopleSocialSlot(
    val time: String,
    val people: Int,
)

private val peopleSocialPreviewSlots = listOf(
    PeopleSocialSlot("19:00 - 19:30", 2),
    PeopleSocialSlot("19:30 - 20:00", 4),
    PeopleSocialSlot("20:00 - 20:30", 7),
    PeopleSocialSlot("20:30 - 21:00", 5),
    PeopleSocialSlot("21:00 - 21:30", 3),
    PeopleSocialSlot("21:30 - 22:00", 1),
)

@Composable
internal fun PeopleSocialScreen(
    onBack: () -> Unit,
) {
    var selectedSlots by remember { mutableStateOf(setOf<Int>()) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.people_home_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xD8071727),
                        0.46f to Color(0xE4071421),
                        1f to Color(0xF2071018),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PeopleSocialHeader(onBack = onBack)

            Column(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = appString(R.string.people_social_prompt),
                    color = SocialIvory,
                    fontSize = 25.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = appString(R.string.people_social_relaxed_note),
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SocialNotificationNote()

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = SocialPanel),
                border = BorderStroke(1.dp, SocialAccentSoft.copy(alpha = 0.45f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = appString(R.string.people_social_today),
                            color = SocialIvory,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = appString(R.string.people_social_tap_hint),
                            color = SocialAccent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    peopleSocialPreviewSlots.forEachIndexed { index, slot ->
                        val selected = index in selectedSlots
                        SocialTimeSlot(
                            slot = slot,
                            selected = selected,
                            onClick = {
                                selectedSlots = if (selected) {
                                    selectedSlots - index
                                } else {
                                    selectedSlots + index
                                }
                            },
                        )
                    }
                }
            }

            Text(
                text = appString(R.string.people_social_footer_note),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                color = Color.White.copy(alpha = 0.60f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PeopleSocialHeader(
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SocialIvory),
        ) {
            Text(appString(R.string.back))
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 22.dp)
                .background(SocialAccent),
        )
        Spacer(Modifier.size(9.dp))
        Text(
            text = appString(R.string.people_social_title),
            color = SocialIvory,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SocialNotificationNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SocialAccent.copy(alpha = 0.50f), RoundedCornerShape(18.dp))
            .background(Color(0x9C07303A), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(SocialAccent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_people_social_bell),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = appString(R.string.people_social_notification_note),
            modifier = Modifier.weight(1f),
            color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun SocialTimeSlot(
    slot: PeopleSocialSlot,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(17.dp)
    val borderColor = if (selected) SocialAccent else Color(0xFF2A4A63)
    val container = if (selected) {
        Brush.horizontalGradient(
            listOf(
                Color(0xD10B4A56),
                Color(0xCB0A3547),
            ),
        )
    } else {
        Brush.horizontalGradient(listOf(SocialRow, SocialRow))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, shape)
            .border(
                width = if (selected) 1.4.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = slot.time,
            modifier = Modifier.weight(1f),
            color = SocialIvory,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Row(
            modifier = Modifier.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_people_social_group),
                contentDescription = null,
                modifier = Modifier.size(21.dp),
            )
            Text(
                text = appString(
                    R.string.people_social_people_count,
                    slot.people + if (selected) 1 else 0,
                ),
                color = Color(0xFFB9D8FF),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.size(12.dp))

        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    if (selected) SocialAccent else Color.Transparent,
                    CircleShape,
                )
                .border(
                    1.5.dp,
                    if (selected) SocialAccent else Color(0xFF7E9DB4),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Image(
                    painter = painterResource(R.drawable.ic_people_social_check),
                    contentDescription = appString(R.string.people_social_selected),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}
