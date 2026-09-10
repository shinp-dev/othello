package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StandardBundledStarterContentTest {
    private val root = File("src/main/assets/standard-content/bootstrap")

    @Test
    fun starterPacksUseTheSameSchemaAndStableIdsAsRemoteContent() {
        val packDirectories = root.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .sortedBy(File::getName)

        assertEquals(listOf("books", "trivia"), packDirectories.map(File::getName))

        val allCards = packDirectories.flatMap { directory ->
            val manifest = parseStandardContentPackManifest(
                File(directory, "manifest.json").readText(),
            )
            assertEquals(directory.name, manifest.id)
            assertEquals(1, manifest.version)

            parseStandardContentCards(File(directory, "cards.json").readText())
        }

        assertEquals(allCards.size, allCards.map { it.id }.toSet().size)
        assertTrue(allCards.any { it.type == StandardContentCardType.TRIVIA })
        assertTrue(allCards.any { it.type == StandardContentCardType.HISTORY })
        assertTrue(allCards.any { it.type == StandardContentCardType.BOOK })
        assertTrue(allCards.all { it.sourceLabel != null && it.sourceUrl != null })
    }

    @Test
    fun starterBooksDoNotBundlePublisherCoverArtWithoutPermission() {
        val books = parseStandardContentCards(
            File(root, "books/cards.json").readText(),
        )

        assertTrue(books.isNotEmpty())
        assertTrue(books.all { it.imagePath == null })
        assertTrue(books.all { it.attributes["author"].isNullOrBlank().not() })
        assertTrue(books.all { it.attributes["publisher"].isNullOrBlank().not() })
    }
}
