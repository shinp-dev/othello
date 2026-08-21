package com.example.othello

import android.content.Context

class AudioSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var timeWarningEnabled: Boolean
        get() = preferences.getBoolean(KEY_TIME_WARNING_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_TIME_WARNING_ENABLED, value).apply()

    var focusSoundEnabled: Boolean
        get() = preferences.getBoolean(KEY_FOCUS_SOUND_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_FOCUS_SOUND_ENABLED, value).apply()

    var focusSoundVolume: Float
        get() = preferences.getFloat(KEY_FOCUS_SOUND_VOLUME, DEFAULT_FOCUS_SOUND_VOLUME)
        set(value) = preferences.edit().putFloat(KEY_FOCUS_SOUND_VOLUME, value.coerceIn(0f, 1f)).apply()

    private companion object {
        const val FILE_NAME = "chanriva_audio_settings"
        const val KEY_TIME_WARNING_ENABLED = "time_warning_enabled"
        const val KEY_FOCUS_SOUND_ENABLED = "focus_sound_enabled"
        const val KEY_FOCUS_SOUND_VOLUME = "focus_sound_volume"
        const val DEFAULT_FOCUS_SOUND_VOLUME = 0.18f
    }
}
