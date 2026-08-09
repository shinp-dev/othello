package com.example.othello.analysis.edax

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.othello.analysis.api.AnalysisAsset
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportedAnalysisFile(
    val fileName: String,
    val appPrivatePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val importedAtEpochMillis: Long,
)

data class EdaxDataStatus(
    val nativeAvailable: Boolean,
    val nativeVersion: String?,
    val enabled: Boolean,
    val level: Int,
    val evaluationData: ImportedAnalysisFile?,
    val openingBook: ImportedAnalysisFile?,
)

/** Owns the single active eval/book slots. A slot boundary permits later multi-book expansion. */
class EdaxDataManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val storageDirectory = File(appContext.filesDir, "analysis/edax/slots/default").apply { mkdirs() }

    fun status(): EdaxDataStatus = EdaxDataStatus(
        nativeAvailable = NativeEdax.available,
        nativeVersion = NativeEdax.version,
        enabled = preferences.getBoolean(KEY_ENABLED, true),
        level = preferences.getInt(KEY_LEVEL, DEFAULT_LEVEL).coerceIn(MIN_LEVEL, MAX_LEVEL),
        evaluationData = readMetadata(EVAL_PREFIX),
        openingBook = readMetadata(BOOK_PREFIX),
    )

    fun analysisSettings(): AnalysisSettings {
        val current = status()
        return AnalysisSettings(
            level = current.level,
            evaluationData = current.evaluationData?.let {
                EvaluationDataSource.Imported(AnalysisAsset(it.appPrivatePath, it.sha256))
            } ?: EvaluationDataSource.None,
            bookSource = current.openingBook?.let {
                BookSource.ImportedBook(AnalysisAsset(it.appPrivatePath, it.sha256))
            } ?: BookSource.None,
        )
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setLevel(level: Int) {
        require(level in MIN_LEVEL..MAX_LEVEL)
        preferences.edit().putInt(KEY_LEVEL, level).apply()
    }

    suspend fun importEvaluationData(uri: Uri): ImportedAnalysisFile =
        import(uri, EVAL_PREFIX, EVAL_MAX_BYTES, "dat", NativeEdax::validateEvaluationData)

    suspend fun importOpeningBook(uri: Uri): ImportedAnalysisFile =
        import(uri, BOOK_PREFIX, BOOK_MAX_BYTES, "book", NativeEdax::validateBook)

    fun deleteEvaluationData() = delete(EVAL_PREFIX)
    fun deleteOpeningBook() = delete(BOOK_PREFIX)

    private suspend fun import(
        uri: Uri,
        prefix: String,
        maximumBytes: Long,
        extension: String,
        validate: (String) -> String?,
    ): ImportedAnalysisFile = withContext(Dispatchers.IO) {
        require(NativeEdax.available) { "Edax native libraryを利用できません" }
        val displayName = queryDisplayName(uri).take(120)
        val temporary = File.createTempFile(".$prefix-", ".tmp", storageDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            requireNotNull(appContext.contentResolver.openInputStream(uri)) { "選択したファイルを開けません" }.use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= maximumBytes) { "ファイルが上限サイズを超えています" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(copied > 0) { "空のファイルはインポートできません" }
            validate(temporary.absolutePath)?.let { error(it) }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(storageDirectory, "$prefix-$sha256.$extension")
            runCatching {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val imported = ImportedAnalysisFile(displayName, destination.absolutePath, copied, sha256, System.currentTimeMillis())
            val previous = readMetadata(prefix)
            writeMetadata(prefix, imported)
            previous?.appPrivatePath?.takeIf { it != imported.appPrivatePath }?.let { File(it).delete() }
            imported
        } finally {
            temporary.delete()
        }
    }

    private fun delete(prefix: String) {
        preferences.getString("$prefix.path", null)?.let { File(it).delete() }
        clearMetadata(prefix)
    }

    private fun clearMetadata(prefix: String) {
        preferences.edit()
            .remove("$prefix.name")
            .remove("$prefix.path")
            .remove("$prefix.size")
            .remove("$prefix.sha256")
            .remove("$prefix.importedAt")
            .apply()
    }

    private fun readMetadata(prefix: String): ImportedAnalysisFile? {
        val path = preferences.getString("$prefix.path", null) ?: return null
        val file = File(path)
        if (!file.isFile || !file.toPath().normalize().startsWith(storageDirectory.toPath().normalize())) {
            clearMetadata(prefix)
            return null
        }
        val sha256 = preferences.getString("$prefix.sha256", null)
        if (sha256.isNullOrBlank()) {
            clearMetadata(prefix)
            return null
        }
        return ImportedAnalysisFile(
            fileName = preferences.getString("$prefix.name", null) ?: file.name,
            appPrivatePath = file.absolutePath,
            sizeBytes = preferences.getLong("$prefix.size", file.length()),
            sha256 = sha256,
            importedAtEpochMillis = preferences.getLong("$prefix.importedAt", 0L),
        )
    }

    private fun writeMetadata(prefix: String, metadata: ImportedAnalysisFile) {
        preferences.edit()
            .putString("$prefix.name", metadata.fileName)
            .putString("$prefix.path", metadata.appPrivatePath)
            .putLong("$prefix.size", metadata.sizeBytes)
            .putString("$prefix.sha256", metadata.sha256)
            .putLong("$prefix.importedAt", metadata.importedAtEpochMillis)
            .apply()
    }

    private fun queryDisplayName(uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "imported-file"
    }

    private companion object {
        const val PREFERENCES = "edax-analysis"
        const val KEY_ENABLED = "enabled"
        const val KEY_LEVEL = "level"
        const val EVAL_PREFIX = "evaluation"
        const val BOOK_PREFIX = "book"
        const val DEFAULT_LEVEL = 8
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 18
        const val EVAL_MAX_BYTES = 13_952_436L
        const val BOOK_MAX_BYTES = 256L * 1024L * 1024L
    }
}
