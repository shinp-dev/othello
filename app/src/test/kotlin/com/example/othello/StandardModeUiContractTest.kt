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
            .substringBefore("private fun StandardOpponentPackPreviewCard")
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
        assertTrue("R.drawable.standard_home_winning_tips_art" in home)
        assertTrue("R.drawable.standard_home_gacha_icon" in home)
        assertTrue("R.drawable.standard_home_collection_icon" in home)
        assertTrue("R.drawable.standard_home_real_event_photo" in home)
        assertTrue("TextButton(" in home)
        assertTrue("R.string.switch_to_advanced_mode" in home)
    }

    @Test
    fun standardHomeUsesOneScreenHierarchyWithTwoMiniCards() {
        val home = source.substringAfter("private fun StandardHomeScreen(")
            .substringBefore("private fun StandardOpponentPackPreviewCard")

        assertTrue("BoxWithConstraints(" in home)
        assertFalse("verticalScroll(" in home)
        assertTrue("StandardHomeWideFeatureCard(" in home)
        assertEquals(2, home.split("StandardHomeMiniFeatureCard(").size - 1)
        assertTrue("horizontalArrangement = Arrangement.spacedBy(itemSpacing)" in home)
        assertTrue("modifier = Modifier.weight(1f)" in home)
        assertTrue("Spacer(Modifier.weight(1f))" in home)
    }

    @Test
    fun animalPackUsesOneWideSceneInsteadOfFourSeparateIcons() {
        val card = source.substringAfter("private fun StandardOpponentPackPreviewCard(")
            .substringBefore("private fun StandardHomeWideFeatureCard")

        assertTrue("painterResource(pack.bannerDrawableRes)" in card)
        assertTrue(".height(artworkHeight)" in card)
        assertTrue(".align(Alignment.BottomCenter)" in card)
        assertTrue("contentScale = ContentScale.Fit" in card)
        assertFalse("pack.previewLevels.forEach" in card)
    }

    @Test
    fun standardHomeWideCardsUseCroppedArtworkUnderADarkOverlay() {
        val card = source.substringAfter("private fun StandardHomeWideFeatureCard(")
            .substringBefore("private fun StandardHomeMiniFeatureCard")

        assertTrue("painterResource(artworkDrawableRes)" in card)
        assertTrue("contentScale = ContentScale.Crop" in card)
        assertTrue("Brush.horizontalGradient" in card)
        assertTrue("ChanrivaColors.surfaceElevated.copy" in card)
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
