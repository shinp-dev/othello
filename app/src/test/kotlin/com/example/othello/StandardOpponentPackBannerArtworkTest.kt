package com.example.othello

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StandardOpponentPackBannerArtworkTest {
    @Test
    fun animalPackBannerIsWideAndTransparent() {
        val asset = File("src/main/res/drawable-nodpi/standard_ai_animal_pack_banner.png")
        assertTrue(asset.isFile, "Missing animal pack banner")

        val bytes = asset.readBytes()
        assertTrue(bytes.size > 32, "Animal pack banner is unexpectedly small")
        assertContentEquals(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            ),
            bytes.copyOfRange(0, 8),
            "Animal pack banner must be a PNG",
        )

        fun pngInt(offset: Int): Int =
            ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

        assertEquals(1086, pngInt(16))
        assertEquals(362, pngInt(20))

        val colorType = bytes[25].toInt() and 0xFF
        val hasAlphaChannel = colorType == 4 || colorType == 6
        val hasTransparencyChunk =
            bytes.toString(Charsets.ISO_8859_1).contains("tRNS")
        assertTrue(
            hasAlphaChannel || hasTransparencyChunk,
            "Animal pack banner must preserve transparency",
        )
    }
}
