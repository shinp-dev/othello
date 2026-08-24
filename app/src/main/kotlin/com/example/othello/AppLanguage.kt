package com.example.othello

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

internal enum class AppLanguage(val tag: String) {
    SYSTEM_DEFAULT(""),
    JAPANESE("ja"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = when (
            tag?.substringBefore(',')?.substringBefore('-')?.substringBefore('_')?.lowercase()
        ) {
            "ja" -> JAPANESE
            "en" -> ENGLISH
            else -> SYSTEM_DEFAULT
        }
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
