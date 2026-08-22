package com.example.othello.analysis.edax

import android.content.Context
import android.content.SharedPreferences

data class AiMatchSettings(
    val level: Int = DEFAULT_LEVEL,
    val moveTimeMs: Int = DEFAULT_MOVE_TIME_MS,
) {
    init {
        require(level in MIN_LEVEL..MAX_LEVEL) { "AI match level must be in 1..8" }
        require(moveTimeMs.isSupportedAnalysisTime()) { "AI match move time is unsupported" }
    }

    companion object {
        const val DEFAULT_LEVEL = 1
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 8
        const val DEFAULT_MOVE_TIME_MS = 2_000
    }
}

data class ReviewAnalysisSettings(
    val level: Int = DEFAULT_LEVEL,
    val timePerCandidateMs: Int = DEFAULT_TIME_PER_CANDIDATE_MS,
    val highLoadAnalysisEnabled: Boolean = false,
) {
    init {
        val maximumLevel = if (highLoadAnalysisEnabled) HIGH_LOAD_MAX_LEVEL else NORMAL_MAX_LEVEL
        require(level in MIN_LEVEL..maximumLevel) { "review level is inconsistent with high-load setting" }
        require(timePerCandidateMs.isSupportedAnalysisTime()) { "review analysis time is unsupported" }
    }

    companion object {
        const val DEFAULT_LEVEL = 6
        const val MIN_LEVEL = 1
        const val NORMAL_MAX_LEVEL = 8
        const val HIGH_LOAD_MAX_LEVEL = 18
        const val DEFAULT_TIME_PER_CANDIDATE_MS = 2_000
    }
}

