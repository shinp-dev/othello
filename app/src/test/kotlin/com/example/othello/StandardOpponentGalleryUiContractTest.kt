package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardOpponentGalleryUiContractTest {
    private val gallery = File("src/main/kotlin/com/example/othello/StandardOpponentGallery.kt").readText()
    private val route = File("src/main/kotlin/com/example/othello/StandardAiScreens.kt").readText()

    @Test
    fun selectionUsesAdjacentCardsWithoutWrappingAtEitherEnd() {
        assertTrue("pack.players.getOrNull(selectedIndex - 1)" in gallery)
        assertTrue("pack.players.getOrNull(selectedIndex + 1)" in gallery)
        assertTrue("if (previous != null)" in gallery)
        assertTrue("if (next != null)" in gallery)
        assertFalse("HorizontalPager(" in gallery)
    }

    @Test
    fun selectedAndLockedCardsUseTheirDedicatedArtworkAndUnlockCondition() {
        assertTrue("R.drawable.animal_selection_frame" in gallery)
        assertTrue("R.drawable.animal_selection_locked_frame" in gallery)
        assertTrue("R.string.standard_ai_unlock_condition" in gallery)
        assertTrue("Icons.Filled.Lock" in gallery)
    }

    @Test
    fun selectionUsesExplicitStartButtonAndDoesNotKeepOldDirectionLabels() {
        assertTrue("ForestStartButton(" in gallery)
        assertTrue("onClick = onStart" in gallery)
        assertFalse("R.string.opponent_previous" in gallery)
        assertFalse("R.string.opponent_next" in gallery)
        assertTrue("StandardOpponentSelectionScreen(" in route)
    }
}
