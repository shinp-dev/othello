package com.example.othello

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

class StandardContentModelsTest {
    @Test
    fun parsesVersionedIndexAndFlexibleCardMetadata() {
        val index = parseStandardContentIndex(
            """
            {
              "schemaVersion": 1,
              "packs": [
                {
                  "id": "books",
                  "version": 5,
                  "url": "https://chanriva.shinp-studio.com/content/books-v5.zip",
                  "sizeBytes": 12345,
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("books", index.packs.single().id)
        assertEquals(5, index.packs.single().version)

        val cards = parseStandardContentCards(
            """
            {
              "schemaVersion": 1,
              "cards": [
                {
                  "id": "book.001",
                  "type": "book",
                  "rarity": "rare",
                  "title": "An Othello Book",
                  "summary": "A sample book card",
                  "seriesId": "books.basic",
                  "imagePath": "assets/book_001.webp",
                  "tags": ["book", "opening"],
                  "attributes": {
                    "author": "Sample Author",
                    "publisher": "Sample Publisher"
                  },
                  "sourceLabel": "Publisher",
                  "sourceUrl": "https://example.com/source",
                  "externalUrl": "https://example.com/book",
                  "sortOrder": 10
                }
              ]
            }
            """.trimIndent(),
        )

        val card = cards.single()
        assertEquals("book.001", card.id)
        assertEquals(StandardContentCardType.BOOK, card.type)
        assertEquals(StandardContentRarity.RARE, card.rarity)
        assertEquals("Sample Author", card.attributes["author"])
        assertEquals("assets/book_001.webp", card.imagePath)
        assertNull(card.body)
    }

    @Test
    fun rejectsUnsafeOrAmbiguousContentDefinitions() {
        assertFailsWith<StandardContentFormatException> {
            parseStandardContentIndex(
                """
                {
                  "schemaVersion": 1,
                  "packs": [{
                    "id": "books",
                    "version": 1,
                    "url": "http://example.com/books.zip",
                    "sizeBytes": 100,
                    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                  }]
                }
                """.trimIndent(),
            )
        }

        assertFailsWith<StandardContentFormatException> {
            parseStandardContentCards(
                """
                {
                  "schemaVersion": 1,
                  "cards": [
                    {
                      "id": "trivia.001",
                      "type": "trivia",
                      "title": "A",
                      "summary": "A",
                      "imagePath": "../secret.webp"
                    },
                    {
                      "id": "trivia.001",
                      "type": "trivia",
                      "title": "B",
                      "summary": "B"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun rejectsUnknownSchemaInsteadOfGuessing() {
        assertFailsWith<StandardContentFormatException> {
            parseStandardContentPackManifest(
                """{"schemaVersion":2,"id":"trivia","version":1}""",
            )
        }
    }
}
