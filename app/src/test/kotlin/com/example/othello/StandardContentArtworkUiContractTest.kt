package com.example.othello

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class StandardContentArtworkUiContractTest {
    private val source =
        File("src/main/kotlin/com/example/othello/StandardContentArtwork.kt").readText()

    @Test
    fun everyContentTypeHasARealCategoryArtworkAsset() {
        val assets = listOf(
            "standard_content_icon_trivia.png",
            "standard_content_icon_book.webp",
            "standard_content_icon_person.webp",
            "standard_content_icon_history.webp",
            "standard_content_icon_collab.webp",
        )

        assertTrue("StandardContentCardType.TRIVIA -> R.drawable.standard_content_icon_trivia" in source)
        assertTrue("StandardContentCardType.BOOK -> R.drawable.standard_content_icon_book" in source)
        assertTrue("StandardContentCardType.PERSON -> R.drawable.standard_content_icon_person" in source)
        assertTrue("StandardContentCardType.HISTORY -> R.drawable.standard_content_icon_history" in source)
        assertTrue("StandardContentCardType.COLLAB -> R.drawable.standard_content_icon_collab" in source)

        assets.forEach { assetName ->
            val asset = File("src/main/res/drawable-nodpi/$assetName")
            assertTrue(asset.isFile, "Missing category artwork: $assetName")
            assertTrue(asset.length() > 1_000L, "Category artwork is unexpectedly small: $assetName")
        }
    }

    @Test
    fun triviaArtworkIsADecodable192PixelPng() {
        val asset = File("src/main/res/drawable-nodpi/standard_content_icon_trivia.png")
        val image = assertNotNull(ImageIO.read(asset), "Trivia artwork must be decodable")

        assertEquals(192, image.width)
        assertEquals(192, image.height)
    }

    @Test
    fun corruptCategoryArtworkFallsBackInsteadOfCrashing() {
        assertTrue("BitmapFactory.decodeResource" in source)
        assertTrue("runCatching" in source)
        assertTrue("categoryBitmap != null" in source)
        assertTrue("painterResource(entry.card.type.iconRes)" !in source)
    }

    @Test
    fun rarityOwnsTheBackgroundInsteadOfDuplicatingArtwork() {
        assertTrue("StandardContentRarity.COMMON -> StandardContentRarityVisualColors" in source)
        assertTrue("background = Color(0xFF173D32)" in source)
        assertTrue("StandardContentRarity.RARE -> StandardContentRarityVisualColors" in source)
        assertTrue("background = Color(0xFF343B45)" in source)
        assertTrue("StandardContentRarity.SPECIAL -> StandardContentRarityVisualColors" in source)
        assertTrue("background = Color(0xFF5A4315)" in source)
        assertTrue("Brush.radialGradient" in source)
    }
}
