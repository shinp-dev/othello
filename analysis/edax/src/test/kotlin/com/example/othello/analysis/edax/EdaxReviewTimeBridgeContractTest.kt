package com.example.othello.analysis.edax

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class EdaxReviewTimeBridgeContractTest {
    private val bridgeSource = File("src/main/cpp/edax_android_bridge.c").readText()
    private val reviewBody = bridgeSource.substringAfter("int edax_android_analyze(")
        .substringBefore("static bool select_best_legal_book_move")
    private val jniSource = File("src/main/cpp/edax_jni.cpp").readText()
    private val jniReviewBody = jniSource.substringAfter("NativeEdax_nativeAnalyze(")
        .substringBefore("NativeEdax_nativeChooseBestMove(")
    private val kotlinSource = File("src/main/kotlin/com/example/othello/analysis/edax/ProductionAnalysisEngine.kt").readText()
    private val dataManagerSource = File("src/main/kotlin/com/example/othello/analysis/edax/EdaxDataManager.kt").readText()

    @Test
    fun candidateTimeTravelsThroughKotlinJniAndBridge() {
        assertTrue("timePerCandidateMs: Int" in kotlinSource)
        assertTrue("timePerCandidateMs = settings.timePerCandidateMs" in kotlinSource)
        assertTrue("fun analysisSettings(settings: ReviewAnalysisSettings)" in dataManagerSource)
        assertTrue("timePerCandidateMs = settings.timePerCandidateMs" in dataManagerSource)
        assertTrue("jint time_per_candidate_ms" in jniReviewBody)
        assertTrue("time_per_candidate_ms," in jniReviewBody)
        assertTrue("int time_per_candidate_ms" in reviewBody)
    }

    @Test
    fun everySearchedCandidateGetsItsOwnMoveTimeAfterLevelAndBeforeRun() {
        assertEquals(1, Regex("""\bsearch_set_move_time\s*\(""").findAll(reviewBody).count())
        val candidateLoop = reviewBody.substringAfter("foreach_bit(square, legal)")
        val searchedCandidate = candidateLoop.substringAfter("if (!book_hit)")
        assertTrue(searchedCandidate.indexOf("search_set_level") < searchedCandidate.indexOf("search_set_move_time"))
        assertTrue(searchedCandidate.indexOf("search_set_move_time") < searchedCandidate.indexOf("search_run"))
    }

    @Test
    fun bookCandidatesStillSkipSearchAndOnlyExplicitStopCancels() {
        assertTrue(reviewBody.indexOf("if (has_book_position)") < reviewBody.indexOf("if (!book_hit)"))
        assertTrue("search.stop == STOP_ON_DEMAND" in reviewBody)
        assertFalse("search.stop == STOP_TIMEOUT" in reviewBody)
    }

    @Test
    fun nativeResultFieldsAreForwardedWithoutInventingTimeoutValues() {
        assertTrue("output->score = -search.result->score" in reviewBody)
        assertTrue("output->depth = search.result->depth" in reviewBody)
        assertTrue("selectivity_percent(search.result->selectivity)" in reviewBody)
    }

    @Test
    fun bridgeRemainsSingleTaskAndCandidateSearchRemainsSequential() {
        assertTrue(Regex("""options\.n_task\s*=\s*1\s*;""").containsMatchIn(bridgeSource))
        assertEquals(1, Regex("""\bforeach_bit\s*\(square, legal\)""").findAll(reviewBody).count())
    }
}
