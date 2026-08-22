package com.example.othello.analysis.edax

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class EdaxAiBridgeContractTest {
    private val source = File("src/main/cpp/edax_android_bridge.c").readText()
    private val aiBody = source.substringAfter("int edax_android_choose_best_move(")
        .substringBefore("const char *edax_android_version")
    private val reviewBody = source.substringAfter("int edax_android_analyze(")
        .substringBefore("static bool select_best_legal_book_move")

    @Test
    fun aiUsesOneTimedRootSearchWhileReviewRemainsUntimedPerCandidate() {
        assertEquals(1, Regex("""\bsearch_run\s*\(""").findAll(aiBody).count())
        assertEquals(1, Regex("""\bsearch_set_move_time\s*\(""").findAll(aiBody).count())
        assertTrue(aiBody.indexOf("search_set_move_time") < aiBody.indexOf("search_run"))
        assertFalse("search_set_move_time" in reviewBody)
    }

    @Test
    fun bridgeKeepsSingleTaskAndDistinguishesTimeoutFromExplicitCancel() {
        assertTrue(Regex("""options\.n_task\s*=\s*1\s*;""").containsMatchIn(source))
        assertTrue("STOP_ON_DEMAND" in aiBody)
        assertFalse("->stop == STOP_TIMEOUT" in aiBody)
    }

    @Test
    fun bookIsConsultedBeforeRootSearchAndReturnedMoveIsCheckedAgainstRootLegality() {
        assertTrue(aiBody.indexOf("select_best_legal_book_move") < aiBody.indexOf("search_run"))
        assertTrue("book_get_moves(&loaded_book" in source)
        assertTrue("foreach_bit(square, legal)" in source)
        assertTrue("book_move->score > best_score" in source)
        assertTrue("legal & (UINT64_C(1) << square)" in aiBody)
        assertTrue("square < A1 || square > H8" in aiBody)
    }

    @Test
    fun evaluationDataIsReusedWhenThePathIsUnchanged() {
        val ensureEvalBody = source.substringAfter("static int ensure_eval(")
            .substringBefore("static int ensure_book(")
        assertTrue("eval_loaded && strcmp(eval_path_loaded, path) == 0" in ensureEvalBody)
        assertTrue("return EDAX_ANDROID_OK" in ensureEvalBody)
    }

    @Test
    fun aiSearchCleanupCoversFatalAndNormalExits() {
        assertTrue(Regex("""clear_active_search\s*\(""").findAll(aiBody).count() >= 3)
        assertTrue(Regex("""search_free\s*\(""").findAll(aiBody).count() >= 2)
        assertTrue(Regex("""free\s*\(\(Search \*\) search\)""").findAll(aiBody).count() >= 2)
    }
}
