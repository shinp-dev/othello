package com.example.othello.analysis.edax

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.othello.analysis.api.AnalysisAsset
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.BookSource
import com.example.othello.analysis.api.EvaluationDataSource
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.TurnResolver
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdaxNativeInstrumentationTest {
    @Test
    fun initializesAndSolvesARepeatableSmallEndgameWithoutBook() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val evaluation = File(context.cacheDir, "synthetic-eval.dat")
        val book = File(context.cacheDir, "synthetic-empty-book.dat")
        writeSyntheticEvaluationData(evaluation)
        writeSyntheticEmptyBook(book)
        assertNull(NativeEdax.validateEvaluationData(evaluation.absolutePath))
        assertNull(NativeEdax.validateBook(book.absolutePath))
        assertTrue(NativeEdax.version.orEmpty().contains("Edax 4.6"))

        val position = deterministicEndgame(4)
        val engine = ProductionAnalysisEngine()
        val settings = AnalysisSettings(
            level = 8,
            evaluationData = EvaluationDataSource.Imported(AnalysisAsset(evaluation.absolutePath, "synthetic-zero-v1")),
            bookSource = BookSource.ImportedBook(AnalysisAsset(book.absolutePath, "synthetic-empty-book-v1")),
        )
        val first = engine.analyze(ReviewPosition(position), settings)
        engine.clearCache()
        val second = engine.analyze(ReviewPosition(position), settings)

        assertTrue(first.available)
        assertEquals(position.legalMoves, first.evaluations.map { it.move }.toSet())
        assertTrue(first.evaluations.all { it.score.kind == EvaluationKind.EXACT })
        assertEquals(first, second)
    }

    @Test
    fun rejectsMalformedEvaluationAndBookWithoutCrashingProcess() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val invalid = File(context.cacheDir, "invalid-edax-data").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val oversizedNodeCount = File(context.cacheDir, "invalid-book-node-count").also {
            writeSyntheticEmptyBook(it, Int.MAX_VALUE)
        }
        assertTrue(NativeEdax.validateEvaluationData(invalid.absolutePath).orEmpty().isNotBlank())
        assertTrue(NativeEdax.validateBook(invalid.absolutePath).orEmpty().isNotBlank())
        assertTrue(NativeEdax.validateBook(oversizedNodeCount.absolutePath).orEmpty().contains("node count"))
    }

    @Test
    fun dataManagerReplacesEvaluationOnlyAfterValidationAndPersistsTheActiveSlot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = EdaxDataManager(context)
        val firstSource = File(context.cacheDir, "manager-eval-first.dat")
        val secondSource = File(context.cacheDir, "manager-eval-second.dat")
        val invalidSource = File(context.cacheDir, "manager-eval-invalid.dat")
        manager.deleteEvaluationData()
        try {
            writeSyntheticEvaluationData(firstSource)
            writeSyntheticEvaluationData(secondSource, marker = 1)
            invalidSource.writeBytes(byteArrayOf(1, 2, 3, 4))

            val first = manager.importEvaluationData(Uri.fromFile(firstSource))
            assertEquals(EVAL_SIZE, manager.status().evaluationData?.sizeBytes)

            val second = manager.importEvaluationData(Uri.fromFile(secondSource))
            assertEquals(second.sha256, manager.status().evaluationData?.sha256)
            assertFalse(File(first.appPrivatePath).exists())

            assertFailsWith<IllegalStateException> {
                manager.importEvaluationData(Uri.fromFile(invalidSource))
            }
            assertEquals(second.sha256, EdaxDataManager(context).status().evaluationData?.sha256)

            manager.deleteEvaluationData()
            assertNull(manager.status().evaluationData)
        } finally {
            manager.deleteEvaluationData()
            firstSource.delete()
            secondSource.delete()
            invalidSource.delete()
        }
    }

    @Test
    fun dataManagerAcceptsSmallValidBookReplacesItAndPreservesItOnInvalidImport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = EdaxDataManager(context)
        val firstSource = File(context.cacheDir, "manager-book-first.dat")
        val secondSource = File(context.cacheDir, "manager-book-second.dat")
        val invalidSource = File(context.cacheDir, "manager-book-invalid.dat")
        manager.deleteOpeningBook()
        try {
            writeSyntheticEmptyBook(firstSource, day = 9)
            writeSyntheticEmptyBook(secondSource, day = 10)
            invalidSource.writeBytes(byteArrayOf(1, 2, 3, 4))

            val first = manager.importOpeningBook(Uri.fromFile(firstSource))
            assertEquals(42L, first.sizeBytes)

            val second = manager.importOpeningBook(Uri.fromFile(secondSource))
            assertEquals(second.sha256, manager.status().openingBook?.sha256)
            assertFalse(File(first.appPrivatePath).exists())

            assertFailsWith<IllegalStateException> {
                manager.importOpeningBook(Uri.fromFile(invalidSource))
            }
            assertEquals(second.sha256, EdaxDataManager(context).status().openingBook?.sha256)

            manager.deleteOpeningBook()
            assertNull(manager.status().openingBook)
        } finally {
            manager.deleteOpeningBook()
            firstSource.delete()
            secondSource.delete()
            invalidSource.delete()
        }
    }

    private fun deterministicEndgame(targetEmpty: Int): GameState {
        var state = GameState()
        while (state.board.emptyCount() > targetEmpty) {
            val legal = state.legalMoves.sortedBy { it.index() }
            if (legal.isEmpty()) {
                state = TurnResolver.resolveForcedPasses(state).state
                if (state.legalMoves.isEmpty()) break
            } else {
                state = TurnResolver.resolveForcedPasses((state.play(legal.first()) as MoveOutcome.Played).state).state
            }
        }
        require(state.legalMoves.isNotEmpty())
        return state
    }

    private fun writeSyntheticEvaluationData(file: File, marker: Int = 0) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(EVAL_SIZE)
            val header = ByteBuffer.allocate(28).order(ByteOrder.nativeOrder())
            header.putInt(EDAX_MAGIC)
            header.putInt(EVAL_MAGIC)
            header.putInt(4)
            header.putInt(6)
            header.putInt(0)
            header.putDouble(0.0)
            output.seek(0)
            output.write(header.array())
            if (marker != 0) {
                output.seek(128)
                output.write(marker)
            }
        }
    }

    /** A header-only Edax 4.6 book created by this test; it contains no third-party positions. */
    private fun writeSyntheticEmptyBook(file: File, nodeCount: Int = 0, day: Int = 9) {
        val header = ByteBuffer.allocate(42).order(ByteOrder.nativeOrder())
        header.putInt(EDAX_MAGIC)
        header.putInt(BOOK_MAGIC)
        header.put(4)
        header.put(6)
        header.putShort(2026)
        header.put(8)
        header.put(day.toByte())
        header.put(0)
        header.put(0)
        header.put(0)
        header.put(0)
        repeat(5) { header.putInt(0) }
        header.putInt(nodeCount)
        file.writeBytes(header.array())
    }

    private companion object {
        const val EVAL_SIZE = 13_952_436L
        const val EDAX_MAGIC = 0x45444158
        const val EVAL_MAGIC = 0x4556414c
        const val BOOK_MAGIC = 0x424f4f4b
    }
}
