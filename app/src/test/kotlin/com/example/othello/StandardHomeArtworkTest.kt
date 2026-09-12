package com.example.othello

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class StandardHomeArtworkTest {
    @Test
    fun wideArtworkIsDecodableAndThreeToOne() {
        listOf(
            "standard_home_winning_tips_art.jpg",
            "standard_home_real_event_photo.jpg",
        ).forEach { assetName ->
            val asset = File("src/main/res/drawable-nodpi/$assetName")
            assertTrue(asset.isFile, "Missing $assetName")

            val image = assertNotNull(ImageIO.read(asset), "$assetName must be decodable")
            assertEquals(900, image.width, "$assetName width")
            assertEquals(300, image.height, "$assetName height")
        }
    }

    @Test
    fun miniArtworkIsDecodableSquareAndTransparent() {
        listOf(
            "standard_home_gacha_icon.png",
            "standard_home_collection_icon.png",
        ).forEach { assetName ->
            val asset = File("src/main/res/drawable-nodpi/$assetName")
            assertTrue(asset.isFile, "Missing $assetName")

            val image = assertNotNull(ImageIO.read(asset), "$assetName must be decodable")
            assertEquals(320, image.width, "$assetName width")
            assertEquals(320, image.height, "$assetName height")
            assertTrue(image.colorModel.hasAlpha(), "$assetName must preserve transparency")
            assertEquals(0, image.getRGB(0, 0).ushr(24), "$assetName corners should be transparent")
        }
    }
}