/** Persists only use-case settings. eval.dat and opening-book ownership stays in [EdaxDataManager]. */
class EdaxSettingsStore internal constructor(
    private val preferences: EdaxSettingPreferences,
) {
    constructor(context: Context) : this(
        SharedPreferencesEdaxSettings(
            context.applicationContext.getSharedPreferences(EDAX_PREFERENCES, Context.MODE_PRIVATE),
        ),
    )

    fun aiMatchSettings(): AiMatchSettings {
        val level = preferences.getInt(KEY_AI_MATCH_LEVEL, AiMatchSettings.DEFAULT_LEVEL)
            .coerceIn(AiMatchSettings.MIN_LEVEL, AiMatchSettings.MAX_LEVEL)
        val moveTimeMs = preferences.getInt(KEY_AI_MATCH_MOVE_TIME_MS, AiMatchSettings.DEFAULT_MOVE_TIME_MS)
            .sanitizedAnalysisTime(AiMatchSettings.DEFAULT_MOVE_TIME_MS)
        preferences.correctInt(KEY_AI_MATCH_LEVEL, level)
        preferences.correctInt(KEY_AI_MATCH_MOVE_TIME_MS, moveTimeMs)
        return AiMatchSettings(level, moveTimeMs)
    }

    fun reviewAnalysisSettings(): ReviewAnalysisSettings {
        val highLoadEnabled = preferences.getBoolean(KEY_HIGH_LOAD_ANALYSIS_ENABLED, false)
        val maximumLevel = if (highLoadEnabled) {
            ReviewAnalysisSettings.HIGH_LOAD_MAX_LEVEL
        } else {
            ReviewAnalysisSettings.NORMAL_MAX_LEVEL
        }
        val level = preferences.getInt(KEY_REVIEW_ANALYSIS_LEVEL, ReviewAnalysisSettings.DEFAULT_LEVEL)
            .coerceIn(ReviewAnalysisSettings.MIN_LEVEL, maximumLevel)
        val timePerCandidateMs = preferences.getInt(
            KEY_REVIEW_ANALYSIS_TIME_PER_CANDIDATE_MS,
            ReviewAnalysisSettings.DEFAULT_TIME_PER_CANDIDATE_MS,
        ).sanitizedAnalysisTime(ReviewAnalysisSettings.DEFAULT_TIME_PER_CANDIDATE_MS)
        preferences.correctInt(KEY_REVIEW_ANALYSIS_LEVEL, level)
        preferences.correctInt(KEY_REVIEW_ANALYSIS_TIME_PER_CANDIDATE_MS, timePerCandidateMs)
        return ReviewAnalysisSettings(level, timePerCandidateMs, highLoadEnabled)
    }

    fun setAiMatchLevel(level: Int) {
        require(level in AiMatchSettings.MIN_LEVEL..AiMatchSettings.MAX_LEVEL)
        preferences.putInt(KEY_AI_MATCH_LEVEL, level)
    }

    fun setAiMatchMoveTimeMs(moveTimeMs: Int) {
        require(moveTimeMs.isSupportedAnalysisTime())
        preferences.putInt(KEY_AI_MATCH_MOVE_TIME_MS, moveTimeMs)
    }

    fun setReviewAnalysisLevel(level: Int) {
        val maximumLevel = if (preferences.getBoolean(KEY_HIGH_LOAD_ANALYSIS_ENABLED, false)) {
            ReviewAnalysisSettings.HIGH_LOAD_MAX_LEVEL
        } else {
            ReviewAnalysisSettings.NORMAL_MAX_LEVEL
        }
        require(level in ReviewAnalysisSettings.MIN_LEVEL..maximumLevel)
        preferences.putInt(KEY_REVIEW_ANALYSIS_LEVEL, level)
    }

    fun setReviewAnalysisTimePerCandidateMs(timePerCandidateMs: Int) {
        require(timePerCandidateMs.isSupportedAnalysisTime())
        preferences.putInt(KEY_REVIEW_ANALYSIS_TIME_PER_CANDIDATE_MS, timePerCandidateMs)
    }

    fun setHighLoadAnalysisEnabled(enabled: Boolean) {
        preferences.putBoolean(KEY_HIGH_LOAD_ANALYSIS_ENABLED, enabled)
        if (!enabled) {
            val safeLevel = preferences.getInt(KEY_REVIEW_ANALYSIS_LEVEL, ReviewAnalysisSettings.DEFAULT_LEVEL)
                .coerceIn(ReviewAnalysisSettings.MIN_LEVEL, ReviewAnalysisSettings.NORMAL_MAX_LEVEL)
            preferences.putInt(KEY_REVIEW_ANALYSIS_LEVEL, safeLevel)
        }
    }

    companion object {
        const val MIN_ANALYSIS_TIME_MS = 500
        const val MAX_ANALYSIS_TIME_MS = 10_000
        const val ANALYSIS_TIME_STEP_MS = 500

        internal const val KEY_AI_MATCH_LEVEL = "ai_match_level"
        internal const val KEY_AI_MATCH_MOVE_TIME_MS = "ai_match_move_time_ms"
        internal const val KEY_REVIEW_ANALYSIS_LEVEL = "review_analysis_level"
        internal const val KEY_REVIEW_ANALYSIS_TIME_PER_CANDIDATE_MS = "review_analysis_time_per_candidate_ms"
        internal const val KEY_HIGH_LOAD_ANALYSIS_ENABLED = "high_load_analysis_enabled"
    }
}

internal interface EdaxSettingPreferences {
    fun getInt(key: String, defaultValue: Int): Int
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
}

private class SharedPreferencesEdaxSettings(
    private val preferences: SharedPreferences,
) : EdaxSettingPreferences {
    override fun getInt(key: String, defaultValue: Int): Int = preferences.getInt(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = preferences.getBoolean(key, defaultValue)
    override fun putInt(key: String, value: Int) { preferences.edit().putInt(key, value).apply() }
    override fun putBoolean(key: String, value: Boolean) { preferences.edit().putBoolean(key, value).apply() }
}

private fun EdaxSettingPreferences.correctInt(key: String, correctedValue: Int) {
    if (getInt(key, correctedValue) != correctedValue) putInt(key, correctedValue)
}

private fun Int.isSupportedAnalysisTime(): Boolean =
    this in EdaxSettingsStore.MIN_ANALYSIS_TIME_MS..EdaxSettingsStore.MAX_ANALYSIS_TIME_MS &&
        (this - EdaxSettingsStore.MIN_ANALYSIS_TIME_MS) % EdaxSettingsStore.ANALYSIS_TIME_STEP_MS == 0

private fun Int.sanitizedAnalysisTime(defaultValue: Int): Int =
    takeIf { it.isSupportedAnalysisTime() } ?: defaultValue
