package com.example.othello

import kotlin.test.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun languageTagsMapToTheSupportedChoices() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag("fr"))
    }

    @Test
    fun explicitChoicesUseOnlyTheDeclaredLocaleTags() {
        assertEquals("", AppLanguage.SYSTEM_DEFAULT.tag)
        assertEquals("ja", AppLanguage.JAPANESE.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
    }
}
