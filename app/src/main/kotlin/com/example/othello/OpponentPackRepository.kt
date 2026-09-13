package com.example.othello

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface OpponentPackTransport {
    suspend fun index(): String
    suspend fun download(descriptor: OpponentPackDescriptor, destination: File)
}

/** Separate catalog and storage from card packs. Only validated immutable snapshots leave here. */
internal class OpponentPackRepository(
    private val root: File,
    private val baselineIndex: () -> String,
    private val baselineArchive: (OpponentPackDescriptor) -> InputStream,
    private val transport: OpponentPackTransport,
    private val validateImage: (File) -> Unit = ::validateOpponentImage,
) {
    private val mutex = Mutex()
    private var bundledSnapshot: OpponentPackSnapshot? = null

    suspend fun prepareForEntry(): OpponentPackSnapshot = mutex.withLock {
        require(root.mkdirs() || root.isDirectory)
        val bundled = parseOpponentIndex(baselineIndex())
        val previous = runCatching { parseOpponentIndex(readBounded(File(root, "active.json"), 128 * 1024)) }.getOrNull()
        // Recover from incomplete/corrupt local state without removing it or resetting user data.
        var current = previous?.let { descriptors -> runCatching { snapshot(descriptors) }.getOrNull() }
        var active = previous
        if (current == null) {
            current = baseline(bundled)
            // Recovery must not lower the version watermark from a valid previous index.
            active = previous ?: bundled
        }
        try {
            val raw = transport.index()
            val incoming = parseOpponentIndex(raw)
            val oldById = requireNotNull(active).associateBy { it.id }
            incoming.forEach { descriptor ->
                val old = oldById[descriptor.id]
                require(old == null || descriptor.version >= old.version) { "Opponent rollback refused" }
                require(old == null || descriptor.version != old.version || descriptor.sha256 == old.sha256) { "Opponent version is immutable" }
                install(descriptor) { file ->
                    if (bundled.any { it.id == descriptor.id && it.sha256 == descriptor.sha256 }) {
                        baselineArchive(descriptor).use { copyLimited(it, file, descriptor.sizeBytes) }
                    } else transport.download(descriptor, file)
                }
            }
            val updated = snapshot(incoming) // All packs must validate before changing the active catalog.
            require(updated.visiblePacks().isNotEmpty()) { "Catalog must provide an available opponent pack" }
            currentCoroutineContext().ensureActive()
            atomicWrite(File(root, "active.json"), raw.toByteArray())
            current = updated
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preserve the last usable catalog on any transport, validation, or promotion failure.
        }
        // A cached seasonal catalog may expire while offline. Keep the baseline entrance available.
        if (requireNotNull(current).visiblePacks().isEmpty()) {
            current = baseline(bundled)
        }
        requireNotNull(current)
    }

    private suspend fun baseline(descriptors: List<OpponentPackDescriptor>): OpponentPackSnapshot {
        bundledSnapshot?.let { return it }
        // Independent recovery copy: corrupt downloaded version directories cannot hide the APK baseline.
        val packs = descriptors.map { d ->
            val target = File(root, "baseline/${d.id}/${d.sha256}")
            val usable = if (target.exists() && runCatching { load(target, d) }.isFailure) {
                File(root, "baseline/${d.id}/${d.sha256}-${java.util.UUID.randomUUID()}")
            } else target
            install(d, usable) { file -> baselineArchive(d).use { copyLimited(it, file, d.sizeBytes) } }
            load(usable, d)
        }
        return OpponentPackSnapshot(packs).also { bundledSnapshot = it }
    }

    private fun snapshot(descriptors: List<OpponentPackDescriptor>) = OpponentPackSnapshot(descriptors.map { descriptor ->
        load(directory(descriptor), descriptor)
    })

    private fun directory(d: OpponentPackDescriptor): File = File(root, "packs/${d.id}/${d.version}-${d.sha256}")

    private suspend fun install(d: OpponentPackDescriptor, target: File = directory(d), download: suspend (File) -> Unit) {
        if (target.isDirectory && runCatching { load(target, d) }.isSuccess) return
        val archive = File.createTempFile("opponent-", ".zip", root)
        val staging = Files.createTempDirectory(root.toPath(), "opponent-staging-").toFile()
        try {
            download(archive)
            require(archive.length() == d.sizeBytes && archive.length() <= OPPONENT_MAX_ARCHIVE_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == d.sha256) { "Opponent SHA-256 mismatch" }
            val names = mutableSetOf<String>()
            var expanded = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && names.add(entry.name) && names.size <= OPPONENT_MAX_ENTRIES)
                    if (entry.name != "manifest.json") validateOpponentAssetPath(entry.name)
                    val file = File(staging, entry.name)
                    val limit = if (entry.name == "manifest.json") OPPONENT_MAX_MANIFEST_BYTES.toLong() else OPPONENT_MAX_ASSET_BYTES
                    expanded += copyLimited(zip, file, minOf(limit, OPPONENT_MAX_EXPANDED_BYTES - expanded))
                    zip.closeEntry()
                }
            }
            val installed = load(staging, d)
            require(names == installed.definition.assetPaths + "manifest.json") { "Missing or unreferenced opponent assets" }
            // Never replace a version directory used by an existing snapshot.
            require(!target.exists()) { "Existing opponent version is corrupt; a new version is required" }
            currentCoroutineContext().ensureActive()
            target.parentFile.mkdirs()
            atomicMove(staging, target)
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun load(directory: File, d: OpponentPackDescriptor): InstalledOpponentPack {
        val definition = parseOpponentManifest(readBounded(File(directory, "manifest.json"), OPPONENT_MAX_MANIFEST_BYTES))
        require(definition.id == d.id && definition.version == d.version)
        definition.assetPaths.forEach { path ->
            val image = File(directory, path)
            require(image.isFile && image.length() in 1..OPPONENT_MAX_ASSET_BYTES)
            validateImage(image)
        }
        return InstalledOpponentPack(definition, directory)
    }
}

