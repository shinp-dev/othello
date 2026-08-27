package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AiMatchArchitectureContractTest {
    private val aiController = File("src/main/kotlin/com/example/othello/LocalAiTurnController.kt").readText()
    private val mainActivity = File("src/main/kotlin/com/example/othello/MainActivity.kt").readText()
    private val reviewScreens = File("src/main/kotlin/com/example/othello/BetaScreens.kt").readText()

    @Test
    fun aiMatchUsesOnlyDedicatedMoveApiAndAiSettings() {
        assertTrue("AiMoveEngine" in aiController)
        assertTrue("engine.chooseBestMove" in aiController)
        assertFalse("AnalysisEngine" in aiController)
        assertFalse("engine.analyze" in aiController)

        val localMatchBody = mainActivity.substringAfter("private fun LocalMatchScreen(")
            .substringBefore("private fun LocalAiSetupScreen(")
        assertTrue("settingsStore.aiMatchSettings()" in localMatchBody)
        assertTrue("dataManager.aiMatchConfiguration" in localMatchBody)
        assertTrue("aiConfiguration).moveSettings" in localMatchBody)
        assertFalse("reviewAnalysisSettings" in localMatchBody)
        assertFalse("analysisSettings(" in localMatchBody)
    }

    @Test
    fun undoIsConfinedToLocalMatchScreen() {
        val onlineMatchBody = mainActivity.substringAfter("private fun OnlineMatchScreen(")
            .substringBefore("private fun PlayScreen(")
        val localMatchBody = mainActivity.substringAfter("private fun LocalMatchScreen(")
            .substringBefore("private fun LocalAiSetupScreen(")

        assertFalse("undoMove" in onlineMatchBody)
        assertFalse("undo_move" in onlineMatchBody)
        assertTrue("undoMove" in localMatchBody)
        assertTrue("undo_move" in localMatchBody)
    }

    @Test
    fun reviewStillUsesItsAllCandidateAnalysisContract() {
        assertTrue("review.analyze(engine, settings)" in reviewScreens)
        assertTrue("dataManager.analysisSettings(reviewSettings)" in reviewScreens)
        assertTrue("reviewSettings.timePerCandidateMs" in reviewScreens)
        assertFalse("aiMatchSettings" in reviewScreens)
        assertFalse("chooseBestMove" in reviewScreens)
    }
}
