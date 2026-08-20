package com.example.othello.analysis.edax

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Test

class EdaxEvalArchiveExtractorTest {
    @Test
    fun extractsOnlyEvalDatFromNestedArchive() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val archive = createArchive(
            "data/eval.dat" to expected,
            "data/other.bin" to byteArrayOf(9, 8, 7),
        )
        val destination = File.createTempFile("eval-extracted-", ".dat")
        try {
            EdaxEvalArchiveExtractor.extract(archive, destination, expected.size.toLong())
            assertContentEquals(expected, destination.readBytes())
        } finally {
            archive.delete()
            destination.delete()
        }
    }

    @Test
    fun rejectsArchiveWithoutEvalDatAndRemovesPartialOutput() {
        val archive = createArchive("data/other.bin" to byteArrayOf(1))
        val destination = File.createTempFile("eval-extracted-", ".dat")
        try {
            assertFailsWith<IllegalArgumentException> {
                EdaxEvalArchiveExtractor.extract(archive, destination, 1024)
            }
            assertFalse(destination.exists())
        } finally {
            archive.delete()
            destination.delete()
        }
    }

    @Test
    fun rejectsMultipleEvalDatEntries() {
        val archive = createArchive(
            "first/eval.dat" to byteArrayOf(1),
            "second/eval.dat" to byteArrayOf(2),
        )
        val destination = File.createTempFile("eval-extracted-", ".dat")
        try {
            assertFailsWith<IllegalArgumentException> {
                EdaxEvalArchiveExtractor.extract(archive, destination, 1024)
            }
            assertFalse(destination.exists())
        } finally {
            archive.delete()
            destination.delete()
        }
    }

    private fun createArchive(vararg entries: Pair<String, ByteArray>): File {
        val archive = File.createTempFile("eval-archive-", ".7z")
        SevenZOutputFile(archive).use { output ->
            entries.forEach { (name, bytes) ->
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    size = bytes.size.toLong()
                }
                output.putArchiveEntry(entry)
                output.write(bytes)
                output.closeArchiveEntry()
            }
        }
        assertTrue(archive.isFile)
        return archive
    }
}
