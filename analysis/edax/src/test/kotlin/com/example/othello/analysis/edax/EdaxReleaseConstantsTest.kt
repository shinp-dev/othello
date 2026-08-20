package com.example.othello.analysis.edax

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test

class EdaxReleaseConstantsTest {
    @Test
    fun keepsEngineAndEvaluationDataVersionsIndependentAndPinsOfficialArchive() {
        assertEquals("4.6", EdaxReleaseConstants.ENGINE_VERSION)
        assertEquals("4.4", EdaxReleaseConstants.EVALUATION_DATA_VERSION)
        assertNotEquals(EdaxReleaseConstants.ENGINE_VERSION, EdaxReleaseConstants.EVALUATION_DATA_VERSION)
        assertEquals(
            "https://github.com/abulmo/edax-reversi/releases/download/v4.4/eval.7z",
            EdaxReleaseConstants.OFFICIAL_EVALUATION_ARCHIVE_URL,
        )
    }
}
