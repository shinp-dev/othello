package com.example.othello

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class OpponentPackRepositoryTest {
    private val baseline = File("src/main/assets/opponents/animal-v1.zip").readBytes()
    private val lionBaseline = File("src/main/assets/opponents/lione-boss-v1.zip").readBytes()
    private val baselineIndex = File("src/main/assets/opponents/index.json").readText()

    @Test fun cleanInstallOfflineRetainsBothChallengeEntrances() = fixture { repo, remote, _ ->
        remote.failure = true
        assertEquals(listOf("animal", "lione-boss"), repo.prepareForEntry().visiblePacks().map { it.definition.id })
    }

    @Test fun corruptDownloadedCatalogCanStillUseBundledBaseline() = fixture { repo, remote, root ->
        repo.prepareForEntry()
        File(root, "packs").walkTopDown().first { it.name == "manifest.json" }.writeText("broken")
        remote.failure = true
        assertEquals(listOf("animal", "lione-boss"), repository(root, remote).prepareForEntry().visiblePacks().map { it.definition.id })
    }

    @Test fun oversizedExpandedEntryCannotPromoteItsCatalog() = fixture { repo, remote, _ ->
        val payload = ByteArrayOutputStream()
        ZipOutputStream(payload).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(animalManifest().replace("\"version\": 1", "\"version\": 2").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/huge.png"))
            zip.write(ByteArray(OPPONENT_MAX_ASSET_BYTES.toInt() + 1))
            zip.closeEntry()
        }
        remote.archives = mapOf("animal" to payload.toByteArray())
        assertEquals(1, repo.prepareForEntry().packs.single { it.definition.id == "animal" }.definition.version)
    }

    @Test fun corruptPackRecoveryDoesNotAllowRollbackOfTheSavedIndex() = fixture { repo, remote, root ->
        remote.archives = mapOf("animal" to archive(version = 2))
        repo.prepareForEntry()
        val previousIndex = File(root, "active.json").readBytes()
        File(root, "packs").walkTopDown().first { it.name == "manifest.json" }.writeText("broken")
        remote.archives = mapOf("animal" to baseline)
        assertEquals(1, repository(root, remote).prepareForEntry().packs.single { it.definition.id == "animal" }.definition.version)
        assertContentEquals(previousIndex, File(root, "active.json").readBytes())
    }

    @Test fun exactSizeButWrongHashCannotPublish() = fixture { repo, remote, root ->
        repo.prepareForEntry()
        val previousIndex = File(root, "active.json").readBytes()
        remote.archives = mapOf("animal" to archive(version = 2))
        remote.corruptHash = true
        assertEquals(1, repo.prepareForEntry().packs.single().definition.version)
        assertContentEquals(previousIndex, File(root, "active.json").readBytes())
    }

    @Test fun offlineExpiredCatalogKeepsTheBaselineEntrance() = fixture { _, remote, root ->
        remote.archives = mapOf("seasonal" to archive(id = "seasonal", transform = {
            it["availableUntil"] = JsonPrimitive("2000-01-01T00:00:00Z")
        }))
        // Recreate an installed catalog that was valid before its availability window ended.
        val descriptor = remote.descriptors().single()
        val directory = File(root, "packs/seasonal/1-${descriptor.sha256}").apply { mkdirs() }
        java.util.zip.ZipInputStream(remote.archives.getValue("seasonal").inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                File(directory, entry.name).apply { parentFile!!.mkdirs(); writeBytes(zip.readBytes()) }
            }
        }
        File(root, "active.json").writeText(remote.index())
        remote.failure = true
        assertEquals(
            listOf("animal", "lione-boss"),
            repository(root, remote).prepareForEntry().visiblePacks().map { it.definition.id },
        )
    }

    @Test fun supportsMultiplePacksAndSkipsInstalledVersionsAcrossProcessRecreation() = fixture { repo, remote, root ->
        remote.archives = mapOf("animal" to archive(version = 2), "beginner" to archive(id = "beginner"))
        val ready = repo.prepareForEntry()
        assertEquals(listOf("animal", "beginner"), ready.visiblePacks().map { it.definition.id })
        assertEquals(2, remote.downloads)
        repository(root, remote).prepareForEntry()
        assertEquals(2, remote.downloads)
    }

    @Test fun failureOfSecondPackDoesNotPromoteAnyOfTheNewCatalog() = fixture { repo, remote, root ->
        remote.archives = mapOf("animal" to archive(version = 2))
        repo.prepareForEntry()
        val before = File(root, "active.json").readBytes()
        remote.archives = mapOf("animal" to archive(version = 3), "beginner" to archive(id = "beginner"))
        remote.badDownload = "beginner"
        assertEquals(2, repo.prepareForEntry().packs.single().definition.version)
        assertContentEquals(before, File(root, "active.json").readBytes())
        remote.failure = true
        assertEquals(2, repository(root, remote).prepareForEntry().packs.single().definition.version)
    }

    @Test fun badHashRollbackAndMutatingTheSameVersionKeepLastGoodPack() = fixture { repo, remote, root ->
        remote.archives = mapOf("animal" to archive(version = 2))
        repo.prepareForEntry()
        remote.archives = mapOf("animal" to archive(version = 3))
        remote.badDownload = "animal"
        assertEquals(2, repo.prepareForEntry().packs.single().definition.version)
        remote.badDownload = null
        remote.archives = mapOf("animal" to baseline)
        assertEquals(2, repo.prepareForEntry().packs.single().definition.version)
        remote.archives = mapOf("animal" to archive(version = 2, transform = { it["title"] = buildJsonObject { put("en", "Changed") } }))
        assertEquals(2, repo.prepareForEntry().packs.single().definition.version)
        assertFalse(File(root, "active.json").readText().contains(remote.descriptors().single().sha256))
    }

    @Test fun unsafePathsMissingAssetsAndInvalidPoliciesCannotReplaceCurrentPack() = fixture { repo, remote, _ ->
        val invalid = listOf(
            archive(version = 2, extraPath = "../escape.txt"),
            archive(version = 2, omitAsset = true),
            archive(version = 2, transform = { it["code"] = JsonPrimitive("execute") }),
            archive(version = 2, extraPath = "assets/unreferenced.png"),
        )
        invalid.forEach { bytes ->
            remote.archives = mapOf("animal" to bytes)
            assertEquals(1, repo.prepareForEntry().packs.single { it.definition.id == "animal" }.definition.version)
        }
    }

    @Test fun cancellationDoesNotPublishCatalogAndRetrySucceeds() = fixture { repo, remote, root ->
        remote.archives = mapOf("animal" to archive(version = 2))
        remote.cancel = true
        assertFailsWith<CancellationException> { repo.prepareForEntry() }
        assertFalse(File(root, "active.json").exists())
        remote.cancel = false
        assertEquals(2, repo.prepareForEntry().packs.single().definition.version)
        assertTrue(root.listFiles()!!.none { it.name.startsWith("opponent-staging-") || it.extension == "zip" })
    }

    private fun fixture(block: suspend (OpponentPackRepository, FakeRemote, File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("opponent-repository-test").toFile()
        try {
            val remote = FakeRemote()
            block(repository(root, remote), remote, root)
        } finally { root.deleteRecursively() }
    }

    private fun repository(root: File, remote: FakeRemote) = OpponentPackRepository(root,
        { baselineIndex }, { descriptor -> (if (descriptor.id == "lione-boss") lionBaseline else baseline).inputStream() },
        remote, validateImage = { require(it.length() > 0) })

    private fun archive(id: String = "animal", version: Int = 1, extraPath: String? = null, omitAsset: Boolean = false,
        transform: (MutableMap<String, JsonElement>) -> Unit = {},
    ): ByteArray {
        val manifest = Json.parseToJsonElement(animalManifest()).jsonObject.toMutableMap().apply {
            this["id"] = JsonPrimitive(id); this["version"] = JsonPrimitive(version); transform(this)
        }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, raw: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(raw); zip.closeEntry() }
            entry("manifest.json", JsonObject(manifest).toString().toByteArray())
            ZipFile(File("src/main/assets/opponents/animal-v1.zip")).use { source ->
                source.entries().asSequence().filter { it.name != "manifest.json" && (!omitAsset || it.name != "assets/banner.png") }.forEach {
                    entry(it.name, source.getInputStream(it).use { input -> input.readBytes() })
                }
            }
            extraPath?.let { entry(it, byteArrayOf(1)) }
        }
        return bytes.toByteArray()
    }

    private inner class FakeRemote : OpponentPackTransport {
        var archives = mapOf("animal" to baseline)
        var failure = false
        var cancel = false
        var badDownload: String? = null
        var corruptHash = false
        var downloads = 0
        fun descriptors() = archives.map { (id, raw) ->
            val manifest = java.util.zip.ZipInputStream(raw.inputStream()).use { zip -> zip.nextEntry; zip.readBytes().toString(Charsets.UTF_8) }
            val version = Json.parseToJsonElement(manifest).jsonObject.getValue("version").jsonPrimitive.int
            OpponentPackDescriptor(id, version, "https://example.com/$id.zip", raw.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        override suspend fun index(): String {
            if (failure) error("offline")
            return buildJsonObject {
                put("schemaVersion", 1)
                put("packs", JsonArray(descriptors().map { d -> buildJsonObject {
                    put("id", d.id); put("version", d.version); put("url", d.url); put("sizeBytes", d.sizeBytes); put("sha256", d.sha256)
                } }))
            }.toString()
        }
        override suspend fun download(descriptor: OpponentPackDescriptor, destination: File) {
            if (cancel) throw CancellationException()
            downloads++
            val bytes = if (badDownload == descriptor.id) byteArrayOf(1, 2) else archives.getValue(descriptor.id).copyOf()
            if (corruptHash) bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            destination.writeBytes(bytes)
        }
    }
}
