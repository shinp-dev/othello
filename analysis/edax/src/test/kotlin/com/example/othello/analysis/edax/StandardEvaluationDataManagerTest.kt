package com.example.othello.analysis.edax

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StandardEvaluationDataManagerTest {
    @Test
    fun preparesIntoStandardOnlyDirectoryAndReusesTheInstalledAsset() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("standard-eval-test").toFile()
        try {
            val metadata = MemoryMetadataStore()
            val installer = FakeInstaller()
            val manager = StandardEvaluationDataManager(directory, metadata, installer)

            val prepared = manager.prepare()
            val preparedAgain = manager.prepare()

            assertTrue(File(prepared.appPrivatePath).toPath().startsWith(directory.toPath()))
            assertTrue(File(prepared.appPrivatePath).name.startsWith("eval-v4.4-"))
            assertEquals(prepared, preparedAgain)
            assertEquals(1, installer.downloadCalls)
            assertNotNull(manager.current())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedPreparationCanBeRetried() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("standard-eval-retry").toFile()
        try {
            val installer = FakeInstaller(failFirstDownload = true)
            val manager = StandardEvaluationDataManager(directory, MemoryMetadataStore(), installer)

            assertFails { manager.prepare() }
            assertNull(manager.current())
            assertNotNull(manager.prepare())
            assertEquals(2, installer.downloadCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class MemoryMetadataStore : StandardEvaluationMetadataStore {
        private var metadata: StandardEvaluationMetadata? = null
        override fun read(): StandardEvaluationMetadata? = metadata
        override fun write(metadata: StandardEvaluationMetadata) { this.metadata = metadata }
        override fun clear() { metadata = null }
    }

    private class FakeInstaller(
        private val failFirstDownload: Boolean = false,
    ) : StandardEvaluationInstaller {
        override val nativeAvailable = true
        var downloadCalls = 0

        override fun download(destination: File) {
            downloadCalls++
            if (failFirstDownload && downloadCalls == 1) error("offline")
            destination.writeText("archive")
        }

        override fun extract(archive: File, destination: File, maximumBytes: Long) {
            destination.writeText("valid evaluation")
        }

        override fun validate(path: String): String? = null
    }
}
