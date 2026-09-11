package com.example.othello

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun gachaHasCapsuleCrackGlowRevealAndCollectionExit() {
        assertTrue("STANDARD_GACHA_PRESS_MILLIS" in source)
        assertTrue("STANDARD_GACHA_CRACK_MILLIS" in source)
        assertTrue("STANDARD_GACHA_BURST_MILLIS" in source)
        assertTrue("STANDARD_GACHA_PHASE_CRACK" in source)
        assertTrue("STANDARD_GACHA_PHASE_BURST" in source)
        assertTrue("R.drawable.standard_gacha_capsule_base_art" in source)
        assertTrue("R.drawable.standard_gacha_capsule_cracks_art" in source)
        assertTrue("R.drawable.standard_gacha_capsule_left_shell_art" in source)
        assertTrue("R.drawable.standard_gacha_capsule_right_shell_art" in source)
        assertTrue("BitmapFactory.decodeResource(" in source)
        assertTrue("runCatching {" in source)
        assertTrue("leftShellBitmap?.let" in source)
        assertTrue("rightShellBitmap?.let" in source)
        assertFalse(".clipToBounds()" in source)
        assertTrue("standardGachaGlowColor" in source)
        assertTrue("StandardContentRarity.COMMON -> Color(0xFFDCEBFF)" in source)
        assertTrue("StandardContentRarity.RARE -> Color(0xFFFFD76A)" in source)
        assertTrue("StandardContentRarity.SPECIAL -> Color(0xFFFF5A66)" in source)
        assertTrue("R.string.standard_gacha_capsule_tap" in source)
        assertTrue(".clickable(" in source)
        assertTrue("R.string.standard_gacha_new" in source)
        assertTrue("R.string.standard_gacha_duplicate" in source)
        assertTrue("StandardContentArtwork(" in source)
        assertFalse("standardGachaTypeLabel(" in source)
        assertFalse("standardGachaRarityLabel(" in source)
        assertTrue("maxLines = 1" in source)
        assertTrue("TextOverflow.Ellipsis" in source)
        assertFalse("R.string.standard_gacha_daily_remaining" in source)
        assertTrue("R.string.standard_gacha_daily_limit_reached" in source)
        assertTrue("dailyState.canDrawForFree" in source)
        assertTrue("R.string.standard_gacha_draw_again_remaining" in source)
        assertTrue("R.string.standard_gacha_draw_free_remaining" in source)
        assertTrue("color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)" in source)
        assertTrue("R.string.standard_gacha_open_collection" in source)
        assertTrue("onClick = onCollection" in source)
    }

    @Test
    fun rightCapsuleShellAssetIsDecodable() {
        val asset = File("src/main/res/drawable-nodpi/standard_gacha_capsule_right_shell_art.png")
        val image = assertNotNull(ImageIO.read(asset))

        assertEquals(256, image.width)
        assertEquals(256, image.height)
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
