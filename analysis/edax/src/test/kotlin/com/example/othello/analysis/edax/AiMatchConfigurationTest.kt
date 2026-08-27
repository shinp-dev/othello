package com.example.othello.analysis.edax

import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AiMatchConfigurationTest {
    @Test
    fun configurationUsesOneMetadataSnapshotAndKeepsFullIdentities() {
        val evaluation = importedFile("eval.dat", "a".repeat(64), 18_400_000L)
        val book = importedFile("book.dat", "b".repeat(64), 6_200_000L)
        val status = EdaxCommonDataStatus(true, "4.6", evaluation, book)

        val configuration = status.toAiMatchConfiguration(AiMatchSettings(level = 8, moveTimeMs = 2_000))

        assertEquals(8, configuration.moveSettings.level)
        assertEquals(2_000, configuration.moveSettings.moveTimeMs)
        assertEquals(evaluation, configuration.evaluationData)
        assertEquals(book, configuration.openingBook)
        val evaluationSource = assertIs<EvaluationDataSource.Imported>(configuration.moveSettings.evaluationData)
        val bookSource = assertIs<BookSource.ImportedBook>(configuration.moveSettings.bookSource)
        assertEquals(evaluation.sha256, evaluationSource.asset.identitySha256)
        assertEquals(book.sha256, bookSource.asset.identitySha256)
        assertEquals(evaluation.appPrivatePath, evaluationSource.asset.appPrivatePath)
        assertEquals(book.appPrivatePath, bookSource.asset.appPrivatePath)
    }

    @Test
    fun unsetDataProducesExplicitNoneSourcesAndNullMetadata() {
        val configuration = EdaxCommonDataStatus(true, "4.6", null, null)
            .toAiMatchConfiguration(AiMatchSettings())

        assertEquals(EvaluationDataSource.None, configuration.moveSettings.evaluationData)
        assertEquals(BookSource.None, configuration.moveSettings.bookSource)
        assertEquals(null, configuration.evaluationData)
        assertEquals(null, configuration.openingBook)
    }

    private fun importedFile(name: String, sha256: String, sizeBytes: Long) = ImportedAnalysisFile(
        fileName = name,
        appPrivatePath = "private/$name",
        sizeBytes = sizeBytes,
        sha256 = sha256,
        importedAtEpochMillis = 1L,
    )
}
