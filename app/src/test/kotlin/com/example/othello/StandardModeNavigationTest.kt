package com.example.othello

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class StandardModeNavigationTest {
    @Test
    fun authenticatedUsersStartAtModeSelection() {
        assertEquals(
            AuthenticatedModeDestination.MODE_SELECTION,
            initialAuthenticatedModeDestination(),
        )
    }

    @Test
    fun modeSelectionOpensStandardHomeOrTheUnchangedAdvancedRoute() {
        assertEquals(
            AuthenticatedModeDestination.STANDARD_HOME,
            destinationFor(AppMode.STANDARD),
        )
        assertEquals(
            AuthenticatedModeDestination.ADVANCED,
            destinationFor(AppMode.ADVANCED),
        )
    }

    @Test
    fun standardHomeRoutesAiOnlineAndRealEventsToTheirDestinations() {
        assertEquals(
            AuthenticatedModeDestination.STANDARD_AI,
            destinationFor(StandardFeature.AI),
        )
        assertEquals(
            AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON,
            destinationFor(StandardFeature.ONLINE),
        )
        assertEquals(
            AuthenticatedModeDestination.STANDARD_REAL_EVENT,
            destinationFor(StandardFeature.REAL_EVENT),
        )
    }

    @Test
    fun standardFeatureScreensReturnToStandardHome() {
        listOf(
            AuthenticatedModeDestination.STANDARD_AI,
            AuthenticatedModeDestination.STANDARD_WINNING_TIPS,
            AuthenticatedModeDestination.STANDARD_GACHA,
            AuthenticatedModeDestination.STANDARD_COLLECTION,
            AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON,
            AuthenticatedModeDestination.STANDARD_REAL_EVENT,
        ).forEach { destination ->
            assertEquals(
                AuthenticatedModeDestination.STANDARD_HOME,
                authenticatedModeBackDestination(destination),
            )
        }
    }

    @Test
    fun standardHomeReturnsToModeSelectionWithoutChangingAdvancedBackHandling() {
        assertEquals(
            AuthenticatedModeDestination.MODE_SELECTION,
            authenticatedModeBackDestination(AuthenticatedModeDestination.STANDARD_HOME),
        )
        assertNull(authenticatedModeBackDestination(AuthenticatedModeDestination.MODE_SELECTION))
        assertNull(authenticatedModeBackDestination(AuthenticatedModeDestination.ADVANCED))
    }
}
