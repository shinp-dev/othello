package com.example.othello

import android.content.Context
import com.example.othello.records.LocalGameRecord
import com.example.othello.records.LocalGameRecordJson
import com.example.othello.records.LocalGameRecordStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-private, server-independent persistence for LocalGameRecord. */
class JsonFileLocalGameRecordStore(context: Context) : LocalGameRecordStore {
    private val file = File(context.applicationContext.filesDir, "local-game-records.jsonl")
    private val mutex = Mutex()

    override suspend fun list(limit: Int): List<LocalGameRecord> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext emptyList()
            LocalGameRecordJson.decodeList(file.readText())
                .sortedByDescending { it.createdAtEpochMillis }
                .take(limit.coerceAtLeast(0))
        }
    }

    override suspend fun save(record: LocalGameRecord) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val records = if (file.isFile) LocalGameRecordJson.decodeList(file.readText()) else emptyList()
            val next = (records.filterNot { it.localId == record.localId } + record)
                .sortedByDescending { it.createdAtEpochMillis }
            writeAtomically(LocalGameRecordJson.encodeList(next))
        }
    }

    override suspend fun delete(localId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext
            val records = LocalGameRecordJson.decodeList(file.readText())
            writeAtomically(LocalGameRecordJson.encodeList(records.filterNot { it.localId == localId }))
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
