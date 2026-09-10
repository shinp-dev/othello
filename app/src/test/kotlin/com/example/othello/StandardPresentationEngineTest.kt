package com.example.othello

import com.example.othello.analysis.api.StandardTensionLevel
import org.junit.Test
import kotlin.test.assertEquals

class StandardPresentationEngineTest {
    @Test
    fun tensionTransitionsDriveBoardStateAndHeartbeatCues() {
        val sound = RecordingSoundOutput()
        val haptic = RecordingHapticOutput()
        val engine = StandardPresentationEngine(sound, haptic)

        engine.accept(StandardPresentationEvent.DecisionReady(StandardTensionLevel.TENSE))
        assertEquals(StandardTensionLevel.TENSE, engine.state.value.tension)
        assertEquals(listOf(StandardSoundCue.HEARTBEAT_TENSE_START), sound.cues)

        engine.accept(StandardPresentationEvent.DecisionReady(StandardTensionLevel.CRITICAL))
        assertEquals(StandardTensionLevel.CRITICAL, engine.state.value.tension)
        assertEquals(
            listOf(
                StandardSoundCue.HEARTBEAT_TENSE_START,
                StandardSoundCue.HEARTBEAT_STOP,
                StandardSoundCue.HEARTBEAT_CRITICAL_START,
            ),
            sound.cues,
        )
        assertEquals(listOf(StandardHapticCue.TENSION_PEAK), haptic.cues)
    }

    @Test
    fun moveStopsHeartbeatBeforeStoneCueAndReturnsBoardToCalm() {
        val sound = RecordingSoundOutput()
        val haptic = RecordingHapticOutput()
        val engine = StandardPresentationEngine(sound, haptic)
        engine.accept(StandardPresentationEvent.DecisionReady(StandardTensionLevel.CRITICAL))
        sound.cues.clear()
        haptic.cues.clear()

        engine.accept(StandardPresentationEvent.MovePlaced)

        assertEquals(StandardTensionLevel.CALM, engine.state.value.tension)
        assertEquals(
            listOf(StandardSoundCue.HEARTBEAT_STOP, StandardSoundCue.STONE_PLACED),
            sound.cues,
        )
        assertEquals(listOf(StandardHapticCue.MOVE), haptic.cues)
    }

    @Test
    fun firstClearAndConquestTakePriorityOverOrdinaryResultSound() {
        val firstClearSound = RecordingSoundOutput()
        val firstClearEngine = StandardPresentationEngine(firstClearSound, RecordingHapticOutput())
        firstClearEngine.accept(
            StandardPresentationEvent.MatchFinished(
                outcome = StandardAiHumanOutcome.WIN,
                firstClear = true,
                conquered = false,
            ),
        )
        assertEquals(listOf(StandardSoundCue.LEVEL_CLEAR), firstClearSound.cues)

        val conquestSound = RecordingSoundOutput()
        val conquestEngine = StandardPresentationEngine(conquestSound, RecordingHapticOutput())
        conquestEngine.accept(
            StandardPresentationEvent.MatchFinished(
                outcome = StandardAiHumanOutcome.WIN,
                firstClear = true,
                conquered = true,
            ),
        )
        assertEquals(listOf(StandardSoundCue.CAMPAIGN_CONQUERED), conquestSound.cues)
    }

    @Test
    fun boardEffectStrengthTracksTensionWithoutLeakingRenderingIntoEngine() {
        val engine = StandardPresentationEngine()
        val calm = engine.state.value.boardEffect
        engine.accept(StandardPresentationEvent.DecisionReady(StandardTensionLevel.TENSE))
        val tense = engine.state.value.boardEffect
        engine.accept(StandardPresentationEvent.DecisionReady(StandardTensionLevel.CRITICAL))
        val critical = engine.state.value.boardEffect

        assertEquals(0f, calm.pulseAmplitude)
        assert(tense.pulseAmplitude > calm.pulseAmplitude)
        assert(critical.pulseAmplitude > tense.pulseAmplitude)
        assert(critical.alpha < tense.alpha)
    }

    private class RecordingSoundOutput : StandardSoundOutput {
        val cues = mutableListOf<StandardSoundCue>()
        override fun emit(cue: StandardSoundCue) {
            cues += cue
        }
    }

    private class RecordingHapticOutput : StandardHapticOutput {
        val cues = mutableListOf<StandardHapticCue>()
        override fun emit(cue: StandardHapticCue) {
            cues += cue
        }
    }
}
