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
        assertTrue("<string name=\"local_match_to_move\">%1\$s to move</string>" in defaultText)
        assertTrue("<string name=\"local_match_to_move\">%1\$sの手番</string>" in japaneseText)
        assertTrue("<string name=\"local_match_resigned\">%1\$s resigned</string>" in defaultText)
        assertTrue("<string name=\"local_match_resigned\">%1\$sが投了しました</string>" in japaneseText)
        assertTrue("<string name=\"email_format_invalid\">Enter a valid email address.</string>" in defaultText)
        assertTrue("<string name=\"email_format_invalid\">メールアドレスの形式を確認してください。</string>" in japaneseText)
        assertTrue("<string name=\"show_password\">Show password</string>" in defaultText)
        assertTrue("<string name=\"show_password\">パスワードを表示</string>" in japaneseText)
        assertTrue("<string name=\"hide_password\">Hide password</string>" in defaultText)
        assertTrue("<string name=\"hide_password\">パスワードを非表示</string>" in japaneseText)
        assertTrue("<string name=\"saving_variation\">Saving…</string>" in defaultText)
        assertTrue("<string name=\"saving_variation\">保存中…</string>" in japaneseText)
        assertTrue("<string name=\"variation_saved\">Saved</string>" in defaultText)
        assertTrue("<string name=\"variation_saved\">保存済み</string>" in japaneseText)
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
        assertEquals(441, english.size)
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
