package com.example.othello

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Standard-only visual adapter for presentation state.
 * The shared board composable stays untouched so Advanced behavior cannot inherit these effects.
 */
@Composable
internal fun StandardBoardEffectHost(
    state: StandardPresentationState,
    motionEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val effect = state.boardEffect
    val amplitude = if (motionEnabled) effect.pulseAmplitude else 0f
    val transition = rememberInfiniteTransition(label = "standard-board-tension")
    val pulse by transition.animateFloat(
        initialValue = -amplitude,
        targetValue = amplitude,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = effect.pulsePeriodMs,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "standard-board-pulse",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = effect.alpha
                scaleX = 1f + pulse
                scaleY = 1f + pulse
            },
    ) {
        content()
    }
}
