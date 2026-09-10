package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardGachaUiContractTest {
    private val source =
        File("src/main/kotlin/com/example/othello/StandardGachaScreen.kt").readText()

    @Test
    fun gachaUsesLocalSnapshotAndCollectionStoreWithoutRemoteRpc() {
        assertTrue("application.standardContent.snapshot()" in source)
        assertTrue("withContext(Dispatchers.IO)" in source)
        assertTrue("collectionStore.markObtained(userId, entry.card.id)" in source)
        assertTrue("StandardGachaDailyStore(context)" in source)
        assertTrue("dailyStore.tryConsumeFreeDraw(userId)" in source)
        assertFalse("StandardContentIndexFetcher" in source)
        assertFalse("refresh(" in source)
        assertFalse("Supabase" in source)
    }

    @Test
    fun gachaHasRevealLoopNewStateAndCollectionExit() {
        assertTrue("STANDARD_GACHA_REVEAL_DELAY_MILLIS" in source)
        assertTrue("rememberInfiniteTransition" in source)
        assertTrue("R.string.standard_gacha_new" in source)
        assertTrue("R.string.standard_gacha_duplicate" in source)
        assertTrue("R.string.standard_gacha_daily_remaining" in source)
        assertTrue("R.string.standard_gacha_daily_limit_reached" in source)
        assertTrue("dailyState.canDrawForFree" in source)
        assertTrue("R.string.standard_gacha_draw_again" in source)
        assertTrue("R.string.standard_gacha_open_collection" in source)
        assertTrue("onClick = onCollection" in source)
    }

    @Test
    fun gachaStaysOutsideAdvancedAndMatchImplementation() {
        assertFalse("advancedContent" in source)
        assertFalse("AuthenticatedApp" in source)
        assertFalse("Edax" in source)
        assertFalse("LocalOthelloBoard" in source)
        assertFalse("StandardLocalAiCoordinator" in source)
    }
}
