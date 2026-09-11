package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardOpponentGalleryUiContractTest {
    private val source = File("src/main/kotlin/com/example/othello/StandardOpponentGallery.kt").readText()

    @Test
    fun opponentGalleryShowsCurrentOpponentWithOnlyItsNeighbors() {
        assertTrue("val previous = pack.opponents.getOrNull(selectedIndex - 1)" in source)
        assertTrue("val current = pack.opponents[selectedIndex]" in source)
        assertTrue("val next = pack.opponents.getOrNull(selectedIndex + 1)" in source)
        assertTrue("standard-ai-opponent-carousel" in source)
        assertTrue("SIDE_WEIGHT" in source)
        assertTrue("CENTER_WEIGHT" in source)
        assertFalse("HorizontalPager(" in source)
        assertFalse("LinearProgressIndicator(" in source)
        assertFalse("standard_ai_collection_progress" in source)
    }

    @Test
    fun opponentGalleryUsesSwipeAndTapInsteadOfASeparateStartButton() {
        assertTrue("detectHorizontalDragGestures(" in source)
        assertTrue("selectIfUnlocked(next)" in source)
        assertTrue("selectIfUnlocked(previous)" in source)
        assertTrue("onClick = onStart" in source)
        assertTrue("standard-ai-level-" in source)
    }

    @Test
    fun opponentGalleryKeepsAllLevelsInOneSequenceWithoutGroupHeadings() {
        assertFalse("standard_ai_group_basic" in source)
        assertFalse("standard_ai_group_serious" in source)
        assertFalse("pack.opponents.take(4)" in source)
        assertFalse("pack.opponents.drop(4)" in source)
    }

    @Test
    fun clearedAndLockedStatesAreIconsInsteadOfStatusText() {
        assertTrue("Icons.Filled.CheckCircle" in source)
        assertTrue("Icons.Filled.Lock" in source)
        assertTrue("standard-ai-state-" in source)
        assertTrue("R.string.standard_ai_opponent_unknown" in source)
        assertFalse("statusRes" in source)
        assertFalse("standard_ai_opponent_status_next" in source)
        assertFalse("standard_ai_opponent_status_selected" in source)
        assertFalse("standard_ai_opponent_status_available" in source)
    }

    @Test
    fun opponentGalleryDoesNotReferenceAdvancedUiOrSettings() {
        assertFalse("Advanced" in source)
        assertFalse("AppDestination" in source)
        assertFalse("EdaxDataManager" in source)
    }
}
