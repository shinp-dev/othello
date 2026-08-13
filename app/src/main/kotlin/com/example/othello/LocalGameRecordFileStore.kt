package com.example.othello

import android.content.Context
import com.example.othello.records.LocalGameRecord
import com.example.othello.records.LocalGameRecordJson
import com.example.othello.records.LocalGameRecordDecodeResult
import com.example.othello.records.LocalGameRecordReadResult
import com.example.othello.records.LocalGameRecordStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-private, server-independent persistence for LocalGameRecord. */
class JsonFileLocalGameRecordStore private constructor(
    private val file: File,
    private val mutex: Mutex = Mutex(),
) : LocalGameRecordStore {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "local-game-records.jsonl"))

    internal constructor(file: File) : this(file, Mutex())

    override suspend fun list(limit: Int): List<LocalGameRecord> = mutex.withLock {
        withContext(Dispatchers.IO) {
            listResultLocked(limit).records
        }
    }

    override suspend fun listResult(limit: Int): LocalGameRecordReadResult = mutex.withLock {
        withContext(Dispatchers.IO) { listResultLocked(limit) }
    }

    override suspend fun save(record: LocalGameRecord) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val decoded = readDecoded()
            if (decoded.hasCorruption) backupCorruptLines(decoded)
            val next = (decoded.records.filterNot { it.localId == record.localId } + record)
                .sortedByDescending { it.createdAtEpochMillis }
            writeAtomically(LocalGameRecordJson.encodeList(next))
        }
    }

    override suspend fun delete(localId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext
            val decoded = readDecoded()
            if (decoded.hasCorruption) backupCorruptLines(decoded)
            writeAtomically(LocalGameRecordJson.encodeList(decoded.records.filterNot { it.localId == localId }))
        }
    }

    private fun listResultLocked(limit: Int): LocalGameRecordReadResult {
        if (!file.isFile) return LocalGameRecordReadResult(emptyList())
        val decoded = readDecoded()
        val recovered = if (decoded.hasCorruption) {
            runCatching {
                backupCorruptLines(decoded)
                writeAtomically(LocalGameRecordJson.encodeList(decoded.records))
            }.isSuccess
        } else {
            false
        }
        return LocalGameRecordReadResult(
            records = decoded.records.sortedByDescending { it.createdAtEpochMillis }.take(limit.coerceAtLeast(0)),
            corruptLineCount = decoded.corruptLines.size,
            recoveryCompleted = recovered,
        )
    }

    private fun readDecoded(): LocalGameRecordDecodeResult =
        if (file.isFile) LocalGameRecordJson.decodeListRecovering(file.readText())
        else LocalGameRecordDecodeResult(emptyList(), emptyList())

    private fun backupCorruptLines(decoded: LocalGameRecordDecodeResult) {
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
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(contents)
        runCatching {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
