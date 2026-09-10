package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardAiArchitectureContractTest {
    private val standardScreens = File("src/main/kotlin/com/example/othello/StandardAiScreens.kt").readText()
    private val standardProvider = File(
        "../analysis/edax/src/main/kotlin/com/example/othello/analysis/edax/ProductionStandardCandidateProvider.kt",
    ).readText()
    private val standardData = File(
        "../analysis/edax/src/main/kotlin/com/example/othello/analysis/edax/StandardEvaluationDataManager.kt",
    ).readText()
    private val standardApi = File(
        "../analysis/api/src/main/kotlin/com/example/othello/analysis/api/StandardAi.kt",
    ).readText()
    private val advancedEngine = File(
        "../analysis/edax/src/main/kotlin/com/example/othello/analysis/edax/ProductionAnalysisEngine.kt",
    ).readText()
    private val advancedAiEngine = File(
        "../analysis/edax/src/main/kotlin/com/example/othello/analysis/edax/ProductionAiMoveEngine.kt",
    ).readText()

    @Test
    fun standardNeverReadsAdvancedSettingsEvalOrBook() {
        listOf("EdaxDataManager", "EdaxSettingsStore", "AiMoveSettings", "BookSource").forEach {
            assertFalse(it in standardScreens)
        }
        assertTrue("bookPath = null" in standardProvider)
        assertFalse("openingBook" in standardProvider)
        assertFalse("EDAX_PREFERENCES" in standardData)
        assertTrue("analysis/edax/standard/v1" in standardData)
        assertFalse("AiMoveSettings" in standardApi)
        assertFalse("BookSource" in standardApi)
        assertFalse("StandardBestMoveProvider" in standardApi)
        assertTrue("candidateProvider.evaluate" in standardApi)
    }

    @Test
    fun advancedEngineHasNoStandardModeBranches() {
        assertFalse("StandardAi" in advancedEngine)
        assertFalse("standardMode" in advancedEngine)
        assertFalse("StandardAi" in advancedAiEngine)
        assertFalse("standardMode" in advancedAiEngine)
        assertTrue("level = settings.level" in advancedAiEngine)
        assertTrue("evaluationDataPath = evaluationAsset.appPrivatePath" in advancedAiEngine)
        assertTrue("bookPath = bookAsset?.appPrivatePath" in advancedAiEngine)
    }
}
