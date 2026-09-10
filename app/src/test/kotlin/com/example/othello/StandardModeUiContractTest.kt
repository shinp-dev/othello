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
        assertTrue("AuthenticatedModeDestination.ADVANCED -> advancedContent()" in source)
        assertFalse("ChanrivaBottomNavigation" in source)
        assertFalse("AppDestination" in source)
    }

    @Test
    fun standardHomeUsesThreeEqualCardsAndASecondaryModeSwitch() {
        val home = source.substringAfter("private fun StandardHomeScreen(")
            .substringBefore("@Composable\nprivate fun StandardComingSoonScreen")
        assertTrue("StandardFeature.entries.forEach" in home)
        assertTrue("ModeCard(" in home)
        assertTrue("TextButton(" in home)
        assertTrue("R.string.switch_to_advanced_mode" in home)
    }

    @Test
    fun unfinishedFeaturesShareOneComingSoonComposable() {
        assertEquals(1, source.split("private fun StandardComingSoonScreen(").size - 1)
        assertTrue("R.string.feature_coming_soon" in source)
        assertTrue("R.string.back_to_standard_home" in source)
        assertTrue("AuthenticatedModeDestination.STANDARD_AI -> StandardAiRoute(" in source)
    }
}
