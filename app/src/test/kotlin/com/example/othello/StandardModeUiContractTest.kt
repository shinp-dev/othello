package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardModeUiContractTest {
    private val source = File("src/main/kotlin/com/example/othello/StandardModeNavigation.kt").readText()

    @Test
    fun standardRouteIsSeparateFromAdvancedNavigation() {
        assertTrue("AuthenticatedModeDestination.ADVANCED -> advancedContent {" in source)
        assertTrue("destination = AuthenticatedModeDestination.MODE_SELECTION" in source)
        assertFalse("ChanrivaBottomNavigation" in source)
        assertFalse("AppDestination" in source)
    }

    @Test
    fun standardHomeShowsAnimalPackThenWinningTipsGachaCollectionAndRealEvent() {
        val home = source.substringAfter("private fun StandardHomeScreen(")
            .substringBefore("@Composable\nprivate fun StandardOpponentPackPreviewCard")
        val packIndex = home.indexOf("StandardOpponentPackPreviewCard(")
        val tipsIndex = home.indexOf("R.string.standard_winning_tips_title")
        val gachaIndex = home.indexOf("R.string.standard_gacha_title")
        val collectionIndex = home.indexOf("R.string.standard_collection_title")
        val realEventIndex = home.indexOf("title = appString(StandardFeature.REAL_EVENT.titleRes)")
        assertTrue(packIndex >= 0)
        assertTrue(tipsIndex > packIndex)
        assertTrue(gachaIndex > tipsIndex)
        assertTrue(collectionIndex > gachaIndex)
        assertTrue(realEventIndex > collectionIndex)
        assertFalse("title = appString(StandardFeature.AI.titleRes)" in home)
        assertFalse("title = appString(StandardFeature.ONLINE.titleRes)" in home)
        assertTrue("pack = StandardOpponentPacks.animal" in home)
        assertTrue("onClick = onWinningTips" in home)
        assertTrue("onClick = onGacha" in home)
        assertTrue("onClick = onCollection" in home)
        assertTrue("R.drawable.standard_home_winning_tips_banner" in home)
        assertTrue("R.drawable.standard_home_gacha_banner" in home)
        assertTrue("R.drawable.standard_home_collection_banner" in home)
        assertTrue("R.drawable.standard_home_real_event_banner" in home)
        assertTrue("TextButton(" in home)
        assertTrue("R.string.switch_to_advanced_mode" in home)
    }

    @Test
    fun animalPackUsesOneWideSceneInsteadOfFourSeparateIcons() {
        val card = source.substringAfter("private fun StandardOpponentPackPreviewCard(")
            .substringBefore("@Composable\nprivate fun StandardComingSoonScreen")

        assertTrue("painterResource(pack.bannerDrawableRes)" in card)
        assertTrue(".aspectRatio(3f)" in card)
        assertTrue("contentScale = ContentScale.Fit" in card)
        assertFalse("pack.previewLevels.forEach" in card)
    }

    @Test
    fun standardHomeModeCardsCanRenderWideBanners() {
        val card = source.substringAfter("private fun ModeCard(")

        assertTrue("@DrawableRes bannerDrawableRes: Int? = null" in card)
        assertTrue("painterResource(bannerDrawableRes)" in card)
        assertTrue(".aspectRatio(3f)" in card)
        assertTrue("contentScale = ContentScale.Fit" in card)
    }

    @Test
    fun unfinishedFeaturesShareOneComingSoonComposable() {
        assertEquals(1, source.split("private fun StandardComingSoonScreen(").size - 1)
        assertTrue("R.string.feature_coming_soon" in source)
        assertTrue("R.string.back_to_standard_home" in source)
        assertTrue("AuthenticatedModeDestination.STANDARD_AI -> StandardAiRoute(" in source)
        assertTrue("AuthenticatedModeDestination.STANDARD_WINNING_TIPS -> StandardWinningTipsRoute(" in source)
        assertTrue("AuthenticatedModeDestination.STANDARD_GACHA -> StandardGachaRoute(" in source)
        assertTrue("AuthenticatedModeDestination.STANDARD_COLLECTION -> StandardCollectionRoute(" in source)
    }
}
