package com.example.othello

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppNavigationTest {
    @Test
    fun topLevelOrderAndLaunchDestinationAreStable() {
        assertEquals(
            listOf(AppDestination.PLAY, AppDestination.STUDY, AppDestination.SETTINGS, AppDestination.MORE),
            topLevelDestinations,
        )
        assertNull(backDestination(AppDestination.PLAY))
        assertEquals(AppDestination.PLAY, backDestination(AppDestination.STUDY))
        assertEquals(AppDestination.PLAY, backDestination(AppDestination.SETTINGS))
        assertEquals(AppDestination.PLAY, backDestination(AppDestination.MORE))
    }

    @Test
    fun detailScreensReturnToTheirLogicalParents() {
        assertEquals(AppDestination.STUDY, backDestination(AppDestination.ONLINE_RECORDS))
        assertEquals(AppDestination.STUDY, backDestination(AppDestination.OFFLINE_RECORDS))
        assertEquals(
            AppDestination.ONLINE_RECORDS,
            backDestination(AppDestination.REVIEW, reviewParent = AppDestination.ONLINE_RECORDS),
        )
        assertEquals(
            AppDestination.OFFLINE_RECORDS,
            backDestination(AppDestination.REVIEW, reviewParent = AppDestination.OFFLINE_RECORDS),
        )
        assertEquals(AppDestination.SETTINGS, backDestination(AppDestination.MATCH_SETTINGS))
        assertEquals(AppDestination.SETTINGS, backDestination(AppDestination.REVIEW_SETTINGS))
        assertEquals(AppDestination.SETTINGS, backDestination(AppDestination.COMMON_SETTINGS))
        assertEquals(AppDestination.SETTINGS, backDestination(AppDestination.RESEARCH_SETTINGS))
        assertEquals(
            AppDestination.RESEARCH_INFO,
            backDestination(
                AppDestination.RESEARCH_SETTINGS,
                researchSettingsParent = AppDestination.RESEARCH_INFO,
            ),
        )
        assertEquals(AppDestination.MORE, backDestination(AppDestination.RESEARCH_INFO))
        assertEquals(AppDestination.MORE, backDestination(AppDestination.ACCOUNT))
        assertEquals(AppDestination.ACCOUNT, backDestination(AppDestination.ACCOUNT_DELETION))
        assertEquals(AppDestination.MORE, backDestination(AppDestination.ABOUT))
        assertEquals(AppDestination.ABOUT, backDestination(AppDestination.OSS_LICENSES))
    }

    @Test
    fun commonSettingsReturnsToTheScreenThatOpenedIt() {
        assertEquals(
            AppDestination.REVIEW,
            backDestination(AppDestination.COMMON_SETTINGS, commonSettingsParent = AppDestination.REVIEW),
        )
        assertEquals(
            AppDestination.LOCAL_AI_SETUP,
            backDestination(
                AppDestination.COMMON_SETTINGS,
                commonSettingsParent = AppDestination.LOCAL_AI_SETUP,
            ),
        )
    }
}
