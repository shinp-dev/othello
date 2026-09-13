package com.example.othello

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class StandardBundledContentPack(
    val manifestJson: String,
    val cardsJson: String,
    val assets: Map<String, ByteArray> = emptyMap(),
)

internal fun interface StandardBundledContentSource {
    fun load(): List<StandardBundledContentPack>
}

internal class AndroidStandardBundledContentSource(
    context: Context,
) : StandardBundledContentSource {
    private val assets = context.applicationContext.assets

    override fun load(): List<StandardBundledContentPack> {
        val packDirectories = assets.list(ROOT).orEmpty().sorted()
        return packDirectories.map { packDirectory ->
            val base = "$ROOT/$packDirectory"
            val manifest = assets.open("$base/manifest.json").bufferedReader().use { it.readText() }
            val cards = assets.open("$base/cards.json").bufferedReader().use { it.readText() }
            val assetFiles = readAssetFiles("$base/assets")
            StandardBundledContentPack(
                manifestJson = manifest,
                cardsJson = cards,
                assets = assetFiles,
            )
        }
    }

    private fun readAssetFiles(path: String): Map<String, ByteArray> {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, ByteArray>()
        fun walk(currentPath: String) {
            assets.list(currentPath).orEmpty().sorted().forEach { child ->
                val childPath = "$currentPath/$child"
                val nested = assets.list(childPath).orEmpty()
                if (nested.isNotEmpty()) {
                    walk(childPath)
                } else {
                    val relative = childPath.substringAfter("$ROOT/")
                        .substringAfter("/assets/")
                        .let { "assets/$it" }
                    validateAssetPath(relative)
                    val bytes = assets.open(childPath).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > STANDARD_CONTENT_MAX_ENTRY_BYTES) {
                                throw IOException("Bundled Standard content asset is too large")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    result[relative] = bytes
                }
            }
        }
        walk(path)
        return result
    }

    private companion object {
        const val ROOT = "standard-content/bootstrap"
    }
}

/**
 * Standard Bootstrap is the only caller. Prepare a stable snapshot before opening Home;
 * feature screens never install packs or start background refreshes. Advanced does not use this owner.
 */
internal class StandardContentProcessOwner internal constructor(
    private val packManager: StandardContentPackManager,
    private val bundledSource: StandardBundledContentSource,
    private val indexFetcher: StandardContentIndexFetcher,
) {
    constructor(context: Context) : this(
        StandardContentPackManager(context),
        AndroidStandardBundledContentSource(context),
        RemoteStandardContentIndexFetcher(
            transport = UrlConnectionStandardContentIndexTransport(),
            endpoint = REMOTE_STANDARD_CONTENT_INDEX_URL,
        ),
    )

    private val repository = StandardContentRepository(packManager)
    private val mutex = Mutex()
    private var bootstrapped = false

    private fun ensureBundledBaseline() {
        if (bootstrapped) return
        bundledSource.load().forEach(packManager::installBundledBaseline)
        bootstrapped = true
    }

    suspend fun prepareForEntry(): StandardContentSnapshot = mutex.withLock {
        ensureBundledBaseline()
        try {
            packManager.refresh(indexFetcher.fetch())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Offline/invalid updates retain installed packs and all user-owned collection data.
            // The next Standard entry checks again, including within the same process.
        }
        repository.snapshot()
    }

    private companion object {
        const val REMOTE_STANDARD_CONTENT_INDEX_URL =
            "https://raw.githubusercontent.com/shinp-dev/chanriva-content/main/index.json"
    }
}
