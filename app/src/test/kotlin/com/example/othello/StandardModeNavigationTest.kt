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
    fun everyStandardHomeCardOpensItsComingSoonDestination() {
        assertEquals(
            AuthenticatedModeDestination.STANDARD_AI_COMING_SOON,
            destinationFor(StandardFeature.AI),
        )
        assertEquals(
            AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON,
            destinationFor(StandardFeature.ONLINE),
        )
        assertEquals(
            AuthenticatedModeDestination.STANDARD_REAL_EVENT_COMING_SOON,
            destinationFor(StandardFeature.REAL_EVENT),
        )
    }

    @Test
    fun comingSoonScreensReturnToStandardHome() {
        listOf(
            AuthenticatedModeDestination.STANDARD_AI_COMING_SOON,
            AuthenticatedModeDestination.STANDARD_ONLINE_COMING_SOON,
            AuthenticatedModeDestination.STANDARD_REAL_EVENT_COMING_SOON,
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
