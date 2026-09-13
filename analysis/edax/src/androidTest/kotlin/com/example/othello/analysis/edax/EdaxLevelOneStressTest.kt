package com.example.othello.analysis.edax

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.MoveOutcome
import com.example.othello.game.TurnResolver
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdaxLevelOneStressTest {
    @Test
    fun analyzesManyReachableForcedPassCandidatesWithSyntheticEvaluationData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val eval = File(context.cacheDir, "stress-synthetic-eval.dat")
        writeSyntheticEvaluationData(eval)
        val asset = StandardEvaluationAsset(eval.absolutePath, eval.name)
        val provider = ProductionStandardCandidateProvider()
        var analyzed = 0

        for (seed in 0 until 100_000) {
            var state = GameState()
            val random = Random(seed)
            val targetPly = 8 + random.nextInt(50)
            val moves = StringBuilder()
            for (ply in 0 until targetPly) {
                state = TurnResolver.resolveForcedPasses(state).state
                if (state.legalMoves.isEmpty()) break
                val move = state.legalMoves.sortedBy { it.index() }[random.nextInt(state.legalMoves.size)]
                moves.append(('a'.code + move.column).toChar()).append(move.row + 1)
                state = (state.play(move) as MoveOutcome.Played).state
            }
            state = TurnResolver.resolveForcedPasses(state).state
            val firstMove = state.legalMoves.minByOrNull { it.index() } ?: continue
            val firstChild = (state.play(firstMove) as MoveOutcome.Played).state
            val firstCandidateForcesPass =
                firstChild.status is GameStatus.InProgress && firstChild.legalMoves.isEmpty()
            if (!firstCandidateForcesPass || state.legalMoves.size <= 1) continue

            Log.i("EdaxStress", "forced-pass-case=$seed moves=$moves first=${firstMove.index()} legal=${state.legalMoves.size}")
            val result = provider.evaluate(state, 1, asset)
            assertTrue(result.available, "case=$seed moves=$moves message=${result.message}")
            assertTrue(result.rankedCandidates.map { it.move }.toSet() == state.legalMoves)
            analyzed++
            if (analyzed >= 200) break
        }
        Log.i("EdaxStress", "completed=$analyzed")
        assertTrue(analyzed >= 200)
    }

    private fun writeSyntheticEvaluationData(file: File) {
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
        }
    }

    private companion object {
        const val EVAL_SIZE = 13_952_436L
        const val EDAX_MAGIC = 0x45444158
        const val EVAL_MAGIC = 0x4556414c
    }
}
