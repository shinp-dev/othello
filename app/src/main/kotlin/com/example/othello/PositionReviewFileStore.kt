package com.example.othello

import android.content.Context
import com.example.othello.review.PositionReviewDecodeResult
import com.example.othello.review.PositionReviewRecord
import com.example.othello.review.PositionReviewRecordJson
import com.example.othello.review.PositionReviewStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-private persistence dedicated to imported position reviews. */
class JsonFilePositionReviewStore private constructor(
    private val file: File,
    private val mutex: Mutex = Mutex(),
) : PositionReviewStore {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "position-reviews.jsonl"))

    internal constructor(file: File) : this(file, Mutex())

    override suspend fun list(limit: Int): List<PositionReviewRecord> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext emptyList()
            val decoded = readDecoded()
            if (decoded.hasCorruption) {
                backupCorruptLines(decoded)
                writeAtomically(PositionReviewRecordJson.encodeList(decoded.records))
            }
            decoded.records.sortedByDescending { it.updatedAtEpochMillis }.take(limit.coerceAtLeast(0))
        }
    }

    override suspend fun save(record: PositionReviewRecord) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val decoded = readDecoded()
            if (decoded.hasCorruption) backupCorruptLines(decoded)
            val next = (decoded.records.filterNot { it.id == record.id } + record)
                .sortedByDescending { it.updatedAtEpochMillis }
            writeAtomically(PositionReviewRecordJson.encodeList(next))
        }
    }

    override suspend fun delete(id: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext
            val decoded = readDecoded()
            if (decoded.hasCorruption) backupCorruptLines(decoded)
            writeAtomically(PositionReviewRecordJson.encodeList(decoded.records.filterNot { it.id == id }))
        }
    }

    private fun readDecoded(): PositionReviewDecodeResult =
        if (file.isFile) PositionReviewRecordJson.decodeListRecovering(file.readText())
        else PositionReviewDecodeResult(emptyList(), emptyList())

    private fun backupCorruptLines(decoded: PositionReviewDecodeResult) {
        if (!decoded.hasCorruption) return
        val parent = requireNotNull(file.parentFile)
        val backup = File.createTempFile("${file.name}.corrupt-", ".jsonl", parent)
        try {
            backup.writeText(decoded.corruptLines.joinToString("\n"))
        } catch (failure: Throwable) {
            backup.delete()
            throw failure
        }
    }

    private fun writeAtomically(contents: String) {
        val parent = requireNotNull(file.parentFile)
        parent.mkdirs()
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeText(contents)
        runCatching {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
