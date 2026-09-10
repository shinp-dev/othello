package com.example.othello

import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.analysis.api.StandardEvaluationDataSource
import com.example.othello.analysis.api.StandardEvaluationPreparationPhase
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StandardAiPreparationControllerTest {
    @Test
    fun missingEvaluationAutomaticallyEntersPreparationAndBecomesReady() = runBlocking {
        val source = FakeSource()
        val controller = StandardAiPreparationController(source)

        assertIs<StandardAiPreparationState.NotPrepared>(controller.state.value)
        controller.prepare()

        assertIs<StandardAiPreparationState.Ready>(controller.state.value)
        assertEquals(1, source.prepareCalls)
    }

    @Test
    fun failedPreparationCanBeRetried() = runBlocking {
        val source = FakeSource(failFirst = true)
        val controller = StandardAiPreparationController(source)

        controller.prepare()
        assertIs<StandardAiPreparationState.Failed>(controller.state.value)
        controller.prepare()

        assertIs<StandardAiPreparationState.Ready>(controller.state.value)
        assertEquals(2, source.prepareCalls)
    }

    private class FakeSource(
        private val failFirst: Boolean = false,
    ) : StandardEvaluationDataSource {
        override val nativeAvailable = true
        var prepareCalls = 0
        private var asset: StandardEvaluationAsset? = null
        override fun current(): StandardEvaluationAsset? = asset
        override suspend fun prepare(
            onPhase: suspend (StandardEvaluationPreparationPhase) -> Unit,
        ): StandardEvaluationAsset {
            prepareCalls++
            onPhase(StandardEvaluationPreparationPhase.DOWNLOADING)
            if (failFirst && prepareCalls == 1) error("offline")
            return StandardEvaluationAsset("standard/eval.dat", "a".repeat(64)).also { asset = it }
        }
    }
}
