package com.example.othello.analysis.edax

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.othello.analysis.api.AnalysisAsset
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import java.io.FileInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportedAnalysisFile(
    val fileName: String,
    val appPrivatePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val importedAtEpochMillis: Long,
)

data class EdaxCommonDataStatus(
    val nativeAvailable: Boolean,
    val nativeVersion: String?,
    val evaluationData: ImportedAnalysisFile?,
    val openingBook: ImportedAnalysisFile?,
)

internal const val EDAX_PREFERENCES = "edax-analysis"

/** Deliberately separate engine and evaluation-data versions. The archive URL is pinned below. */
object EdaxReleaseConstants {
    const val ENGINE_VERSION = "4.6"
    const val EVALUATION_DATA_VERSION = "4.4"
    const val OFFICIAL_EVALUATION_ARCHIVE_URL =
        "https://github.com/abulmo/edax-reversi/releases/download/v4.4/eval.7z"
}

/** Owns the single active eval/book slots. A slot boundary permits later multi-book expansion. */
class EdaxDataManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val storageDirectory = File(appContext.filesDir, "analysis/edax/slots/default").apply { mkdirs() }

    fun commonDataStatus(): EdaxCommonDataStatus = EdaxCommonDataStatus(
        nativeAvailable = NativeEdax.available,
        nativeVersion = NativeEdax.version,
        evaluationData = readMetadata(EVAL_PREFIX),
        openingBook = readMetadata(BOOK_PREFIX),
    )

    fun analysisSettings(level: Int): AnalysisSettings {
        val current = commonDataStatus()
        return AnalysisSettings(
            level = level,
            evaluationData = current.evaluationData?.let {
                EvaluationDataSource.Imported(AnalysisAsset(it.appPrivatePath, it.sha256))
            } ?: EvaluationDataSource.None,
            bookSource = current.openingBook?.let {
                BookSource.ImportedBook(AnalysisAsset(it.appPrivatePath, it.sha256))
            } ?: BookSource.None,
        )
    }

    suspend fun importEvaluationData(uri: Uri): ImportedAnalysisFile =
        import(uri, EVAL_PREFIX, EVAL_MAX_BYTES, "dat", NativeEdax::validateEvaluationData)

    /** Downloads only the official v4.4 archive and installs its eval.dat after validation. */
    suspend fun downloadOfficialEvaluationData(
        onPhase: suspend (String) -> Unit = {},
    ): ImportedAnalysisFile = withContext(Dispatchers.IO) {
        require(NativeEdax.available) { "Edax native libraryを利用できません" }
        val archive = File.createTempFile(".evaluation-official-", ".7z", storageDirectory)
        var extracted: File? = null
        try {
            val extractedFile = File.createTempFile(".evaluation-official-", ".dat", storageDirectory)
            extracted = extractedFile
            onPhase("公式データをダウンロード中…")
            OfficialEvalDownloader.download(archive)
            onPhase("評価データを展開中…")
            EdaxEvalArchiveExtractor.extract(archive, extractedFile, EVAL_MAX_BYTES)
            onPhase("評価データを検証・保存中…")
            installValidatedFile(
                source = extractedFile,
                prefix = EVAL_PREFIX,
                maximumBytes = EVAL_MAX_BYTES,
                extension = "dat",
                displayName = "Edax v${EdaxReleaseConstants.EVALUATION_DATA_VERSION} eval.dat",
                validate = NativeEdax::validateEvaluationData,
            )
        } finally {
            archive.delete()
            extracted?.delete()
        }
    }

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
            installValidatedFile(
                source = temporary,
                prefix = prefix,
                maximumBytes = maximumBytes,
                extension = extension,
                displayName = displayName,
                validate = validate,
                precomputedSha256 = digest.digest().joinToString("") { "%02x".format(it) },
            )
        } finally {
            temporary.delete()
        }
    }

    private fun installValidatedFile(
        source: File,
        prefix: String,
        maximumBytes: Long,
        extension: String,
        displayName: String,
        validate: (String) -> String?,
        precomputedSha256: String? = null,
    ): ImportedAnalysisFile {
        val size = source.length()
        require(source.isFile && size > 0L) { "空のファイルはインポートできません" }
        require(size <= maximumBytes) { "ファイルが上限サイズを超えています" }
        validate(source.absolutePath)?.let { error(it) }
        val sha256 = precomputedSha256 ?: sha256(source)
        val destination = File(storageDirectory, "$prefix-$sha256.$extension")
        runCatching {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val imported = ImportedAnalysisFile(displayName, destination.absolutePath, size, sha256, System.currentTimeMillis())
        val previous = readMetadata(prefix)
        writeMetadata(prefix, imported)
        previous?.appPrivatePath?.takeIf { it != imported.appPrivatePath }?.let { File(it).delete() }
        return imported
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
        val actualSize = file.length()
        val recordedSize = preferences.getLong("$prefix.size", actualSize)
        if (actualSize <= 0L || recordedSize != actualSize) {
            clearMetadata(prefix)
            return null
        }
        val sha256 = preferences.getString("$prefix.sha256", null)
        if (sha256 == null || !SHA256.matches(sha256)) {
            clearMetadata(prefix)
            return null
        }
        return ImportedAnalysisFile(
            fileName = preferences.getString("$prefix.name", null) ?: file.name,
            appPrivatePath = file.absolutePath,
            sizeBytes = actualSize,
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
        const val PREFERENCES = EDAX_PREFERENCES
        const val EVAL_PREFIX = "evaluation"
        const val BOOK_PREFIX = "book"
        const val EVAL_MAX_BYTES = 13_952_436L
        const val BOOK_MAX_BYTES = 256L * 1024L * 1024L
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal object OfficialEvalDownloader {
    private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L

    fun download(destination: File) {
        val connection = (URL(EdaxReleaseConstants.OFFICIAL_EVALUATION_ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "Chanriva-EdaxData/1.0")
        }
        try {
            require(connection.url.protocol == "https") { "公式評価データの接続先が安全ではありません" }
            val responseCode = connection.responseCode
            require(responseCode in 200..299) { "公式評価データを取得できませんでした（HTTP $responseCode）" }
            val contentLength = connection.contentLengthLong
            require(contentLength <= MAX_ARCHIVE_BYTES || contentLength < 0L) { "公式評価データが大きすぎます" }
            requireNotNull(connection.inputStream).use { input ->
                FileOutputStream(destination).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_ARCHIVE_BYTES) { "公式評価データが大きすぎます" }
                        output.write(buffer, 0, count)
                    }
                    require(copied > 0L) { "公式評価データが空です" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Extracts one eval.dat entry without materialising any other archive entry. */
internal object EdaxEvalArchiveExtractor {
    fun extract(archive: File, destination: File, maximumBytes: Long) {
        var found = false
        try {
            SevenZFile.builder().setFile(archive).get().use { sevenZ ->
                while (true) {
                    val entry = sevenZ.nextEntry ?: break
                    if (entry.isDirectory || entry.name.fileName() != "eval.dat") continue
                    require(!found) { "7z内にeval.datが複数あります" }
                    require(entry.size < 0L || entry.size <= maximumBytes) { "展開後の評価データが大きすぎます" }
                    destination.outputStream().buffered().use { output ->
                        var copied = 0L
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = sevenZ.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= maximumBytes) { "展開後の評価データが大きすぎます" }
                            output.write(buffer, 0, count)
                        }
                        require(copied > 0L) { "eval.datが空です" }
                        require(entry.size < 0L || copied == entry.size) { "eval.datの展開サイズが不正です" }
                    }
                    found = true
                }
            }
            require(found) { "7z内にeval.datがありません" }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    private fun String.fileName(): String = replace('\\', '/').substringAfterLast('/')
}
