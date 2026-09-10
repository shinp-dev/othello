package com.example.othello

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal data class StandardContentRefreshResult(
    val updatedPackIds: Set<String>,
    val unchangedPackIds: Set<String>,
    val deactivatedPackIds: Set<String>,
)

internal class StandardContentPackManager internal constructor(
    private val rootDirectory: File,
    private val downloader: StandardContentPackDownloader,
) {
    constructor(
        context: Context,
        downloader: StandardContentPackDownloader = UrlConnectionStandardContentPackDownloader(),
    ) : this(
        rootDirectory = File(context.applicationContext.filesDir, ROOT_DIRECTORY_NAME),
        downloader = downloader,
    )

    suspend fun refresh(index: StandardContentIndex): StandardContentRefreshResult {
        require(index.schemaVersion == STANDARD_CONTENT_SCHEMA_VERSION)
        ensureLayout()

        val updated = linkedSetOf<String>()
        val unchanged = linkedSetOf<String>()
        index.packs.forEach { descriptor ->
            val currentVersion = activeVersion(descriptor.id)
            when {
                currentVersion == descriptor.version -> unchanged += descriptor.id
                currentVersion != null && descriptor.version < currentVersion ->
                    throw StandardContentFormatException(
                        "Refusing Standard content rollback for ${descriptor.id}: " +
                            "$currentVersion -> ${descriptor.version}",
                    )
                else -> {
                    install(descriptor)
                    updated += descriptor.id
                }
            }
        }

        val desiredPackIds = index.packs.mapTo(linkedSetOf()) { it.id }
        val deactivated = activePackIds() - desiredPackIds
        deactivated.forEach(::deactivate)

        return StandardContentRefreshResult(
            updatedPackIds = updated,
            unchangedPackIds = unchanged,
            deactivatedPackIds = deactivated,
        )
    }

    fun activePackIds(): Set<String> {
        val directory = activeDirectory()
        if (!directory.isDirectory) return emptySet()
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(ACTIVE_POINTER_SUFFIX) }
            .mapNotNull { pointer ->
                pointer.name.removeSuffix(ACTIVE_POINTER_SUFFIX)
                    .takeIf { runCatching { validatePackId(it) }.isSuccess }
            }
            .toSet()
    }

    fun loadActivePack(packId: String): StandardInstalledContentPack? {
        validatePackId(packId)
        val version = activeVersion(packId) ?: return null
        return loadInstalledPack(packDirectory(packId, version)).also { installed ->
            if (installed.manifest.id != packId || installed.manifest.version != version) {
                throw StandardContentFormatException("Active Standard content pointer does not match pack")
            }
        }
    }

    private suspend fun install(descriptor: StandardContentPackDescriptor) {
        validatePackId(descriptor.id)
        ensureLayout()
        val archive = File(downloadDirectory(), "${descriptor.id}-${descriptor.version}.zip.tmp")
        val staging = File(stagingDirectory(), "${descriptor.id}-${descriptor.version}.staging")
        archive.delete()
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("Could not clear stale Standard content staging directory")
        }

        try {
            val downloadedBytes = downloader.download(
                descriptor.url,
                archive,
                minOf(descriptor.sizeBytes, STANDARD_CONTENT_MAX_PACK_BYTES),
            )
            if (downloadedBytes != descriptor.sizeBytes || archive.length() != descriptor.sizeBytes) {
                throw IOException("Standard content pack size does not match index")
            }
            val actualSha256 = sha256(archive)
            if (!actualSha256.equals(descriptor.sha256, ignoreCase = true)) {
                throw IOException("Standard content pack SHA-256 does not match index")
            }

            extractArchive(archive, staging)
            val installed = loadInstalledPack(staging)
            if (
                installed.manifest.id != descriptor.id ||
                installed.manifest.version != descriptor.version ||
                installed.manifest.schemaVersion != STANDARD_CONTENT_SCHEMA_VERSION
            ) {
                throw StandardContentFormatException("Pack manifest does not match content index")
            }
            installed.cards.forEach { card ->
                card.imagePath?.let { relativePath ->
                    val asset = safeChild(staging, relativePath)
                    if (!asset.isFile) {
                        throw StandardContentFormatException(
                            "Card ${card.id} references a missing content asset",
                        )
                    }
                }
            }

            val target = packDirectory(descriptor.id, descriptor.version)
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Could not replace existing Standard content pack directory")
            }
            target.parentFile?.mkdirs()
            moveAtomicallyWhenPossible(staging, target, replaceExisting = false)
            writeActiveVersion(descriptor.id, descriptor.version)
        } finally {
            archive.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun extractArchive(archive: File, staging: File) {
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw IOException("Could not create Standard content staging directory")
        }
        val seenNames = mutableSetOf<String>()
        var entryCount = 0
        var totalExtractedBytes = 0L

        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > STANDARD_CONTENT_MAX_ENTRIES) {
                    throw IOException("Standard content pack has too many entries")
                }
                val name = entry.name
                validateArchiveEntryName(name, entry.isDirectory)
                if (!seenNames.add(name)) {
                    throw IOException("Standard content pack contains duplicate entries")
                }

                val destination = safeChild(staging, name)
                if (entry.isDirectory) {
                    if (!destination.mkdirs() && !destination.isDirectory) {
                        throw IOException("Could not create Standard content asset directory")
                    }
                } else {
                    destination.parentFile?.mkdirs()
                    var entryBytes = 0L
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            totalExtractedBytes += count
                            if (entryBytes > STANDARD_CONTENT_MAX_ENTRY_BYTES) {
                                throw IOException("Standard content pack entry is too large")
                            }
                            if (totalExtractedBytes > STANDARD_CONTENT_MAX_EXTRACTED_BYTES) {
                                throw IOException("Standard content pack expands beyond its safety limit")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                zip.closeEntry()
            }
        }

        if (!File(staging, MANIFEST_FILE_NAME).isFile || !File(staging, CARDS_FILE_NAME).isFile) {
            throw StandardContentFormatException("Standard content pack is missing manifest.json or cards.json")
        }
    }

    private fun loadInstalledPack(directory: File): StandardInstalledContentPack {
        if (!directory.isDirectory) throw StandardContentFormatException("Installed content pack is missing")
        val manifestFile = File(directory, MANIFEST_FILE_NAME)
        val cardsFile = File(directory, CARDS_FILE_NAME)
        if (!manifestFile.isFile || !cardsFile.isFile) {
            throw StandardContentFormatException("Installed content pack is incomplete")
        }
        if (manifestFile.length() > MAX_MANIFEST_BYTES || cardsFile.length() > MAX_CARDS_JSON_BYTES) {
            throw StandardContentFormatException("Installed content metadata exceeds safety limits")
        }
        return StandardInstalledContentPack(
            manifest = parseStandardContentPackManifest(manifestFile.readText(Charsets.UTF_8)),
            cards = parseStandardContentCards(cardsFile.readText(Charsets.UTF_8)),
            directory = directory,
        )
    }

    private fun activeVersion(packId: String): Int? {
        validatePackId(packId)
        val pointer = activePointer(packId)
        if (!pointer.isFile) return null
        if (pointer.length() > MAX_POINTER_BYTES) {
            throw StandardContentFormatException("Standard content active pointer is invalid")
        }
        return pointer.readText(Charsets.UTF_8).trim().toIntOrNull()
            ?.takeIf { it > 0 }
            ?: throw StandardContentFormatException("Standard content active pointer is invalid")
    }

    private fun writeActiveVersion(packId: String, version: Int) {
        val pointer = activePointer(packId)
        pointer.parentFile?.mkdirs()
        val temporary = File(pointer.parentFile, "${pointer.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(version.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        moveAtomicallyWhenPossible(temporary, pointer, replaceExisting = true)
    }

    private fun deactivate(packId: String) {
        validatePackId(packId)
        val pointer = activePointer(packId)
        if (pointer.exists() && !pointer.delete()) {
            throw IOException("Could not deactivate Standard content pack $packId")
        }
    }

    private fun ensureLayout() {
        listOf(
            rootDirectory,
            activeDirectory(),
            packRootDirectory(),
            downloadDirectory(),
            stagingDirectory(),
        ).forEach { directory ->
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Could not create Standard content storage")
            }
        }
    }

    private fun activeDirectory() = File(rootDirectory, "active")
    private fun packRootDirectory() = File(rootDirectory, "packs")
    private fun downloadDirectory() = File(rootDirectory, "downloads")
    private fun stagingDirectory() = File(rootDirectory, "staging")
    private fun activePointer(packId: String) = File(activeDirectory(), "$packId$ACTIVE_POINTER_SUFFIX")
    private fun packDirectory(packId: String, version: Int) =
        File(File(packRootDirectory(), packId), version.toString())

    private companion object {
        const val ROOT_DIRECTORY_NAME = "standard-content"
        const val ACTIVE_POINTER_SUFFIX = ".version"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val CARDS_FILE_NAME = "cards.json"
        const val MAX_MANIFEST_BYTES = 64L * 1024L
        const val MAX_CARDS_JSON_BYTES = 8L * 1024L * 1024L
        const val MAX_POINTER_BYTES = 32L
    }
}

