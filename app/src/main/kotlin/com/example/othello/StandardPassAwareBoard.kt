package com.example.othello

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.othello.game.GameStatus
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchViewState
import kotlinx.coroutines.delay

private const val STANDARD_PASS_OVERLAY_MILLIS = 900L

@Composable
internal fun StandardPassAwareBoard(
    viewState: LocalMatchViewState,
    controller: LocalMatchController,
) {
    var observedMoveCount by remember(controller) { mutableIntStateOf(viewState.moves.size) }
    var passGeneration by remember(controller) { mutableIntStateOf(0) }
    var showPassOverlay by remember(controller) { mutableStateOf(false) }

    LaunchedEffect(viewState.moves.size, viewState.completedRecord?.localId) {
        val currentMoveCount = viewState.moves.size
        val newPass = currentMoveCount > observedMoveCount &&
            viewState.moves.lastOrNull() == null &&
            viewState.completedRecord == null &&
            viewState.game.status is GameStatus.InProgress
        observedMoveCount = currentMoveCount
        if (newPass) passGeneration++
    }
    LaunchedEffect(passGeneration) {
        if (passGeneration == 0) return@LaunchedEffect
        showPassOverlay = true
        delay(STANDARD_PASS_OVERLAY_MILLIS)
        showPassOverlay = false
    }

    Box(contentAlignment = Alignment.Center) {
        LocalOthelloBoard(viewState, controller)
        if (showPassOverlay) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = appString(R.string.standard_ai_pass_overlay),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
