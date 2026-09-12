package com.example.othello

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardGachaUiContractTest {
    private val source =
        File("src/main/kotlin/com/example/othello/StandardGachaScreen.kt").readText()
    private val appBuild = File("build.gradle.kts").readText()

    @Test
    fun gachaGifSupportsTheExistingApi26Floor() {
        assertTrue("minSdk = 26" in appBuild)
        assertTrue("io.coil-kt.coil3:coil-compose:3.3.0" in appBuild)
        assertTrue("io.coil-kt.coil3:coil-gif:3.3.0" in appBuild)
    }

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
        assertTrue("R.drawable.standard_gacha_capsule_reveal" in source)
        assertTrue("AsyncImage(" in source)
        assertTrue(".repeatCount(0)" in source)
        assertTrue("CachePolicy.DISABLED" in source)
        assertFalse("standard_gacha_capsule_base_art" in source)
        assertFalse("standard_gacha_capsule_cracks_art" in source)
        assertFalse("standard_gacha_capsule_left_shell_art" in source)
        assertFalse("standard_gacha_capsule_right_shell_art" in source)
        assertFalse("BitmapFactory.decodeResource(" in source)
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
        assertTrue("maxLines = 2" in source)
        assertTrue("TextOverflow.Ellipsis" in source)
        assertTrue("R.string.standard_gacha_daily_limit_reached" in source)
        assertTrue("dailyState.canDrawForFree" in source)
        assertTrue("R.string.standard_gacha_draw_again_remaining" in source)
        assertTrue("R.string.standard_gacha_draw_free_remaining" in source)
        assertTrue("R.string.standard_gacha_open_collection" in source)
        assertTrue("onClick = onCollection" in source)
    }

    @Test
    fun resultCardKeepsOnlyStatusArtworkTitleAndSummary() {
        val resultCard = source.substringAfter("private fun StandardGachaResultCard(")
            .substringBefore("@Composable\nprivate fun StandardGachaProgress")

        assertTrue("R.string.standard_gacha_new" in resultCard)
        assertTrue("R.string.standard_gacha_duplicate" in resultCard)
        assertTrue("StandardContentArtwork(" in resultCard)
        assertTrue("text = entry.card.title" in resultCard)
        assertTrue("text = entry.card.summary" in resultCard)
        assertFalse("standardGachaTypeLabel" in resultCard)
        assertFalse("standardGachaRarityLabel" in resultCard)
        assertFalse("entry.card.author" in resultCard)
    }

    @Test
    fun remainingDrawsAppearOnlyInsideThePrimaryButton() {
        val screen = source.substringAfter("private fun StandardGachaScreen(")
            .substringBefore("@Composable\nprivate fun StandardGachaMachine")

        assertTrue("R.string.standard_gacha_draw_again_remaining" in screen)
        assertTrue("R.string.standard_gacha_draw_free_remaining" in screen)
        assertFalse("R.string.standard_gacha_daily_remaining" in screen)
        assertTrue(screen.indexOf("StandardGachaResultCard(") < screen.indexOf("StandardGachaProgress("))
    }

    @Test
    fun collectionProgressIsAThinNeutralSecondaryElement() {
        val progress = source.substringAfter("private fun StandardGachaProgress(")
            .substringBefore("@Composable\nprivate fun StandardGachaEmpty")

        assertTrue("MaterialTheme.typography.labelMedium" in progress)
        assertTrue(".height(3.dp)" in progress)
        assertTrue("color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)" in progress)
        assertTrue("trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)" in progress)
        assertFalse("MaterialTheme.colorScheme.primary" in progress)
        assertFalse("ChanrivaColors.accent" in progress)
    }

    @Test
    fun capsuleRevealGifIsValid() {
        val animation = File("src/main/res/drawable-nodpi/standard_gacha_capsule_reveal.gif")
        assertTrue(animation.isFile)
        assertTrue(animation.length() < 1_000_000L, "Reveal GIF should stay below 1 MB")

        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        ImageIO.createImageInputStream(animation).use { input ->
            reader.input = input
            assertTrue(reader.getNumImages(true) >= 28, "Reveal GIF should have smooth motion")
            assertTrue(reader.getWidth(0) == 320 && reader.getHeight(0) == 320)
            assertTrue(reader.read(0).colorModel.hasAlpha())
        }
        reader.dispose()
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
