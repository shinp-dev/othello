package com.example.othello

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardModeNavigationTest {
    @Test
    fun roomConnectionFailureReturnsToLobbyUnlessGameOverAlreadyExplainsTheEnding() {
        assertTrue(
            shouldReturnFromPeoplePlayRoomAfterConnectionFailure(
                AuthenticatedModeDestination.PEOPLE_ROOM,
                "CONNECTION_FAILED",
                null,
            ),
        )
        assertFalse(
            shouldReturnFromPeoplePlayRoomAfterConnectionFailure(
                AuthenticatedModeDestination.PEOPLE_MATCH,
                "CONNECTION_FAILED",
                null,
            ),
        )
        assertFalse(
            shouldReturnFromPeoplePlayRoomAfterConnectionFailure(
                AuthenticatedModeDestination.PEOPLE_ROOM,
                null,
                null,
            ),
        )
        assertFalse(
            shouldReturnFromPeoplePlayRoomAfterConnectionFailure(
                AuthenticatedModeDestination.PEOPLE_ROOM,
                "CONNECTION_FAILED",
                com.example.othello.network.peopleplay.PeoplePlayGameResult(
                    finishReason = com.example.othello.network.peopleplay.FinishReason.DISCONNECT,
                    outcome = com.example.othello.network.peopleplay.Outcome.WHITE_WIN,
                    winner = com.example.othello.network.peopleplay.PlayerColor.WHITE,
                ),
            ),
        )
    }

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
    fun standardFeatureDestinationsRemainMapped() {
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
        ).forEach { destination ->
            assertEquals(
                AuthenticatedModeDestination.STANDARD_HOME,
                authenticatedModeBackDestination(destination),
            )
        }
    }

    @Test
    fun peopleHomeAndChildScreensReturnToTheirParent() {
        assertEquals(
            AuthenticatedModeDestination.PEOPLE_HOME,
            authenticatedModeBackDestination(AuthenticatedModeDestination.STANDARD_REAL_EVENT),
        )
        assertEquals(
            AuthenticatedModeDestination.MODE_SELECTION,
            authenticatedModeBackDestination(AuthenticatedModeDestination.PEOPLE_HOME),
        )
        listOf(
            AuthenticatedModeDestination.PEOPLE_MATCH,
            AuthenticatedModeDestination.PEOPLE_SOCIAL,
        ).forEach { destination ->
            assertEquals(
                AuthenticatedModeDestination.PEOPLE_HOME,
                authenticatedModeBackDestination(destination),
            )
        }
        assertEquals(
            null,
            authenticatedModeBackDestination(AuthenticatedModeDestination.PEOPLE_NAME_SELECTION),
        )
    }

    @Test
    fun bothModeHomesReturnToModeSelection() {
        assertEquals(
            AuthenticatedModeDestination.MODE_SELECTION,
            authenticatedModeBackDestination(AuthenticatedModeDestination.STANDARD_HOME),
        )
        assertEquals(
            null,
            authenticatedModeBackDestination(AuthenticatedModeDestination.MODE_SELECTION),
        )
        assertEquals(
            AuthenticatedModeDestination.MODE_SELECTION,
            authenticatedModeBackDestination(AuthenticatedModeDestination.ADVANCED),
        )
    }
}
