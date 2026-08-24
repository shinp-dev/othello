package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun languageTagsMapToTheSupportedChoices() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja-JP"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag("fr-FR"))
    }

    @Test
    fun explicitChoicesUseOnlyTheDeclaredLocaleTags() {
        assertEquals("", AppLanguage.SYSTEM_DEFAULT.tag)
        assertEquals("ja", AppLanguage.JAPANESE.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
    }

    @Test
    fun appCompatOwnsLocalePersistence() {
        val languageSource = File("src/main/kotlin/com/example/othello/AppLanguage.kt").readText()
        val activitySource = File("src/main/kotlin/com/example/othello/MainActivity.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertFalse("SharedPreferences" in languageSource)
        assertFalse("AppLanguageStore" in languageSource)
        assertFalse("AppLanguageStore" in activitySource)
        assertTrue("androidx.appcompat.app.AppLocalesMetadataHolderService" in manifest)
        assertTrue("android:name=\"autoStoreLocales\"" in manifest)
        assertTrue("android:value=\"true\"" in manifest)
    }
}
