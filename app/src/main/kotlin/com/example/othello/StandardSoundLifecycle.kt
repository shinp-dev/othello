package com.example.othello

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.othello.analysis.api.StandardAiLevel

/**
 * Creates the real Standard presentation engine and owns the Android sound adapter lifecycle.
 * Keeping this in a Standard-only composable prevents Advanced screens from acquiring audio side effects.
 */
@Composable
internal fun rememberStandardPresentationEngine(level: StandardAiLevel): StandardPresentationEngine {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val soundOutput = remember(context) { AndroidStandardSoundOutput(context.applicationContext) }
    val presentationEngine = remember(level, soundOutput) {
        StandardPresentationEngine(soundOutput = soundOutput)
    }

    DisposableEffect(lifecycleOwner, soundOutput) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> soundOutput.onHostStart()
                Lifecycle.Event.ON_STOP -> soundOutput.onHostStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            soundOutput.onHostStart()
        } else {
            soundOutput.onHostStop()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            soundOutput.close()
        }
    }

    return presentationEngine
}
