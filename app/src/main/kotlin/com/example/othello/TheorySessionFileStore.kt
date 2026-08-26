package com.example.othello

import android.content.Context
import com.example.othello.theory.TheoryExplorationSession
import com.example.othello.theory.TheorySessionJson
import com.example.othello.theory.TheorySessionPersistenceCoordinator
import com.example.othello.theory.TheorySessionSnapshot
import com.example.othello.theory.TheorySessionStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-private temporary session; it is intentionally separate from saved game records. */
class JsonFileTheorySessionStore private constructor(
    private val file: File,
    private val mutex: Mutex,
) : TheorySessionStore {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "theory-exploration-session.json"),
        Mutex(),
    )

    internal constructor(file: File) : this(file, Mutex())

    override suspend fun load(): TheorySessionSnapshot? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext null
            runCatching {
                TheorySessionJson.decode(file.readText()).also { snapshot ->
                    require(TheoryExplorationSession.restore(snapshot) != null) {
                        "theory session failed semantic validation"
                    }
                }
            }.getOrElse {
                quarantineCorruptFile()
                null
            }
        }
    }

    override suspend fun save(snapshot: TheorySessionSnapshot) = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(TheoryExplorationSession.restore(snapshot) != null) {
                "refusing to persist an invalid theory session"
            }
            writeAtomically(TheorySessionJson.encode(snapshot))
        }
    }

    private fun quarantineCorruptFile() {
        if (!file.isFile) return
        val parent = requireNotNull(file.parentFile).apply { mkdirs() }
        val quarantine = File.createTempFile("${file.name}.corrupt-", ".json", parent)
        quarantine.delete()
        runCatching {
            Files.move(file.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            file.delete()
        }
    }

    private fun writeAtomically(contents: String) {
        val parent = requireNotNull(file.parentFile).apply { mkdirs() }
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeText(contents)
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** Keeps the latest temporary-session write alive when its screen leaves composition. */
class TheorySessionPersistenceProcessOwner(
    store: TheorySessionStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val processScope = CoroutineScope(SupervisorJob() + dispatcher)
    val coordinator = TheorySessionPersistenceCoordinator(store, processScope)

    override fun close() {
        processScope.cancel()
    }
}
