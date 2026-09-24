package com.example.othello

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.example.othello.designsystem.OthelloTheme

private val Night = Color(0xFF14253A)
private val Ink = Color(0xFF203248)

@Composable
internal fun ModeSelectionScreen(
    onSelect: (AppMode) -> Unit,
    onRealEvent: () -> Unit,
) {
    // The cloud is decorative. Its inset rectangular action contains all text and the arrow.
    Box(Modifier.fillMaxSize().background(Night).statusBarsPadding()) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChoiceCloud(
                title = appString(R.string.enjoy_casually),
                detail = appString(R.string.enjoy_casually_detail),
                palette = listOf(Color(0xFFFFF9DF), Color(0xFFFFE6A0)),
                ink = Ink,
                variant = 0,
                onClick = { onSelect(AppMode.STANDARD) },
            )
            ChoiceCloud(
                title = appString(R.string.enjoy_together),
                detail = appString(R.string.enjoy_together_detail),
                palette = listOf(Color(0xFFE8F3FF), Color(0xFFBBD8F6)),
                ink = Ink,
                variant = 1,
                onClick = onRealEvent,
            )
            ChoiceCloud(
                title = appString(R.string.enjoy_deeply),
                detail = appString(R.string.enjoy_deeply_detail),
                palette = listOf(Color(0xFF32649B), Color(0xFF1D3E71)),
                ink = Color.White,
                variant = 2,
                onClick = { onSelect(AppMode.ADVANCED) },
            )
        }
    }
}

@Composable
private fun ChoiceCloud(
    title: String,
    detail: String,
    palette: List<Color>,
    ink: Color,
    variant: Int,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(if (variant == 0) 240.dp else 236.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val shift = when (variant) { 1 -> 0.02f; 2 -> -0.02f; else -> 0f }
            val cloud = Path().apply {
                moveTo(w * .09f, h * .39f)
                cubicTo(w * .02f, h * .34f, w * .06f, h * .21f, w * .19f, h * .21f)
                cubicTo(w * .19f, h * .09f, w * .37f, h * .07f, w * .43f, h * .16f)
                cubicTo(w * (.52f + shift), h * .02f, w * .65f, h * .08f, w * .71f, h * .17f)
                cubicTo(w * .85f, h * .08f, w * .95f, h * .19f, w * .91f, h * .33f)
                cubicTo(w * 1.02f, h * .39f, w * 1.01f, h * .56f, w * .95f, h * .62f)
                cubicTo(w * 1.01f, h * .77f, w * .89f, h * .88f, w * .78f, h * .83f)
                cubicTo(w * .72f, h * .95f, w * .56f, h * .94f, w * .49f, h * .86f)
                cubicTo(w * .37f, h * .97f, w * .21f, h * .91f, w * .18f, h * .82f)
                cubicTo(w * .04f, h * .83f, w * .01f, h * .70f, w * .08f, h * .62f)
                cubicTo(w * -.01f, h * .55f, w * .01f, h * .43f, w * .09f, h * .39f)
                close()
            }
            drawPath(cloud, Brush.verticalGradient(palette))
            drawPath(cloud, palette.last().copy(alpha = .75f), style = Stroke(width = 1.5.dp.toPx()))
        }
        if (variant == 0) ReversiGuide(Modifier.align(Alignment.CenterStart).offset(y = 12.dp))
        Box(
            Modifier.fillMaxSize()
                .padding(start = if (variant == 0) 116.dp else 50.dp, end = 50.dp, top = 62.dp, bottom = 54.dp)
                .semantics { contentDescription = "$title。$detail" }
                .clickable(role = Role.Button, onClickLabel = title, onClick = onClick),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        color = ink,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Box(
                        Modifier.size(36.dp).background(
                            if (variant == 2) Color(0xFFDBEDFF) else Ink, CircleShape
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("›", color = if (variant == 2) Ink else Color.White, style = MaterialTheme.typography.headlineMedium)
                    }
                }
                Text(
                    detail,
                    color = ink,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                ChoiceMotif(variant, ink)
            }
        }
    }
}

/** A black and white disc guide wearing a tiny green board hat and pointing to the first choice. */
@Composable
private fun ReversiGuide(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 112.dp, height = 160.dp)) {
        val u = size.width / 112f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        drawCircle(Color(0xFF0E1720), 47 * u, p(52f, 94f))
        drawCircle(Color(0xFFF9F6EC), 39 * u, p(56f, 91f))
        drawCircle(Color(0xFF0E1720), 6 * u, p(41f, 87f))
        drawCircle(Color(0xFF0E1720), 6 * u, p(72f, 87f))
        drawCircle(Color.White, 2 * u, p(43f, 85f))
        drawCircle(Color.White, 2 * u, p(74f, 85f))
        drawCircle(Color(0xFFB85856), 5 * u, p(56f, 110f))
        drawLine(Color(0xFF0E1720), p(77f, 115f), p(102f, 55f), strokeWidth = 13 * u)
        drawCircle(Color(0xFFF9F6EC), 9 * u, p(103f, 53f))
        drawLine(Color(0xFF0E1720), p(32f, 128f), p(15f, 144f), strokeWidth = 12 * u)
        drawRoundRect(Color(0xFF174B3D), topLeft = p(17f, 29f), size = Size(75 * u, 28 * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4 * u))
        drawLine(Color(0xFF90BA79), p(35f, 30f), p(35f, 55f), strokeWidth = 2 * u)
        drawLine(Color(0xFF90BA79), p(56f, 30f), p(56f, 55f), strokeWidth = 2 * u)
        drawLine(Color(0xFF90BA79), p(75f, 30f), p(75f, 55f), strokeWidth = 2 * u)
        drawCircle(Color.White, 10 * u, p(44f, 41f))
        drawCircle(Color(0xFF101820), 10 * u, p(68f, 41f))
        drawLine(Color(0xFFE3BE64), p(12f, 58f), p(98f, 58f), strokeWidth = 6 * u)
    }
}

@Composable
private fun ChoiceMotif(variant: Int, ink: Color) {
    Canvas(Modifier.size(width = 72.dp, height = 30.dp)) {
        val u = size.width / 72f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        if (variant == 0) {
            drawRoundRect(Color(0xFF2F7456), p(1f, 1f), Size(67 * u, 28 * u),
                androidx.compose.ui.geometry.CornerRadius(3 * u))
            for (i in 1..3) drawLine(Color.White.copy(alpha = .5f), p(1f + i * 16f, 2f), p(1f + i * 16f, 28f), u)
            drawCircle(Color.White, 8 * u, p(23f, 15f))
            drawCircle(Color(0xFF12191E), 8 * u, p(43f, 15f))
        } else if (variant == 1) {
            drawCircle(Color(0xFF315F97), 8 * u, p(15f, 8f))
            drawCircle(Color(0xFFB65F73), 8 * u, p(55f, 8f))
            drawLine(ink, p(15f, 21f), p(55f, 21f), 2 * u)
            drawCircle(Color(0xFF111B23), 6 * u, p(28f, 21f))
            drawCircle(Color.White, 6 * u, p(43f, 21f))
        } else {
            drawCircle(Color(0xFFB9DBFF), 7 * u, p(10f, 15f))
            drawCircle(Color(0xFFB9DBFF), 7 * u, p(60f, 15f))
            drawLine(Color(0xFFB9DBFF), p(19f, 15f), p(49f, 15f), 2 * u)
            drawCircle(Color(0xFF1A2632), 6 * u, p(29f, 15f))
            drawCircle(Color.White, 6 * u, p(41f, 15f))
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
