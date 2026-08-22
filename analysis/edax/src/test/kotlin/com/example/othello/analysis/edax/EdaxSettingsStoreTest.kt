package com.example.othello.analysis.edax

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class EdaxSettingsStoreTest {
    @Test
    fun defaultsArePurposeSpecific() {
        val store = EdaxSettingsStore(FakeEdaxSettingPreferences())

        assertEquals(AiMatchSettings(level = 1, moveTimeMs = 2_000), store.aiMatchSettings())
        assertEquals(
            ReviewAnalysisSettings(
                level = 6,
                timePerCandidateMs = 2_000,
                highLoadAnalysisEnabled = false,
            ),
            store.reviewAnalysisSettings(),
        )
    }

    @Test
    fun aiMatchAndReviewLevelsAreSavedIndependently() {
        val preferences = FakeEdaxSettingPreferences()
        val store = EdaxSettingsStore(preferences)

        store.setAiMatchLevel(3)
        store.setReviewAnalysisLevel(7)

        val restored = EdaxSettingsStore(preferences)
        assertEquals(3, restored.aiMatchSettings().level)
        assertEquals(7, restored.reviewAnalysisSettings().level)

        restored.setAiMatchLevel(8)
        assertEquals(8, restored.aiMatchSettings().level)
        assertEquals(7, restored.reviewAnalysisSettings().level)
        assertFailsWith<IllegalArgumentException> { restored.setAiMatchLevel(9) }
    }

    @Test
    fun highLoadRangeIsConsistentWhenEnabledAndDisabled() {
        val preferences = FakeEdaxSettingPreferences()
        val store = EdaxSettingsStore(preferences)

        assertFailsWith<IllegalArgumentException> { store.setReviewAnalysisLevel(9) }
        store.setHighLoadAnalysisEnabled(true)
        store.setReviewAnalysisLevel(18)
        assertEquals(18, store.reviewAnalysisSettings().level)
        assertTrue(store.reviewAnalysisSettings().highLoadAnalysisEnabled)

        store.setHighLoadAnalysisEnabled(false)
        assertEquals(8, store.reviewAnalysisSettings().level)
        assertFalse(store.reviewAnalysisSettings().highLoadAnalysisEnabled)
        assertEquals(8, preferences.ints[EdaxSettingsStore.KEY_REVIEW_ANALYSIS_LEVEL])
    }

    @Test
    fun contradictoryPersistedHighLevelIsRepairedOnRead() {
        val preferences = FakeEdaxSettingPreferences().apply {
            ints[EdaxSettingsStore.KEY_REVIEW_ANALYSIS_LEVEL] = 15
            booleans[EdaxSettingsStore.KEY_HIGH_LOAD_ANALYSIS_ENABLED] = false
        }

        val settings = EdaxSettingsStore(preferences).reviewAnalysisSettings()

        assertEquals(8, settings.level)
        assertEquals(8, preferences.ints[EdaxSettingsStore.KEY_REVIEW_ANALYSIS_LEVEL])
    }

    @Test
    fun useCaseSettingsNeverCreatePurposeSpecificEvalOrBookKeys() {
        val preferences = FakeEdaxSettingPreferences()
        val store = EdaxSettingsStore(preferences)

        store.setAiMatchLevel(4)
        store.setAiMatchMoveTimeMs(3_000)
        store.setHighLoadAnalysisEnabled(true)
        store.setReviewAnalysisLevel(12)
        store.setReviewAnalysisTimePerCandidateMs(5_000)

        val keys = preferences.ints.keys + preferences.booleans.keys
        assertTrue(keys.none { "evaluation" in it || "book" in it })
    }
}

private class FakeEdaxSettingPreferences : EdaxSettingPreferences {
    val ints = mutableMapOf<String, Int>()
    val booleans = mutableMapOf<String, Boolean>()

    override fun getInt(key: String, defaultValue: Int): Int = ints[key] ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = booleans[key] ?: defaultValue
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
}
