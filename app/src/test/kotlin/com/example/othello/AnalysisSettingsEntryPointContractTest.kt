package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AnalysisSettingsEntryPointContractTest {
    private val main = source("MainActivity.kt")
    private val review = source("BetaScreens.kt")
        .substringAfter("internal fun ReviewScreenV2(")
        .substringBefore("if (showMemoDialog)")
    private val positionReview = source("PositionReviewScreens.kt")
        .substringAfter("internal fun PositionReviewScreen(")
        .substringBefore("private fun EditablePositionBoard")
    private val theoryExploration = source("TheoryExplorationScreens.kt")
        .substringAfter("private fun TheoryExplorationContent(")
    private val commonSettings = source("AnalysisScreens.kt")
        .substringAfter("internal fun CommonSettingsScreen(")

    @Test
    fun missingEvaluationDataShowsTheExistingAnalysisSettingsEntryPoint() {
        assertEvaluationSettingsEntryPoint(review, "status")
        assertEvaluationSettingsEntryPoint(positionReview, "dataStatus")
        assertEvaluationSettingsEntryPoint(theoryExploration, "dataStatus")
    }

    @Test
    fun configuredEvaluationDataDoesNotShowAnUnnecessaryEntryPoint() {
        listOf(review, positionReview, theoryExploration).forEach { screen ->
            assertFalse("else R.string.open_analysis_settings" in screen)
            assertFalse("openingBook == null" in screen.substringBefore("R.string.open_analysis_settings"))
        }
    }

    @Test
    fun entryPointsReuseTheExistingSettingsScreenAndReturnToTheirSources() {
        assertTrue("destination == AppDestination.COMMON_SETTINGS -> CommonSettingsScreen(" in main)
        assertTrue("R.string.auto_setup_eval" in commonSettings)
        assertTrue("R.string.choose_eval" in commonSettings)

        listOf(
            "AppDestination.REVIEW",
            "AppDestination.POSITION_REVIEW",
            "AppDestination.THEORY_EXPLORATION",
        ).forEach { sourceDestination ->
            assertTrue("commonSettingsBackDestination = $sourceDestination" in main)
        }
    }

    @Test
    fun entryPointCopyIsLocalizedInEnglishAndJapanese() {
        val english = File("src/main/res/values/strings.xml").readText()
        val japanese = File("src/main/res/values-ja/strings.xml").readText()

        assertTrue("<string name=\"open_analysis_settings\">Open analysis settings</string>" in english)
        assertTrue("<string name=\"open_analysis_settings\">解析設定を開く</string>" in japanese)
    }

    private fun assertEvaluationSettingsEntryPoint(screen: String, statusName: String) {
        val entryPoint = screen.substringAfter("if ($statusName.evaluationData == null) {")
            .substringBefore("}\n")

        assertTrue("onClick = onOpenCommonSettings" in entryPoint)
        assertTrue("R.string.open_analysis_settings" in entryPoint)
    }

    private fun source(name: String): String =
        File("src/main/kotlin/com/example/othello/$name").readText().replace("\r\n", "\n")
}
