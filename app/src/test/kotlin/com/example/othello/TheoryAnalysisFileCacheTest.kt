package com.example.othello

import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.analysis.api.EvaluationPerspective
import com.example.othello.analysis.api.EvaluationScore
import com.example.othello.analysis.api.MoveEvaluation
import com.example.othello.game.Disc
import com.example.othello.game.Position
import com.example.othello.theory.TheoryAnalysisCacheJson
import com.example.othello.theory.TheoryAnalysisCacheKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TheoryAnalysisFileCacheTest {
    @Test
    fun cacheHitReturnsCompleteSerializedEdaxResult() = runBlocking {
        withCacheDirectory { directory ->
            val cache = JsonFileTheoryAnalysisCache(directory, buildVersion = 7)
            val key = key(1)
            val result = result(1)

            cache.put(key, result)

            assertEquals(result, cache.get(key))
            assertEquals(1, cache.entryCount())
            assertTrue(cache.sizeBytes() > 0L)
        }
    }

    @Test
    fun capacityEvictsLeastRecentlyUsedEntryByBytes() = runBlocking {
        withCacheDirectory { directory ->
            val entryBytes = TheoryAnalysisCacheJson.encode(key(1), result(1)).toByteArray().size.toLong()
            val cache = JsonFileTheoryAnalysisCache(
                directory = directory,
                buildVersion = 7,
                maximumBytes = entryBytes * 2,
                // A coarse filesystem clock must still preserve strict access order.
                now = { 1_000L },
            )

            cache.put(key(1), result(1))
            cache.put(key(2), result(2))
            assertEquals(result(1), cache.get(key(1))) // key 1 becomes most recently used.
            cache.put(key(3), result(3))

            assertEquals(result(1), cache.get(key(1)))
            assertNull(cache.get(key(2)))
            assertEquals(result(3), cache.get(key(3)))
            assertEquals(2, cache.entryCount())
            assertTrue(cache.sizeBytes() <= entryBytes * 2)
        }
    }

    @Test
    fun singleEntryLargerThanCapacityIsNotRetained() = runBlocking {
        withCacheDirectory { directory ->
            val encodedBytes = TheoryAnalysisCacheJson.encode(key(1), result(1)).toByteArray().size.toLong()
            val cache = JsonFileTheoryAnalysisCache(
                directory = directory,
                buildVersion = 7,
                maximumBytes = encodedBytes - 1,
            )

            cache.put(key(1), result(1))

            assertNull(cache.get(key(1)))
            assertEquals(0, cache.entryCount())
        }
    }

    @Test
    fun buildVersionChangePurgesEveryOldEntry() = runBlocking {
        withCacheDirectory { directory ->
            val oldBuild = JsonFileTheoryAnalysisCache(directory, buildVersion = 7)
            oldBuild.put(key(1), result(1))
            oldBuild.put(key(2), result(2))
            assertEquals(2, oldBuild.entryCount())

            val newBuild = JsonFileTheoryAnalysisCache(directory, buildVersion = 8)

            assertNull(newBuild.get(key(1)))
            assertNull(newBuild.get(key(2)))
            assertEquals(0, newBuild.entryCount())
        }
    }

    @Test
    fun corruptEntryIsRemovedWithoutPoisoningOtherEntries() = runBlocking {
        withCacheDirectory { directory ->
            val cache = JsonFileTheoryAnalysisCache(directory, buildVersion = 7)
            cache.put(key(1), result(1))
            cache.put(key(2), result(2))
            File(directory, "${key(1).fileId()}.json").writeText("broken")

            assertNull(cache.get(key(1)))
            assertEquals(result(2), cache.get(key(2)))
            assertEquals(1, cache.entryCount())
        }
    }

    @Test
    fun explicitClearKeepsCacheUsable() = runBlocking {
        withCacheDirectory { directory ->
            val cache = JsonFileTheoryAnalysisCache(directory, buildVersion = 7)
            cache.put(key(1), result(1))

            cache.clear()
            cache.put(key(2), result(2))

            assertNull(cache.get(key(1)))
            assertEquals(result(2), cache.get(key(2)))
        }
    }

    @Test
    fun ownedRemovalCannotDeleteANewerWriteForTheSamePosition() = runBlocking {
        withCacheDirectory { directory ->
            val cache = JsonFileTheoryAnalysisCache(directory, buildVersion = 7)
            val key = key(1)
            val oldToken = requireNotNull(cache.put(key, result(1)))
            val latestResult = result(2)
            val latestToken = requireNotNull(cache.put(key, latestResult))

            cache.removeIfOwned(key, oldToken)
            assertEquals(latestResult, cache.get(key))

            cache.removeIfOwned(key, latestToken)
            assertNull(cache.get(key))
        }
    }

    private fun key(index: Int): TheoryAnalysisCacheKey = TheoryAnalysisCacheKey(
        board = index.toString().padStart(64, '0'),
        currentPlayer = Disc.BLACK,
        level = 6,
        timePerCandidateMs = 2_000,
        evaluationIdentity = "e".repeat(64),
        bookIdentity = "b".repeat(64),
    )

    private fun result(index: Int): AnalysisResult = AnalysisResult(
        listOf(
            MoveEvaluation(
                Position(0, index),
                EvaluationScore(
                    value = index,
                    perspective = EvaluationPerspective.SIDE_TO_MOVE,
                    kind = EvaluationKind.HEURISTIC,
                    searchedDepth = 6,
                    selectivityPercent = 100,
                ),
            ),
        ),
    )

    private suspend fun withCacheDirectory(block: suspend (File) -> Unit) {
        val root = createTempDirectory("theory-analysis-cache").toFile()
        try {
            block(File(root, "cache"))
        } finally {
            root.deleteRecursively()
        }
    }
}
