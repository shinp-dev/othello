package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandardOpponentGalleryUiContractTest {
    private val source = File("src/main/kotlin/com/example/othello/StandardOpponentGallery.kt").readText()

    @Test
    fun opponentGalleryUsesTwoColumnThumbnailCards() {
        assertTrue("private const val OPPONENTS_PER_ROW = 2" in source)
        assertTrue("Image(" in source)
        assertTrue("painterResource(opponent.winDrawableRes)" in source)
        assertTrue("Card(" in source)
        assertTrue("enabled = unlocked" in source)
        assertTrue("standard-ai-opponent-" in source)
    }

    @Test
    fun opponentGallerySeparatesFriendlyAndWildGroups() {
        assertTrue("R.string.standard_ai_group_basic" in source)
        assertTrue("R.string.standard_ai_group_serious" in source)
        assertTrue("pack.opponents.take(4)" in source)
        assertTrue("pack.opponents.drop(4)" in source)
    }

    @Test
    fun opponentGalleryDoesNotReferenceAdvancedUiOrSettings() {
        assertFalse("Advanced" in source)
        assertFalse("AppDestination" in source)
        assertFalse("EdaxDataManager" in source)
    }
}
