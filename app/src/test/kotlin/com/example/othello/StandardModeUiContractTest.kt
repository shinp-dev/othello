package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardModeUiContractTest {
    private val source = File("src/main/kotlin/com/example/othello/StandardModeNavigation.kt").readText()

    @Test
    fun bootstrapGatesStandardHomeFeaturesButNotTheIndependentRealEvent() {
        val route = source.substringAfter("internal fun AuthenticatedModeRoute(")
            .substringBefore("@Composable\ninternal fun StandardHomeScreen(")
        val bootstrapIndex = route.indexOf("else -> StandardBootstrapRoute(")
        assertTrue(bootstrapIndex > route.indexOf("AuthenticatedModeDestination.ADVANCED -> advancedContent"))
        assertTrue(bootstrapIndex > route.indexOf("AuthenticatedModeDestination.MODE_SELECTION -> ModeSelectionScreen"))
        AuthenticatedModeDestination.entries.filter { it.name.startsWith("STANDARD_") && it != AuthenticatedModeDestination.STANDARD_REAL_EVENT }.forEach {
            assertTrue(route.indexOf("AuthenticatedModeDestination.$it ->") > bootstrapIndex)
        }
        assertTrue(route.indexOf("AuthenticatedModeDestination.STANDARD_REAL_EVENT -> StandardRealEventRoute(") < bootstrapIndex)
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
    fun standardHomeContainsOnlyTheTwoChallengesAndWinningTips() {
        val home = source.substringAfter("internal fun StandardHomeScreen(")
            .substringBefore("@Composable\nprivate fun StandardHomeFeatureCard")
        assertTrue("opponents.pack(\"animal\")" in home)
        assertTrue("opponents.pack(\"lione-boss\")" in home)
        assertTrue("R.string.standard_home_animals_title" in home)
        assertTrue("R.string.standard_home_king_title" in home)
        assertTrue("R.string.standard_winning_tips_title" in home)
        assertTrue("StandardHomeTipsCard(" in home)
        assertFalse("onGacha" in home)
        assertFalse("onCollection" in home)
        assertFalse("onSwitchMode" in home)
        assertFalse("R.string.standard_mode" in home)
    }

    @Test
    fun cardsUseTheAssignedBackgroundsAndSharedClickableTreatment() {
        val home = source.substringAfter("internal fun StandardHomeScreen(")
            .substringBefore("@Composable\nprivate fun StandardHomeFeatureCard")
        val feature = source.substringAfter("private fun StandardHomeFeatureCard(")
            .substringBefore("@Composable\nprivate fun StandardHomeTipsCard")
        val tips = source.substringAfter("private fun StandardHomeTipsCard(")
            .substringBefore("@Composable\nprivate fun StandardHomeArrow")
        assertTrue("R.drawable.standard_screen_bg" in home)
        assertTrue("R.drawable.card_animals_bg" in home)
        assertTrue("R.drawable.card_lion_bg" in home)
        assertTrue("R.drawable.card_tips_bg" in tips)
        assertTrue("animalPack.image(animalPack.definition.banner)" in home)
        assertTrue("kingPack.image(kingPack.definition.banner)" in home)
        assertTrue("contentScale = ContentScale.Fit" in feature)
        assertTrue("Brush.horizontalGradient" in feature)
        assertTrue("StandardHomeArrow(" in feature)
        assertTrue("StandardHomeArrow(" in tips)
        assertTrue("Card(\n        onClick = onClick" in feature)
        assertTrue("Card(\n        onClick = onClick" in tips)
        assertTrue("RoundedCornerShape(20.dp)" in source)
        assertTrue("verticalScroll(rememberScrollState())" in home)
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
