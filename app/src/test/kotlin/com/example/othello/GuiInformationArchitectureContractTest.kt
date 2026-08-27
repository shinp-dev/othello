package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class GuiInformationArchitectureContractTest {
    private val main = File("src/main/kotlin/com/example/othello/MainActivity.kt").readText()
    private val board = File("src/main/kotlin/com/example/othello/BoardUi.kt").readText()
    private val analysis = File("src/main/kotlin/com/example/othello/AnalysisScreens.kt").readText()
    private val topLevel = File("src/main/kotlin/com/example/othello/TopLevelScreens.kt").readText()
    private val research = File("src/main/kotlin/com/example/othello/ResearchSettingsScreen.kt").readText()
    private val positionReview = File("src/main/kotlin/com/example/othello/PositionReviewScreens.kt").readText()
    private val theoryExploration = File("src/main/kotlin/com/example/othello/TheoryExplorationScreens.kt").readText()

    @Test
    fun playAndScoreHeaderKeepModeOutOfTheFourthColumn() {
        val play = main.substringAfter("private fun PlayScreen(").substringBefore("internal fun opponentRatingLabel")
        val score = main.substringAfter("private fun ScoreHeader(game:")
            .substringBefore("private fun OthelloBoard")
        assertFalse("ちゃんと残る、ちゃんと振り返れるリバーシ" in play)
        assertTrue("ScoreHeader(viewState.game)" in main)
        assertTrue("status.orEmpty()" in score)
        assertFalse("AI対局" in score)
        assertFalse("ローカル" in score)
    }

    @Test
    fun playScreenUsesEmphasizedRowsAndKeepsTwoPlayerAsNormalRow() {
        val play = main.substringAfter("private fun PlayScreen(").substringBefore("internal fun opponentRatingLabel")
        val onlineRow = "title = appString(R.string.play_online)"
        val aiRow = "title = appString(R.string.play_against_ai)"
        assertTrue(play.contains("emphasized = true"))
        assertTrue(play.indexOf(onlineRow) < play.indexOf(aiRow))
        assertTrue(play.indexOf(aiRow) < play.indexOf("R.string.two_player_match"))
        assertFalse(play.contains("Text(appString(R.string.online_match)"))
        assertFalse(play.contains("Text(appString(R.string.device_match)"))
        assertTrue(play.contains("onClick = onLocalAiStart"))
        assertTrue(play.contains("R.string.play_against_ai"))
        assertTrue(play.contains("title = appString(R.string.two_player_match)"))
    }

    @Test
    fun coordinateBoardBalancesAllFourGutters() {
        assertTrue(board.count { it == 'G' } > 0)
        assertTrue(board.contains("Spacer(Modifier.width(CoordinateGutter))"))
        assertTrue(board.contains("Spacer(Modifier.weight(1f))"))
    }

    @Test
    fun settingsAndDataActionsUseTheNewHierarchy() {
        assertTrue(analysis.contains("R.string.ai_match_settings"))
        assertTrue(analysis.contains("R.string.match_common_settings"))
        assertTrue(analysis.contains("if (status.evaluationData != null)"))
        assertTrue(analysis.contains("if (status.openingBook != null)"))
        assertTrue(analysis.contains("R.string.delete_eval_confirm_text"))
        assertTrue(analysis.contains("R.string.delete_book_confirm_text"))
        assertFalse(analysis.contains("詳しい説明を見る"))
        assertTrue(analysis.contains("R.string.choose_book"))
    }

    @Test
    fun webRowsAreMarkedAndResearchSettingsAvoidInternalModelTerms() {
        val components = File("../core/designsystem/src/main/java/com/example/othello/designsystem/ChanrivaComponents.kt").readText()
        assertTrue(components.contains("trailingLabel: String?"))
        assertTrue(analysis.contains("R.string.edax_about"))
        assertTrue(analysis.contains("trailingLabel = \"Web\""))
        assertFalse(research.contains("研究subject:"))
        assertFalse(research.contains("参加period:"))
        assertTrue(research.contains("R.string.research_link"))
    }

    @Test
    fun privacyAndEdaxLinksLiveAtTheEndOfAboutScreen() {
        assertFalse(topLevel.contains("R.string.privacy_policy"))
        assertTrue(analysis.indexOf("R.string.official_site") < analysis.indexOf("R.string.source_code"))
        assertTrue(analysis.indexOf("R.string.source_code") < analysis.indexOf("R.string.privacy_policy"))
        assertTrue(analysis.indexOf("R.string.privacy_policy") < analysis.indexOf("R.string.edax_about"))
        assertTrue(analysis.indexOf("R.string.edax_about") < analysis.indexOf("R.string.oss_licenses"))
        assertTrue(analysis.indexOf("R.string.edax_about") < analysis.indexOf("R.string.edax_disclaimer"))
        assertTrue(analysis.contains("fontSize = 11.sp"))
        assertTrue(analysis.contains("color = ChanrivaColors.accent"))
        assertFalse(analysis.contains("Text(appString(R.string.app_name), style = MaterialTheme.typography.labelLarge"))
        assertFalse(research.substringBefore("if (showConsentDialog)").contains("R.string.confirm_consent"))
    }

    @Test
    fun positionReviewIsPrimaryAndIsolatedFromGameRecordsAndResearch() {
        val study = topLevel.substringAfter("internal fun StudyScreen(").substringBefore("internal fun MoreScreen(")
        assertTrue(study.contains("emphasized = true"))
        assertTrue(study.split("emphasized = true").size - 1 == 4)
        assertTrue(study.contains("color = MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(study.indexOf("R.string.study_position_analysis") < study.indexOf("R.string.study_theory_analysis"))
        assertTrue(study.indexOf("R.string.study_theory_analysis") < study.indexOf("R.string.study_record_analysis"))
        assertTrue(study.indexOf("R.string.position_review") < study.indexOf("R.string.online_records"))
        assertTrue(study.indexOf("R.string.position_review") < study.indexOf("R.string.theory_exploration"))
        assertTrue(study.indexOf("R.string.theory_exploration") < study.indexOf("R.string.online_records"))
        assertTrue(study.indexOf("R.string.online_records") < study.indexOf("R.string.offline_records"))
        assertTrue(study.contains("titleBadge = appString(R.string.theory_enthusiast_recommended)"))
        assertTrue(study.split("titleBadge =").size - 1 == 1)
        assertTrue(positionReview.contains("PositionReviewStore"))
        assertTrue(positionReview.contains("PositionReviewSession"))
        assertFalse(positionReview.contains("LocalGameRecord"))
        assertFalse(positionReview.contains("ResearchReviewPanel"))
        assertFalse(positionReview.contains("ResearchPositionRepository"))
    }

    @Test
    fun positionReviewAutomaticallyAnalyzesAndHasNoManualAnalysisButton() {
        val screen = positionReview.substringAfter("internal fun PositionReviewScreen(")
            .substringBefore("private fun EditablePositionBoard")

        assertTrue(screen.contains("analysisCoordinator.begin(state, settings)"))
        assertTrue(screen.contains("analysisCoordinator.complete(request, session.current, currentSettings, analyzed)"))
        assertTrue(screen.contains("analysisCoordinator.invalidate()"))
        assertTrue(screen.contains("engine.cancel()"))
        assertTrue(screen.contains("result = null"))
        assertFalse(screen.contains("R.string.analyze_all_legal_moves"))
        assertFalse(screen.contains("R.string.cancel_analysis"))
    }

    @Test
    fun theoryExplorationIsDedicatedAutomaticAndRegistryDriven() {
        assertTrue(theoryExploration.contains("TheoryExplorationSession"))
        assertTrue(theoryExploration.contains("TheoryMetricRegistry.definitions"))
        assertTrue(theoryExploration.contains("TheoryMetricEvaluator.evaluateAll(state)"))
        assertTrue(theoryExploration.contains("analysisCoordinator.begin(state, settings)"))
        assertTrue(theoryExploration.contains("analysisCoordinator.complete(request, session.current, currentSettings, analyzed)"))
        assertTrue(theoryExploration.contains("analysisCoordinator.invalidate()"))
        assertTrue(theoryExploration.contains("engine.cancel()"))
        assertTrue(theoryExploration.contains("result = null"))
        assertTrue(theoryExploration.indexOf("text = edaxText") < theoryExploration.indexOf("text = metricText"))
        assertFalse(theoryExploration.contains("PositionReviewSession"))
        assertFalse(theoryExploration.contains("ReviewScreenV2"))
        assertFalse(theoryExploration.contains("LocalGameRecord"))
        assertFalse(theoryExploration.contains("save_position_review"))
        assertFalse(theoryExploration.contains("analyze_all_legal_moves"))

        val analysisEffect = theoryExploration.substringAfter("private fun TheoryExplorationContent(")
            .substringAfter("LaunchedEffect(")
            .substringBefore("DisposableEffect(")
        assertFalse(analysisEffect.contains("selectedMetric.id"))
        assertFalse(analysisEffect.contains("selectedMetricId"))
    }
}
