package com.example.othello

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

internal enum class AppLanguage(val tag: String) {
    SYSTEM_DEFAULT(""),
    JAPANESE("ja"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = when (tag) {
            "ja" -> JAPANESE
            "en" -> ENGLISH
            else -> SYSTEM_DEFAULT
        }
    }
}

internal class AppLanguageStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun selected(): AppLanguage = AppLanguage.fromTag(preferences.getString(KEY_LANGUAGE, null))

    fun save(language: AppLanguage) {
        preferences.edit().putString(KEY_LANGUAGE, language.tag).apply()
    }

    companion object {
        private const val FILE_NAME = "chanriva_app_language"
        private const val KEY_LANGUAGE = "language"
    }
}

internal fun applyAppLanguage(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(
        if (language == AppLanguage.SYSTEM_DEFAULT) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        },
    )
}
