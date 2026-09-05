package com.example.othello

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

class TheoryBoardTextWiringTest {
    private val analysis = File("src/main/kotlin/com/example/othello/AnalysisScreens.kt").readText()
    private val boardUi = File("src/main/kotlin/com/example/othello/BoardUi.kt").readText()
    private val theory = File("src/main/kotlin/com/example/othello/TheoryExplorationScreens.kt").readText()

    @Test
    fun reviewSettingsExposeBoardTextSizeControl() {
        val reviewSettings = analysis.substringAfter("internal fun ReviewSettingsScreen(")
            .substringBefore("private fun AudioPreviewButton")
        assertTrue("TheoryBoardTextSizeSetting()" in reviewSettings)
    }

    @Test
    fun sharedCoordinateBoardAppliesPersistedSizeToReviewEvaluationLabels() {
        assertTrue("TheoryBoardTextSettingsStore(context).textSize" in boardUi)
        assertTrue("lineCount = 1" in boardUi)
        assertTrue("labelSmall = inheritedTypography.labelSmall.copy" in boardUi)
    }

    @Test
    fun theoryBoardUsesPersistedSafeCandidateLabels() {
        val board = theory.substringAfter("private fun TheoryExplorationBoard(")
        assertTrue("TheoryBoardTextSettingsStore(context).textSize" in board)
        assertTrue("TheoryBoardCandidateLabels(" in board)
    }
}
