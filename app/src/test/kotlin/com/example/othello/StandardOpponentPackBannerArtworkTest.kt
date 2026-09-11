package com.example.othello

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class StandardOpponentPackBannerArtworkTest {
    @Test
    fun animalPackBannerIsWideAndTransparent() {
        val asset = File("src/main/res/drawable-nodpi/standard_ai_animal_pack_banner.png")
        assertTrue(asset.isFile, "Missing animal pack banner")

        val image = assertNotNull(ImageIO.read(asset), "Animal pack banner must be decodable")
        assertEquals(1086, image.width)
        assertEquals(362, image.height)
        assertTrue(image.colorModel.hasAlpha(), "Animal pack banner must preserve transparency")
        assertEquals(0, image.getRGB(0, 0).ushr(24), "Banner corners should be transparent")
    }
}