internal fun androidOpponentPackRepository(context: Context): OpponentPackRepository {
    val app = context.applicationContext
    return OpponentPackRepository(
        File(app.filesDir, "standard-opponents/v1"),
        { app.assets.open("opponents/index.json").bufferedReader().use { it.readText() } },
        { d -> app.assets.open("opponents/${d.id}-v${d.version}.zip") },
        UrlOpponentPackTransport(),
    )
}

private class UrlOpponentPackTransport : OpponentPackTransport {
    override suspend fun index(): String = request(OPPONENT_INDEX_URL) { input ->
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= 128 * 1024)
            bytes.write(buffer, 0, count)
        }
        bytes.toString("UTF-8")
    }
    override suspend fun download(descriptor: OpponentPackDescriptor, destination: File) {
        request(descriptor.url) { copyLimited(it, destination, descriptor.sizeBytes) }
    }
    private fun <T> request(url: String, read: (InputStream) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            require(connection.responseCode in 200..299) { "Opponent HTTP ${connection.responseCode}" }
            return connection.inputStream.use(read)
        } finally { connection.disconnect() }
    }
}

private fun copyLimited(input: InputStream, file: File, limit: Long): Long {
    file.parentFile.mkdirs()
    var size = 0L
    FileOutputStream(file).use { out ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= limit) { "Opponent data exceeded size limit" }
            out.write(buffer, 0, count)
        }
        out.fd.sync()
    }
    return size
}

private fun readBounded(file: File, limit: Int): String {
    require(file.isFile && file.length() <= limit)
    return file.readText(Charsets.UTF_8)
}

private fun validateOpponentImage(file: File) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304)
    require(bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp"))
    val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
    requireNotNull(decoded) { "Undecodable opponent image" }.recycle()
}

private fun atomicWrite(target: File, bytes: ByteArray) {
    val temp = File.createTempFile("catalog-", ".tmp", target.parentFile)
    try {
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        atomicMove(temp, target)
    } finally { temp.delete() }
}

private fun atomicMove(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
