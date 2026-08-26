package com.example.othello

import android.content.Context
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.theory.TheoryAnalysisCache
import com.example.othello.theory.TheoryAnalysisCacheJson
import com.example.othello.theory.TheoryAnalysisCacheKey
import com.example.othello.theory.TheoryAnalysisCacheWriteToken
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Build-scoped, capacity-based disk LRU dedicated to full legal-move Edax results. */
class JsonFileTheoryAnalysisCache internal constructor(
    private val directory: File,
    private val buildVersion: Int,
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    private val mutex: Mutex = Mutex(),
) : TheoryAnalysisCache {
    private var accessClockInitialized = false
    private var lastAccessTimestamp = 0L
    private val writeOwners = mutableMapOf<String, TheoryAnalysisCacheWriteToken>()

    constructor(context: Context) : this(
        directory = File(context.applicationContext.cacheDir, "theory-analysis-v1"),
        buildVersion = BuildConfig.VERSION_CODE,
    )

    init {
        require(maximumBytes > 0L) { "theory analysis cache capacity must be positive" }
    }

    override suspend fun get(key: TheoryAnalysisCacheKey): AnalysisResult? = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureBuildVersion()
            val file = entryFile(key)
            if (!file.isFile) return@withContext null
            runCatching { TheoryAnalysisCacheJson.decode(file.readText(), key) }
                .onSuccess { touch(file) }
                .getOrElse {
                    file.delete()
                    writeOwners.remove(key.fileId())
                    null
                }
        }
    }

    override suspend fun put(
        key: TheoryAnalysisCacheKey,
        result: AnalysisResult,
    ): TheoryAnalysisCacheWriteToken? = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureBuildVersion()
            if (!result.available) return@withContext null
            val fileId = key.fileId()
            val destination = entryFile(key)
            writeAtomically(destination, TheoryAnalysisCacheJson.encode(key, result))
            if (destination.length() > maximumBytes) {
                destination.delete()
                writeOwners.remove(fileId)
                return@withContext null
            }
            val token = TheoryAnalysisCacheWriteToken(UUID.randomUUID().toString())
            writeOwners[fileId] = token
            touch(destination)
            pruneToCapacity()
            if (destination.isFile) {
                token
            } else {
                writeOwners.remove(fileId, token)
                null
            }
        }
    }

    override suspend fun remove(key: TheoryAnalysisCacheKey) = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureBuildVersion()
            entryFile(key).delete()
            writeOwners.remove(key.fileId())
            Unit
        }
    }

    override suspend fun removeIfOwned(
        key: TheoryAnalysisCacheKey,
        token: TheoryAnalysisCacheWriteToken,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureBuildVersion()
            val fileId = key.fileId()
            if (writeOwners[fileId] == token) {
                entryFile(key).delete()
                writeOwners.remove(fileId)
            }
        }
    }

    override suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureBuildVersion()
            entryFiles().forEach(File::delete)
            writeOwners.clear()
        }
    }

    internal suspend fun entryCount(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureBuildVersion()
            entryFiles().size
        }
    }

    internal suspend fun sizeBytes(): Long = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureBuildVersion()
            entryFiles().sumOf(File::length)
        }
    }

    private fun ensureBuildVersion() {
        directory.mkdirs()
        val marker = File(directory, VERSION_MARKER_FILE)
        val expected = "$CACHE_SCHEMA_VERSION:$buildVersion"
        if (!marker.isFile || runCatching { marker.readText() }.getOrNull() != expected) {
            directory.listFiles().orEmpty().forEach { child ->
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
            directory.mkdirs()
            writeAtomically(marker, expected)
            accessClockInitialized = false
            writeOwners.clear()
        }
        directory.listFiles { file -> file.name.endsWith(TEMPORARY_SUFFIX) }
            .orEmpty()
            .forEach(File::delete)
        if (!accessClockInitialized) {
            lastAccessTimestamp = entryFiles().maxOfOrNull(File::lastModified) ?: 0L
            accessClockInitialized = true
        }
    }

    private fun touch(file: File) {
        val timestamp = maxOf(now(), lastAccessTimestamp + 1L)
        if (file.setLastModified(timestamp)) lastAccessTimestamp = timestamp
    }

    private fun pruneToCapacity() {
        val oldestFirst = entryFiles().sortedWith(compareBy(File::lastModified, File::getName))
        var total = oldestFirst.sumOf(File::length)
        for (entry in oldestFirst) {
            if (total <= maximumBytes) break
            val size = entry.length()
            if (entry.delete()) {
                total -= size
                writeOwners.remove(entry.name.removeSuffix(ENTRY_SUFFIX))
            }
        }
    }

    private fun entryFile(key: TheoryAnalysisCacheKey): File = File(directory, "${key.fileId()}$ENTRY_SUFFIX")

    private fun entryFiles(): List<File> = directory.listFiles { file ->
        file.isFile && ENTRY_FILE.matches(file.name)
    }.orEmpty().toList()

    private fun writeAtomically(destination: File, contents: String) {
        directory.mkdirs()
        val temporary = File(directory, ".${destination.name}$TEMPORARY_SUFFIX")
        temporary.writeText(contents)
        runCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_BYTES: Long = 100L * 1024L * 1024L
        private const val CACHE_SCHEMA_VERSION = 1
        private const val VERSION_MARKER_FILE = ".build-version"
        private const val ENTRY_SUFFIX = ".json"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private val ENTRY_FILE = Regex("[0-9a-f]{64}\\.json")
    }
}
