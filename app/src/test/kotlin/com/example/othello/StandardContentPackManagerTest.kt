package com.example.othello

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StandardContentPackManagerTest {
    @Test
    fun installsVerifiedPackAndSwitchesOnlyAfterValidation() = runBlocking {
        val root = Files.createTempDirectory("standard-content").toFile()
        try {
            val v1 = File(root, "source-v1.zip")
            writePackZip(v1, id = "trivia", version = 1, cardId = "trivia.001", title = "First")
            val manager = manager(root, v1)

            manager.refresh(indexOf(descriptorFor(v1, "trivia", 1)))

            val installed = assertNotNull(manager.loadActivePack("trivia"))
            assertEquals(1, installed.manifest.version)
            assertEquals("trivia.001", installed.cards.single().id)
            assertTrue(File(installed.directory, "assets/card.webp").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun badUpdateNeverReplacesPreviouslyActivePack() {
        val root = Files.createTempDirectory("standard-content").toFile()
        try {
            val v1 = File(root, "source-v1.zip")
            writePackZip(v1, id = "books", version = 1, cardId = "book.001", title = "V1")
            val managerV1 = manager(root, v1)
            runBlocking {
                managerV1.refresh(indexOf(descriptorFor(v1, "books", 1)))
            }

            val v2 = File(root, "source-v2.zip")
            writePackZip(v2, id = "books", version = 2, cardId = "book.001", title = "V2")
            val badDescriptor = descriptorFor(v2, "books", 2).copy(
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            )
            val managerV2 = manager(root, v2)

            assertFailsWith<IOException> {
                runBlocking { managerV2.refresh(indexOf(badDescriptor)) }
            }

            val stillActive = assertNotNull(managerV2.loadActivePack("books"))
            assertEquals(1, stillActive.manifest.version)
            assertEquals("V1", stillActive.cards.single().title)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveTraversalBeforePublishingPack() {
        val root = Files.createTempDirectory("standard-content").toFile()
        try {
            val malicious = File(root, "malicious.zip")
            ZipOutputStream(FileOutputStream(malicious)).use { zip ->
                addZipEntry(zip, "../escape.txt", "nope")
                addZipEntry(zip, "manifest.json", """{"schemaVersion":1,"id":"trivia","version":1}""")
                addZipEntry(zip, "cards.json", """{"schemaVersion":1,"cards":[]}""")
            }
            val manager = manager(root, malicious)

            assertFailsWith<IOException> {
                runBlocking {
                    manager.refresh(indexOf(descriptorFor(malicious, "trivia", 1)))
                }
            }

            assertTrue(manager.activePackIds().isEmpty())
            assertFalse(File(root.parentFile, "escape.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun packVersionChangesDoNotTouchObtainedCardIds() = runBlocking {
        val root = Files.createTempDirectory("standard-content").toFile()
        try {
            val collectionPreferences = MemoryCollectionPreferences()
            val collection = StandardCollectionStore(collectionPreferences)

            val v1 = File(root, "books-v1.zip")
            writePackZip(v1, id = "books", version = 1, cardId = "book.003", title = "Old text")
            manager(root, v1).refresh(indexOf(descriptorFor(v1, "books", 1)))
            collection.markObtained("user", "book.003")

            val v2 = File(root, "books-v2.zip")
            writePackZip(v2, id = "books", version = 2, cardId = "book.003", title = "Corrected text")
            val managerV2 = manager(root, v2)
            managerV2.refresh(indexOf(descriptorFor(v2, "books", 2)))

            assertEquals(2, assertNotNull(managerV2.loadActivePack("books")).manifest.version)
            assertEquals("Corrected text", managerV2.loadActivePack("books")!!.cards.single().title)
            assertEquals(setOf("book.003"), collection.obtainedCardIds("user"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bundledBaselineInstallsWithoutNetworkAndNeverDowngradesNewerActivePack() = runBlocking {
        val root = Files.createTempDirectory("standard-content").toFile()
        try {
            val remoteV2 = File(root, "trivia-v2.zip")
            writePackZip(
                remoteV2,
                id = "trivia",
                version = 2,
                cardId = "trivia.001",
                title = "Remote V2",
            )
            val manager = manager(root, remoteV2)

            val bundledV1 = StandardBundledContentPack(
                manifestJson = """{"schemaVersion":1,"id":"trivia","version":1}""",
                cardsJson = """
                    {
                      "schemaVersion": 1,
                      "cards": [{
                        "id": "trivia.001",
                        "type": "trivia",
                        "title": "Bundled V1",
                        "summary": "starter"
                      }]
                    }
                """.trimIndent(),
            )

            assertTrue(manager.installBundledBaseline(bundledV1))
            assertEquals(1, assertNotNull(manager.loadActivePack("trivia")).manifest.version)
            assertEquals("Bundled V1", manager.loadActivePack("trivia")!!.cards.single().title)

            manager.refresh(indexOf(descriptorFor(remoteV2, "trivia", 2)))
            assertEquals(2, assertNotNull(manager.loadActivePack("trivia")).manifest.version)
            assertEquals("Remote V2", manager.loadActivePack("trivia")!!.cards.single().title)

            assertFalse(manager.installBundledBaseline(bundledV1))
            assertEquals(2, manager.loadActivePack("trivia")!!.manifest.version)
            assertEquals("Remote V2", manager.loadActivePack("trivia")!!.cards.single().title)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bundledBaselineUsesTheSameCardValidationAsRemotePacks() {
        val root = Files.createTempDirectory("standard-content").toFile()
        try {
            val placeholder = File(root, "unused.zip").apply { writeText("unused") }
            val manager = manager(root, placeholder)
            val invalid = StandardBundledContentPack(
                manifestJson = """{"schemaVersion":1,"id":"books","version":1}""",
                cardsJson = """
                    {
                      "schemaVersion": 1,
                      "cards": [{
                        "id": "book.001",
                        "type": "book",
                        "title": "Broken",
                        "summary": "missing image",
                        "imagePath": "assets/missing.webp"
                      }]
                    }
                """.trimIndent(),
            )

            assertFailsWith<StandardContentFormatException> {
                manager.installBundledBaseline(invalid)
            }
            assertTrue(manager.activePackIds().isEmpty())
            Unit
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repositoryRejectsDuplicateCardIdsAcrossActivePacks() = runBlocking {
        val root = Files.createTempDirectory("standard-content").toFile()
        try {
            val first = File(root, "first.zip")
            val second = File(root, "second.zip")
            writePackZip(first, id = "trivia", version = 1, cardId = "shared.001", title = "Trivia")
            writePackZip(second, id = "books", version = 1, cardId = "shared.001", title = "Book")
            val sources = mapOf(
                "https://example.com/trivia.zip" to first,
                "https://example.com/books.zip" to second,
            )
            val downloader = StandardContentPackDownloader { url, destination, maxBytes ->
                val source = requireNotNull(sources[url])
                require(source.length() <= maxBytes)
                source.copyTo(destination, overwrite = true)
                destination.length()
            }
            val manager = StandardContentPackManager(File(root, "installed"), downloader)
            val triviaDescriptor = descriptorFor(first, "trivia", 1).copy(url = "https://example.com/trivia.zip")
            val booksDescriptor = descriptorFor(second, "books", 1).copy(url = "https://example.com/books.zip")
            manager.refresh(
                StandardContentIndex(
                    schemaVersion = 1,
                    packs = listOf(triviaDescriptor, booksDescriptor),
                ),
            )

            assertFailsWith<StandardContentFormatException> {
                StandardContentRepository(manager).snapshot()
            }
            Unit
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manager(root: File, source: File): StandardContentPackManager {
        val downloader = StandardContentPackDownloader { _, destination, maxBytes ->
            require(source.length() <= maxBytes)
            source.copyTo(destination, overwrite = true)
            destination.length()
        }
        return StandardContentPackManager(File(root, "installed"), downloader)
    }

    private fun indexOf(descriptor: StandardContentPackDescriptor) =
        StandardContentIndex(
            schemaVersion = STANDARD_CONTENT_SCHEMA_VERSION,
            packs = listOf(descriptor),
        )

    private fun descriptorFor(
        archive: File,
        id: String,
        version: Int,
    ) = StandardContentPackDescriptor(
        id = id,
        version = version,
        url = "https://example.com/$id-v$version.zip",
        sizeBytes = archive.length(),
        sha256 = testSha256(archive),
    )

    private fun writePackZip(
        destination: File,
        id: String,
        version: Int,
        cardId: String,
        title: String,
    ) {
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            addZipEntry(
                zip,
                "manifest.json",
                """{"schemaVersion":1,"id":"$id","version":$version}""",
            )
            addZipEntry(
                zip,
                "cards.json",
                """
                {
                  "schemaVersion": 1,
                  "cards": [{
                    "id": "$cardId",
                    "type": "trivia",
                    "title": "$title",
                    "summary": "summary",
                    "imagePath": "assets/card.webp"
                  }]
                }
                """.trimIndent(),
            )
            addZipEntry(zip, "assets/card.webp", "fake-webp-for-unit-test")
        }
    }

    private fun addZipEntry(zip: ZipOutputStream, name: String, body: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun testSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private class MemoryCollectionPreferences : StandardCollectionPreferences {
        private val values = mutableMapOf<String, Set<String>>()

        override fun getStringSet(key: String): Set<String>? = values[key]?.toSet()

        override fun putStringSet(key: String, value: Set<String>) {
            values[key] = value.toSet()
        }
    }
}
