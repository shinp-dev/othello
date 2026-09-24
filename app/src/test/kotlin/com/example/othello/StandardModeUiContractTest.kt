package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardModeUiContractTest {
    private val source = File("src/main/kotlin/com/example/othello/StandardModeNavigation.kt").readText()

    @Test
    fun bootstrapGatesAllStandardDestinationsButNotModeSelectionOrAdvanced() {
        val route = source.substringAfter("internal fun AuthenticatedModeRoute(")
            .substringBefore("@Composable\ninternal fun StandardHomeScreen(")
        val bootstrapIndex = route.indexOf("else -> StandardBootstrapRoute(")
        assertTrue(bootstrapIndex > route.indexOf("AuthenticatedModeDestination.ADVANCED -> advancedContent"))
        assertTrue(bootstrapIndex > route.indexOf("AuthenticatedModeDestination.MODE_SELECTION -> ModeSelectionScreen"))
        AuthenticatedModeDestination.entries.filter { it.name.startsWith("STANDARD_") }.forEach {
            assertTrue(route.indexOf("AuthenticatedModeDestination.$it ->") > bootstrapIndex)
        }
        assertTrue("preparationState = aiState" in route)
        assertEquals(2, route.split("content = content").size - 1)
        val gate = File("src/main/kotlin/com/example/othello/StandardBootstrapScreen.kt").readText()
        assertTrue("remember(application, userId)" in gate)
        assertFalse("rememberSaveable" in gate)
        assertTrue("is StandardBootstrapState.Ready -> content" in gate)
        assertTrue("BackHandler(onBack = onBack)" in gate)
    }

    @Test
    fun standardRouteIsSeparateFromAdvancedNavigation() {
        assertTrue("AuthenticatedModeDestination.ADVANCED -> advancedContent {" in source)
        assertTrue("destination = AuthenticatedModeDestination.MODE_SELECTION" in source)
        assertFalse("ChanrivaBottomNavigation" in source)
        assertFalse("AppDestination" in source)
    }

    @Test
    fun standardHomeShowsPacksGachaCollectionAndTipsWhileRealEventsLiveOnSelection() {
        val home = source.substringAfter("internal fun StandardHomeScreen(")
            .substringBefore("private fun StandardOpponentPackPreviewCard")
        val packIndex = home.indexOf("OpponentHomeSectionCards(")
        val tipsIndex = home.indexOf("R.string.standard_winning_tips_title")
        val gachaIndex = home.indexOf("R.string.standard_gacha_title")
        val collectionIndex = home.indexOf("R.string.standard_collection_title")
        assertTrue(packIndex >= 0)
        assertTrue(gachaIndex > packIndex)
        assertTrue(collectionIndex > gachaIndex)
        assertTrue(tipsIndex > collectionIndex)
        assertFalse("title = appString(StandardFeature.AI.titleRes)" in home)
        assertFalse("title = appString(StandardFeature.ONLINE.titleRes)" in home)
        assertTrue("OpponentHomeSection.FEATURED" in home)
        assertTrue("OpponentHomeSection.CHALLENGES" in home)
        assertTrue("onClick = onWinningTips" in home)
        assertTrue("onClick = onGacha" in home)
        assertTrue("onClick = onCollection" in home)
        assertTrue("R.drawable.standard_home_winning_tips_art" in home)
        assertTrue("R.drawable.standard_home_gacha_icon" in home)
        assertTrue("R.drawable.standard_home_collection_icon" in home)
        assertFalse("StandardFeature.REAL_EVENT" in home)
        assertTrue("TextButton(" in home)
        assertTrue("R.string.switch_to_advanced_mode" in home)
    }

    @Test
    fun standardHomeUsesOneScreenHierarchyWithTwoMiniCards() {
        val home = source.substringAfter("internal fun StandardHomeScreen(")
            .substringBefore("private fun StandardOpponentPackPreviewCard")

        assertTrue("BoxWithConstraints(" in home)
        assertTrue("verticalScroll(" in home)
        assertTrue("StandardHomeWideFeatureCard(" in home)
        assertEquals(2, home.split("StandardHomeMiniFeatureCard(").size - 1)
        assertTrue("horizontalArrangement = Arrangement.spacedBy(itemSpacing)" in home)
        assertTrue("modifier = Modifier.weight(1f)" in home)
        assertTrue("Spacer(Modifier.height(itemSpacing))" in home)
    }

    @Test
    fun animalPackUsesOneWideSceneInsteadOfFourSeparateIcons() {
        val card = source.substringAfter("private fun StandardOpponentPackPreviewCard(")
            .substringBefore("private fun StandardHomeWideFeatureCard")

        assertTrue("installedPack.image(pack.banner)" in card)
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
    fun standardHomeCardsUseSubtleBordersAndElevation() {
        val packCard = source.substringAfter("private fun StandardOpponentPackPreviewCard(")
            .substringBefore("private fun StandardHomeWideFeatureCard")
        val wideCard = source.substringAfter("private fun StandardHomeWideFeatureCard(")
            .substringBefore("private fun StandardHomeMiniFeatureCard")
        val miniCard = source.substringAfter("private fun StandardHomeMiniFeatureCard(")
            .substringBefore("private fun standardHomeCardBorder")

        listOf(packCard, wideCard, miniCard).forEach { card ->
            assertTrue("border = standardHomeCardBorder()" in card)
            assertTrue("elevation = standardHomeCardElevation()" in card)
        }
        assertTrue("MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)" in source)
        assertTrue("defaultElevation = 1.dp" in source)
        assertTrue("pressedElevation = 0.dp" in source)
    }

    @Test
    fun unfinishedFeaturesShareOneComingSoonComposable() {
        assertEquals(1, source.split("private fun StandardComingSoonScreen(").size - 1)
        assertTrue("R.string.feature_coming_soon" in source)
        assertTrue("R.string.back_to_standard_home" in source)
        assertTrue("AuthenticatedModeDestination.STANDARD_AI -> StandardAiPackRoute(" in source)
        assertTrue("AuthenticatedModeDestination.STANDARD_WINNING_TIPS -> StandardWinningTipsRoute(" in source)
        assertTrue("AuthenticatedModeDestination.STANDARD_GACHA -> StandardGachaRoute(" in source)
        assertTrue("AuthenticatedModeDestination.STANDARD_COLLECTION -> StandardCollectionRoute(" in source)
    }
}