private fun validatePackId(packId: String) {
    if (!Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(packId)) {
        throw StandardContentFormatException("Invalid Standard content pack id")
    }
}

private fun validateArchiveEntryName(name: String, directory: Boolean) {
    if (
        name.isBlank() ||
        name.startsWith("/") ||
        '\\' in name ||
        ':' in name ||
        '\u0000' in name ||
        name.split('/').any { it == "." || it == ".." }
    ) {
        throw IOException("Unsafe Standard content archive path")
    }
    val normalized = if (directory) name.trimEnd('/') else name
    val allowed = when {
        directory -> normalized == "assets" || normalized.startsWith("assets/")
        normalized == "manifest.json" -> true
        normalized == "cards.json" -> true
        normalized.startsWith("assets/") -> runCatching { validateAssetPath(normalized) }.isSuccess
        else -> false
    }
    if (!allowed) throw IOException("Unsupported file in Standard content pack: $name")
}

private fun safeChild(parent: File, relativePath: String): File {
    val parentPath = parent.canonicalFile.toPath()
    val child = File(parent, relativePath).canonicalFile
    if (!child.toPath().startsWith(parentPath)) {
        throw IOException("Standard content path escapes its pack directory")
    }
    return child
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private fun moveAtomicallyWhenPossible(source: File, target: File, replaceExisting: Boolean) {
    target.parentFile?.mkdirs()
    val options = if (replaceExisting) {
        arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } else {
        arrayOf(StandardCopyOption.ATOMIC_MOVE)
    }
    try {
        Files.move(source.toPath(), target.toPath(), *options)
    } catch (_: AtomicMoveNotSupportedException) {
        if (replaceExisting) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.move(source.toPath(), target.toPath())
        }
    }
}
