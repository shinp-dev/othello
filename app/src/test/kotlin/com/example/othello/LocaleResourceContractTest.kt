package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LocaleResourceContractTest {
    private val defaultFile = File("src/main/res/values/strings.xml")
    private val japaneseFile = File("src/main/res/values-ja/strings.xml")
    private val legacyEnglishDirectory = File("src/main/res/values-en")
    private val localeConfig = File("src/main/res/xml/locales_config.xml")

    @Test
    fun defaultResourcesAreEnglishAndJapaneseUsesValuesJa() {
        val defaultText = defaultFile.readText()
        val japaneseText = japaneseFile.readText()

        assertTrue("<string name=\"language_setting\">Language</string>" in defaultText)
        assertTrue("<string name=\"language_setting\">言語</string>" in japaneseText)
        assertFalse(legacyEnglishDirectory.exists())
    }

    @Test
    fun supportedLocalesAreExactlyJapaneseAndEnglish() {
        val localeNames = Regex("""<locale android:name="([^"]+)"\s*/>""")
            .findAll(localeConfig.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(setOf("ja", "en"), localeNames)
    }

    @Test
    fun japaneseAndEnglishResourcesHaveMatchingIdsAndFormatArguments() {
        val english = readStrings(defaultFile)
        val japanese = readStrings(japaneseFile)

        assertEquals(english.keys, japanese.keys)
        assertEquals(435, english.size)
        assertEquals("Analyze Any Position", english["position_review"])
        assertEquals("任意盤面から検討", japanese["position_review"])
        assertEquals("Position Analysis", english["study_position_analysis"])
        assertEquals("局面の分析", japanese["study_position_analysis"])
        assertEquals("Theory and Directional Analysis", english["study_theory_analysis"])
        assertEquals("理論・指向性の分析", japanese["study_theory_analysis"])
        assertEquals("Game Record Analysis", english["study_record_analysis"])
        assertEquals("棋譜の分析", japanese["study_record_analysis"])
        assertEquals("For enthusiasts", english["theory_enthusiast_recommended"])
        assertEquals("ガチ勢推奨", japanese["theory_enthusiast_recommended"])
        english.forEach { (id, value) ->
            assertEquals(formatArguments(value), formatArguments(japanese.getValue(id)), id)
        }
    }

    private fun readStrings(file: File): Map<String, String> =
        Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun formatArguments(value: String): List<String> =
        Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.sorted().toList()
}
