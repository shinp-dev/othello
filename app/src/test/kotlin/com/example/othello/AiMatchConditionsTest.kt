package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AiMatchConditionsTest {
    private val source = File("src/main/kotlin/com/example/othello/AiMatchConditions.kt").readText()
    private val englishStrings = File("src/main/res/values/strings.xml").readText()
    private val japaneseStrings = File("src/main/res/values-ja/strings.xml").readText()

    @Test
    fun shaIdentityUsesAnEightCharacterPrefix() {
        assertEquals("a31f92c8", shortSha256("a31f92c8deadbeef"))
    }

    @Test
    fun summaryContainsLevelTimeUndoAndCanonicalAssetMetadata() {
        assertTrue("configuration.moveSettings.level" in source)
        assertTrue("configuration.moveSettings.moveTimeMs" in source)
        assertTrue("configuration.evaluationData" in source)
        assertTrue("configuration.openingBook" in source)
        assertTrue("file.fileName" in source)
        assertTrue("file.sizeBytes" in source)
        assertTrue("file.sha256" in source)
        assertTrue("not_configured" in source)
        assertTrue("待ったなし" in japaneseStrings)
        assertTrue("待ったあり" in japaneseStrings)
        assertTrue("No undo" in englishStrings)
        assertTrue("Undo used" in englishStrings)
    }

    @Test
    fun longFileNameIsEllipsizedWithoutDroppingSizeAndShaTail() {
        assertTrue("modifier = Modifier.weight(1f)" in source)
        assertTrue("maxLines = 1" in source)
        assertTrue("overflow = TextOverflow.Ellipsis" in source)
        assertTrue("localizedAnalysisFileSize(file.sizeBytes)" in source)
        assertTrue("shortSha256(file.sha256)" in source)
    }

    @Test
    fun conditionUiDoesNotReadFilesOrRecalculateDigests() {
        assertFalse("java.io.File" in source)
        assertFalse("MessageDigest" in source)
        assertFalse("appPrivatePath" in source)
    }
}
