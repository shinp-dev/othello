package com.example.othello

import java.net.URI
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandardOfficialOthelloBlocksTest {
    @Test
    fun officialBlockLinksCoverAllFifteenAssociationBlocks() {
        assertEquals(15, STANDARD_OFFICIAL_OTHELLO_BLOCKS.size)
        assertEquals(15, STANDARD_OFFICIAL_OTHELLO_BLOCKS.map { it.url }.toSet().size)
        assertTrue(
            STANDARD_OFFICIAL_OTHELLO_BLOCKS.all { block ->
                val uri = URI(block.url)
                uri.scheme == "https" && uri.host == "www.othello.gr.jp" && uri.path.isNotBlank()
            },
        )
    }
}
