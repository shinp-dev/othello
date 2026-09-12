package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardGachaEffectsUiContractTest {
    private val effectsSource =
        File("src/main/kotlin/com/example/othello/AndroidStandardGachaEffects.kt").readText()
    private val screenSource =
        File("src/main/kotlin/com/example/othello/StandardGachaScreen.kt").readText()

    @Test
    fun capsuleBurstSynchronizesSystemHapticsAndRarityFanfare() {
        val burstBlock = screenSource.substringAfter("revealPhase = STANDARD_GACHA_PHASE_BURST")

        assertTrue("gachaEffects.reveal(entry.card.rarity, hostView)" in burstBlock)
        assertTrue("delay(STANDARD_GACHA_BURST_MILLIS)" in burstBlock)
        assertTrue(
            burstBlock.indexOf("gachaEffects.reveal") <
                burstBlock.indexOf("delay(STANDARD_GACHA_BURST_MILLIS)"),
        )
        assertTrue("view.performHapticFeedback(" in effectsSource)
        assertTrue("HapticFeedbackConstants.CONFIRM" in effectsSource)
        assertTrue("HapticFeedbackConstants.CONTEXT_CLICK" in effectsSource)
    }

    @Test
    fun fanfaresAreShortPreloadedSoundPoolAssetsWithLifecycleCleanup() {
        assertTrue("SoundPool.Builder()" in effectsSource)
        assertTrue(".setMaxStreams(1)" in effectsSource)
        assertTrue("AudioAttributes.USAGE_GAME" in effectsSource)
        assertTrue("R.raw.standard_gacha_common" in effectsSource)
        assertTrue("R.raw.standard_gacha_rare" in effectsSource)
        assertTrue("R.raw.standard_gacha_special" in effectsSource)
        assertTrue("StandardContentRarity.COMMON -> Fanfare.COMMON" in effectsSource)
        assertTrue("StandardContentRarity.RARE -> Fanfare.RARE" in effectsSource)
        assertTrue("StandardContentRarity.SPECIAL -> Fanfare.SPECIAL" in effectsSource)
        assertTrue("Lifecycle.Event.ON_STOP -> effects.onHostStop()" in effectsSource)
        assertTrue("effects.close()" in effectsSource)
    }

    @Test
    fun generatedOggAssetsAndCommercialProvenanceArePresent() {
        val stems = listOf(
            "standard_gacha_common",
            "standard_gacha_rare",
            "standard_gacha_special",
        )
        stems.forEach { stem ->
            val asset = File("src/main/res/raw/$stem.ogg")
            assertTrue(asset.isFile, "$stem must be bundled")
            assertTrue(asset.length() in 2_000L..50_000L, "$stem should stay suitable for SoundPool")
            assertTrue(asset.readBytes().take(4).toByteArray().contentEquals("OggS".toByteArray()))
        }

        val generator = File("../tools/generate_standard_audio.py").readText()
        val provenance = File("../THIRD_PARTY_AUDIO.md").readText()
        stems.forEach { stem ->
            assertTrue("\"$stem\"" in generator)
            assertTrue("`$stem.ogg`" in provenance)
        }
        assertTrue("do **not** contain third-party samples" in provenance)
        assertTrue("Creative Commons Zero (CC0) 1.0 Universal" in provenance)
    }

    @Test
    fun viewHapticsDoNotAddBroadVibrationPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("android.permission.VIBRATE" in manifest)
    }
}
