package com.example.othello

import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StandardContentBootstrapTest {
    @Test
    fun entryReturnsTheUpdatedPackAndAnUnchangedReentryDoesNotDownloadAgain() = runBlocking {
        val root = Files.createTempDirectory("standard-bootstrap-update").toFile()
        try {
            val update = pack(2)
            val archive = java.io.ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    mapOf("manifest.json" to update.manifestJson, "cards.json" to update.cardsJson)
                        .forEach { (name, contents) ->
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(contents.toByteArray())
                            zip.closeEntry()
                        }
                }
            }.toByteArray()
            val sha = MessageDigest.getInstance("SHA-256").digest(archive)
                .joinToString("") { "%02x".format(it) }
            val index = parseStandardContentIndex(
                """{"schemaVersion":1,"packs":[{"id":"trivia","version":2,"url":"https://example.com/trivia.zip","sha256":"$sha","sizeBytes":${archive.size}}]}""",
            )
            var downloads = 0
            var checks = 0
            val manager = StandardContentPackManager(root, StandardContentPackDownloader { _, file, _ ->
                downloads++
                file.writeBytes(archive)
                archive.size.toLong()
            })
            val owner = StandardContentProcessOwner(
                manager, StandardBundledContentSource { listOf(pack(1)) },
                StandardContentIndexFetcher { checks++; index },
            )

            repeat(2) { assertEquals("V2", owner.prepareForEntry().entries.single().card.title) }

            assertEquals(2, checks)
            assertEquals(1, downloads)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun offlineEntryInstallsBaselineAndEveryLaterEntryChecksAgainWithoutDowngrading() = runBlocking {
        val root = Files.createTempDirectory("standard-bootstrap").toFile()
        try {
            val manager = StandardContentPackManager(root, StandardContentPackDownloader { _, _, _ ->
                error("No downloads expected")
            })
            manager.installBundledBaseline(pack(version = 2))
            var checks = 0
            var bundledLoads = 0
            val owner = StandardContentProcessOwner(
                manager,
                StandardBundledContentSource { bundledLoads++; listOf(pack(version = 1)) },
                StandardContentIndexFetcher { checks++; throw IOException("offline") },
            )
            repeat(2) {
                assertEquals("V2", owner.prepareForEntry().entries.single().card.title)
            }
            assertEquals(2, checks)
            assertEquals(1, bundledLoads)
            assertEquals(2, manager.loadActivePack("trivia")!!.manifest.version)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanInstallCanEnterOfflineAndCancelledCheckCanBeRetried() = runBlocking {
        val root = Files.createTempDirectory("standard-bootstrap-cancel").toFile()
        try {
            val manager = StandardContentPackManager(root, StandardContentPackDownloader { _, _, _ ->
                error("No downloads expected")
            })
            var cancel = true
            val owner = StandardContentProcessOwner(
                manager, StandardBundledContentSource { listOf(pack(1)) },
                StandardContentIndexFetcher {
                    if (cancel) throw CancellationException()
                    throw IOException("offline")
                },
            )
            assertFailsWith<CancellationException> { owner.prepareForEntry() }
            cancel = false
            assertEquals("V1", owner.prepareForEntry().entries.single().card.title)
            assertTrue(manager.loadActivePack("trivia") != null)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pack(version: Int) = StandardBundledContentPack(
        manifestJson = """{"schemaVersion":1,"id":"trivia","version":$version}""",
        cardsJson = """{"schemaVersion":1,"cards":[{"id":"trivia.001","type":"trivia","title":"V$version","summary":"Summary","body":"Body","rarity":"common","sortOrder":1}]}""",
    )
}
