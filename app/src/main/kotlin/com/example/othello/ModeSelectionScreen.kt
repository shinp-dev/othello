package com.example.othello

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import com.example.othello.designsystem.OthelloTheme
import kotlin.math.min

private val StageDark = Color(0xFF0C1930)
private val Ink = Color(0xFF1A2838)
private val SoftWhite = Color(0xFFFFF8E9)
private val TitleShadow = Shadow(Color.White.copy(alpha = 0.85f), Offset(0f, 1f), 5f)
private val DeepShadow = Shadow(Color(0xFF082548), Offset(1f, 2f), 5f)

@Composable
internal fun ModeSelectionScreen(
    onSelect: (AppMode) -> Unit,
    onPeopleAndEnjoy: () -> Unit,
) {
    // Fit the whole 390 x 844 design in the available area, including both touch regions
    // and artwork. No child image or individual choice is independently scaled or cropped.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(StageDark).statusBarsPadding().navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        val scale = min(maxWidth.value / 390f, maxHeight.value / 844f)
        Layout(
            content = {
                Box(Modifier.size(390.dp, 844.dp)) {
                    Image(
                        painter = painterResource(R.drawable.enjoy_background),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    EnjoymentChoice(
                        image = R.drawable.enjoy_casual,
                        title = appString(R.string.enjoy_casually),
                        detail = appString(R.string.enjoy_casually_detail),
                        accessibilityLabel = appString(R.string.enjoy_casually_accessibility),
                        x = 90, y = 58, width = 300, height = 240,
                        actionX = 35, actionY = 78,
                        actionWidth = 247, actionHeight = 132,
                        titleColor = Ink, detailColor = Ink, dark = false,
                        onClick = { onSelect(AppMode.STANDARD) },
                    )
                    EnjoymentChoice(
                        image = R.drawable.enjoy_together_art,
                        title = appString(R.string.enjoy_together),
                        detail = appString(R.string.enjoy_together_detail),
                        accessibilityLabel = appString(R.string.enjoy_together_accessibility),
                        x = 112, y = 301, width = 278, height = 223,
                        actionX = 49, actionY = 49,
                        actionWidth = 219, actionHeight = 138,
                        titleColor = Ink, detailColor = Ink, dark = false,
                        onClick = onPeopleAndEnjoy,
                    )
                    EnjoymentChoice(
                        image = R.drawable.enjoy_deep,
                        title = appString(R.string.enjoy_deeply),
                        detail = appString(R.string.enjoy_deeply_detail),
                        accessibilityLabel = appString(R.string.enjoy_deeply_accessibility),
                        x = 115, y = 525, width = 275, height = 220,
                        actionX = 42, actionY = 38,
                        actionWidth = 226, actionHeight = 141,
                        titleColor = Color.White, detailColor = SoftWhite, dark = true,
                        onClick = { onSelect(AppMode.ADVANCED) },
                    )
                    Image(
                        painter = painterResource(R.drawable.enjoy_guide),
                        contentDescription = null,
                        modifier = Modifier.offset(x = (-13).dp, y = 278.dp).size(190.dp, 253.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            },
            modifier = Modifier.size(390.dp * scale, 844.dp * scale),
        ) { measurables, constraints ->
            val stage = measurables.single().measure(
                androidx.compose.ui.unit.Constraints.fixed(390.dp.roundToPx(), 844.dp.roundToPx())
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                stage.placeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

@Composable
private fun EnjoymentChoice(
    image: Int,
    title: String,
    detail: String,
    accessibilityLabel: String,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    actionX: Int,
    actionY: Int,
    actionWidth: Int,
    actionHeight: Int,
    titleColor: Color,
    detailColor: Color,
    dark: Boolean,
    onClick: () -> Unit,
) {
    Box(Modifier.offset(x.dp, y.dp).size(width.dp, height.dp)) {
        Image(
            painter = painterResource(image),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Box(
            Modifier.offset(actionX.dp, actionY.dp)
                .size(actionWidth.dp, actionHeight.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityLabel
                }
                .clickable(role = Role.Button, onClickLabel = title, onClick = onClick),
        ) {
            Column(Modifier.offset(x = 8.dp, y = 5.dp).width((actionWidth - 50).dp)) {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = 29.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.sp,
                    style = TextStyle(shadow = if (dark) DeepShadow else TitleShadow),
                    maxLines = 1,
                )
                Text(
                    text = detail,
                    color = detailColor,
                    modifier = Modifier.offset(y = 9.dp),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = if (dark) DeepShadow else TitleShadow),
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = 5.dp)
                    .size(38.dp)
                    .shadow(3.dp, CircleShape)
                    .background(if (dark) Color(0xFF163863) else Color(0xFF153C3A), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("›", color = Color.White, fontSize = 33.sp, lineHeight = 37.sp)
            }
        }
    }
}

@Preview(name = "360dp", widthDp = 360, heightDp = 760)
@Composable
private fun ModeSelectionPreview360() {
    OthelloTheme { ModeSelectionScreen({}, {}) }
}

@Preview(name = "390dp", widthDp = 390, heightDp = 844)
@Composable
private fun ModeSelectionPreview390() {
    OthelloTheme { ModeSelectionScreen({}, {}) }
}
