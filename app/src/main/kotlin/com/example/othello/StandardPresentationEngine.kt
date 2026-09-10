package com.example.othello

import com.example.othello.analysis.api.StandardTensionLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-shot audio requests. Actual audio assets/player are intentionally a separate layer. */
internal enum class StandardSoundCue {
    HEARTBEAT_TENSE_START,
    HEARTBEAT_CRITICAL_START,
    HEARTBEAT_STOP,
    STONE_PLACED,
    OPPONENT_APPEARED,
    HUMAN_WIN,
    HUMAN_LOSS,
    DRAW,
    LEVEL_CLEAR,
    CAMPAIGN_CONQUERED,
}

/** One-shot haptic requests. A future Android adapter can honor user/accessibility settings. */
internal enum class StandardHapticCue {
    MOVE,
    TENSION_PEAK,
    RESULT,
    LEVEL_CLEAR,
}

internal data class StandardBoardEffect(
    val alpha: Float,
    val pulseAmplitude: Float,
    val pulsePeriodMs: Int,
)

internal data class StandardPresentationState(
    val tension: StandardTensionLevel = StandardTensionLevel.CALM,
) {
    val boardEffect: StandardBoardEffect
        get() = when (tension) {
            StandardTensionLevel.CALM -> StandardBoardEffect(
                alpha = 1f,
                pulseAmplitude = 0f,
                pulsePeriodMs = 1_000,
            )
            StandardTensionLevel.TENSE -> StandardBoardEffect(
                alpha = 0.97f,
                pulseAmplitude = 0.003f,
                pulsePeriodMs = 1_100,
            )
            StandardTensionLevel.CRITICAL -> StandardBoardEffect(
                alpha = 0.93f,
                pulseAmplitude = 0.006f,
                pulsePeriodMs = 700,
            )
        }
}

internal sealed interface StandardPresentationEvent {
    data class DecisionReady(val tension: StandardTensionLevel) : StandardPresentationEvent
    data object MovePlaced : StandardPresentationEvent
    data object OpponentAppeared : StandardPresentationEvent
    data class MatchFinished(
        val outcome: StandardAiHumanOutcome,
        val firstClear: Boolean,
        val conquered: Boolean,
    ) : StandardPresentationEvent
    data object Reset : StandardPresentationEvent
}

internal fun interface StandardSoundOutput {
    fun emit(cue: StandardSoundCue)
}

internal fun interface StandardHapticOutput {
    fun emit(cue: StandardHapticCue)
}

private val NoOpSoundOutput = StandardSoundOutput { }
private val NoOpHapticOutput = StandardHapticOutput { }

/**
 * Standard-only presentation reducer.
 *
 * AI/game code publishes semantic events here. The engine owns persistent presentation state
 * (currently board tension) and emits one-shot sound/haptic cues through replaceable ports.
 * No Android media, Compose, or Advanced-mode dependency is allowed in this class.
 */
internal class StandardPresentationEngine(
    private val soundOutput: StandardSoundOutput = NoOpSoundOutput,
    private val hapticOutput: StandardHapticOutput = NoOpHapticOutput,
) {
    private val mutableState = MutableStateFlow(StandardPresentationState())
    val state: StateFlow<StandardPresentationState> = mutableState.asStateFlow()

    fun accept(event: StandardPresentationEvent) {
        when (event) {
            is StandardPresentationEvent.DecisionReady -> applyTension(event.tension)
            StandardPresentationEvent.MovePlaced -> onMovePlaced()
            StandardPresentationEvent.OpponentAppeared -> soundOutput.emit(StandardSoundCue.OPPONENT_APPEARED)
            is StandardPresentationEvent.MatchFinished -> onMatchFinished(event)
            StandardPresentationEvent.Reset -> reset()
        }
    }

    private fun applyTension(next: StandardTensionLevel) {
        val previous = mutableState.value.tension
        if (previous == next) return

        stopHeartbeatIfNeeded(previous)
        mutableState.value = StandardPresentationState(next)
        when (next) {
            StandardTensionLevel.CALM -> Unit
            StandardTensionLevel.TENSE -> soundOutput.emit(StandardSoundCue.HEARTBEAT_TENSE_START)
            StandardTensionLevel.CRITICAL -> {
                soundOutput.emit(StandardSoundCue.HEARTBEAT_CRITICAL_START)
                hapticOutput.emit(StandardHapticCue.TENSION_PEAK)
            }
        }
    }

    private fun onMovePlaced() {
        stopHeartbeatIfNeeded(mutableState.value.tension)
        mutableState.value = StandardPresentationState()
        soundOutput.emit(StandardSoundCue.STONE_PLACED)
        hapticOutput.emit(StandardHapticCue.MOVE)
    }

    private fun onMatchFinished(event: StandardPresentationEvent.MatchFinished) {
        stopHeartbeatIfNeeded(mutableState.value.tension)
        mutableState.value = StandardPresentationState()

        val cue = when {
            event.conquered -> StandardSoundCue.CAMPAIGN_CONQUERED
            event.firstClear -> StandardSoundCue.LEVEL_CLEAR
            event.outcome == StandardAiHumanOutcome.WIN -> StandardSoundCue.HUMAN_WIN
            event.outcome == StandardAiHumanOutcome.LOSS -> StandardSoundCue.HUMAN_LOSS
            else -> StandardSoundCue.DRAW
        }
        soundOutput.emit(cue)
        hapticOutput.emit(
            if (event.firstClear || event.conquered) StandardHapticCue.LEVEL_CLEAR else StandardHapticCue.RESULT,
        )
    }

    private fun reset() {
        stopHeartbeatIfNeeded(mutableState.value.tension)
        mutableState.value = StandardPresentationState()
    }

    private fun stopHeartbeatIfNeeded(level: StandardTensionLevel) {
        if (level != StandardTensionLevel.CALM) soundOutput.emit(StandardSoundCue.HEARTBEAT_STOP)
    }
}
