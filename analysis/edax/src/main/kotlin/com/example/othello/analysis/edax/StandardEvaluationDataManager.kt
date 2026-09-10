package com.example.othello.analysis.edax

import android.content.Context
import android.content.SharedPreferences
import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.analysis.api.StandardEvaluationDataSource
import com.example.othello.analysis.api.StandardEvaluationPreparationPhase
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val STANDARD_EVALUATION_PREFERENCES = "standard-ai-evaluation"
private const val STANDARD_EVALUATION_DIRECTORY = "analysis/edax/standard/v1"
private const val STANDARD_EVALUATION_MAX_BYTES = 13_952_436L

/** Owns the fixed Standard eval slot, separate from every Advanced eval/book slot. */
class StandardEvaluationDataManager private constructor(
    private val storageDirectory: File,
    private val metadataStore: StandardEvaluationMetadataStore,
    private val installer: StandardEvaluationInstaller,
) : StandardEvaluationDataSource {
    constructor(context: Context) : this(
        storageDirectory = File(context.applicationContext.filesDir, STANDARD_EVALUATION_DIRECTORY).apply { mkdirs() },
        metadataStore = SharedPreferencesStandardEvaluationMetadata(
            context.applicationContext.getSharedPreferences(STANDARD_EVALUATION_PREFERENCES, Context.MODE_PRIVATE),
        ),
        installer = ProductionStandardEvaluationInstaller,
    )

    internal constructor(
        storageDirectory: File,
        metadataStore: StandardEvaluationMetadataStore,
        installer: StandardEvaluationInstaller,
        createDirectory: Boolean = true,
    ) : this(
        storageDirectory = storageDirectory.apply { if (createDirectory) mkdirs() },
        metadataStore = metadataStore,
        installer = installer,
    )

    override val nativeAvailable: Boolean get() = installer.nativeAvailable

    override fun current(): StandardEvaluationAsset? {
        val metadata = metadataStore.read() ?: return null
        val file = File(metadata.path)
        val safePath = file.toPath().normalize().startsWith(storageDirectory.toPath().normalize())
        if (!safePath || !file.isFile || file.length() != metadata.sizeBytes ||
            metadata.version != EdaxReleaseConstants.EVALUATION_DATA_VERSION || !SHA256.matches(metadata.sha256)
        ) {
            metadataStore.clear()
            return null
        }
        return StandardEvaluationAsset(file.absolutePath, metadata.sha256)
    }

    override suspend fun prepare(
        onPhase: suspend (StandardEvaluationPreparationPhase) -> Unit,
    ): StandardEvaluationAsset = withContext(Dispatchers.IO) {
        current()?.let { return@withContext it }
        require(installer.nativeAvailable) { "Edax native library is unavailable" }
        storageDirectory.mkdirs()
        val archive = File.createTempFile(".standard-eval-", ".7z", storageDirectory)
        val extracted = File.createTempFile(".standard-eval-", ".dat", storageDirectory)
        try {
            onPhase(StandardEvaluationPreparationPhase.DOWNLOADING)
            installer.download(archive)
            onPhase(StandardEvaluationPreparationPhase.EXTRACTING)
            installer.extract(archive, extracted, STANDARD_EVALUATION_MAX_BYTES)
            onPhase(StandardEvaluationPreparationPhase.VALIDATING)
            require(extracted.isFile && extracted.length() in 1..STANDARD_EVALUATION_MAX_BYTES) {
                "Standard evaluation data has an invalid size"
            }
            installer.validate(extracted.absolutePath)?.let { error(it) }
            val sha256 = sha256(extracted)
            val destination = File(
                storageDirectory,
                "eval-v${EdaxReleaseConstants.EVALUATION_DATA_VERSION}-$sha256.dat",
            )
            runCatching {
                Files.move(
                    extracted.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(extracted.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            metadataStore.write(
                StandardEvaluationMetadata(
                    path = destination.absolutePath,
                    sizeBytes = destination.length(),
                    sha256 = sha256,
                    version = EdaxReleaseConstants.EVALUATION_DATA_VERSION,
                ),
            )
            StandardEvaluationAsset(destination.absolutePath, sha256)
        } finally {
            archive.delete()
            extracted.delete()
        }
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class StandardEvaluationMetadata(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val version: String,
)

internal interface StandardEvaluationMetadataStore {
    fun read(): StandardEvaluationMetadata?
    fun write(metadata: StandardEvaluationMetadata)
    fun clear()
}

private class SharedPreferencesStandardEvaluationMetadata(
    private val preferences: SharedPreferences,
) : StandardEvaluationMetadataStore {
    override fun read(): StandardEvaluationMetadata? {
        val path = preferences.getString("path", null) ?: return null
        val sha256 = preferences.getString("sha256", null) ?: return null
        val version = preferences.getString("version", null) ?: return null
        return StandardEvaluationMetadata(path, preferences.getLong("size", -1L), sha256, version)
    }

    override fun write(metadata: StandardEvaluationMetadata) {
        preferences.edit()
            .putString("path", metadata.path)
            .putLong("size", metadata.sizeBytes)
            .putString("sha256", metadata.sha256)
            .putString("version", metadata.version)
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}

internal interface StandardEvaluationInstaller {
    val nativeAvailable: Boolean
    fun download(destination: File)
    fun extract(archive: File, destination: File, maximumBytes: Long)
    fun validate(path: String): String?
}

private object ProductionStandardEvaluationInstaller : StandardEvaluationInstaller {
    override val nativeAvailable: Boolean get() = NativeEdax.available
    override fun download(destination: File) = OfficialEvalDownloader.download(destination)
    override fun extract(archive: File, destination: File, maximumBytes: Long) =
        EdaxEvalArchiveExtractor.extract(archive, destination, maximumBytes)
    override fun validate(path: String): String? = NativeEdax.validateEvaluationData(path)
}
