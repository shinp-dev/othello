package com.example.othello

import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.analysis.api.StandardEvaluationDataSource
import com.example.othello.analysis.api.StandardEvaluationPreparationPhase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StandardBootstrapControllerTest {
    private val snapshot = StandardContentSnapshot(emptyList())

    @Test
    fun bootstrapWaitsForOpponentPacksAndStillAdmitsHomeWhenEvalFails() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val opponents = OpponentPackSnapshot(listOf(InstalledOpponentPack(animalPack(), java.io.File("unused"))))
        val source = FakeEvaluationSource().apply { download = { error("offline") } }
        val bootstrap = StandardBootstrapController(
            { snapshot }, StandardAiPreparationController(source),
            { started.complete(Unit); ready.await(); opponents }, Dispatchers.Unconfined,
        )
        val task = async { bootstrap.prepare() }
        started.await()
        assertIs<StandardBootstrapState.Preparing>(bootstrap.state.value)
        assertEquals(0, source.downloadCalls)
        ready.complete(Unit)
        task.await()
        assertSame(opponents, assertIs<StandardBootstrapState.Ready>(bootstrap.state.value).opponents)
        assertIs<StandardAiPreparationState.Failed>(bootstrap.aiState.value)
        Unit
    }

    @Test
    fun entryWaitsForPackAndInitialEvalThenDoesNotRepeatForFeatureNavigation() = runBlocking {
        val source = FakeEvaluationSource()
        val downloaded = CompletableDeferred<Unit>()
        val allowDownload = CompletableDeferred<Unit>()
        source.download = { downloaded.complete(Unit); allowDownload.await() }
        var contentCalls = 0
        val bootstrap = bootstrap(source) { contentCalls++; snapshot }

        val first = async { bootstrap.prepare() }
        downloaded.await()
        assertIs<StandardBootstrapState.Preparing>(bootstrap.state.value)
        assertEquals(1, contentCalls)
        val second = async { bootstrap.prepare() }
        allowDownload.complete(Unit)
        first.await()
        second.await()
        bootstrap.prepare()

        assertSame(snapshot, assertIs<StandardBootstrapState.Ready>(bootstrap.state.value).content)
        assertIs<StandardAiPreparationState.Ready>(bootstrap.aiState.value)
        assertEquals(1, contentCalls)
        assertEquals(1, source.downloadCalls)
    }

    @Test
    fun installedEvalIsReusedEvenByAFreshProcessController() = runBlocking {
        val source = FakeEvaluationSource().apply { installed = asset }
        repeat(2) {
            val bootstrap = bootstrap(source)
            bootstrap.prepare()
            assertIs<StandardBootstrapState.Ready>(bootstrap.state.value)
            assertEquals(source.asset, assertIs<StandardAiPreparationState.Ready>(bootstrap.aiState.value).asset)
        }
        assertEquals(0, source.downloadCalls)
    }

    @Test
    fun evalFailureStillAdmitsHomeAndOtherFeaturesAndOnlyExplicitRetryPreparesAi() = runBlocking {
        val source = FakeEvaluationSource().apply { download = { error("offline") } }
        var contentCalls = 0
        val bootstrap = bootstrap(source) { contentCalls++; snapshot }
        bootstrap.prepare()
        assertSame(snapshot, assertIs<StandardBootstrapState.Ready>(bootstrap.state.value).content)
        assertIs<StandardAiPreparationState.Failed>(bootstrap.aiState.value)

        bootstrap.prepare()
        assertEquals(1, source.downloadCalls)
        source.download = {}
        bootstrap.retryAi()
        assertIs<StandardAiPreparationState.Ready>(bootstrap.aiState.value)
        assertIs<StandardBootstrapState.Ready>(bootstrap.state.value)
        assertEquals(2, source.downloadCalls)
        assertEquals(1, contentCalls)
    }

    @Test
    fun sourceConstructionFailureIsAlsoOnlyAnAiFailure() = runBlocking<Unit> {
        val bootstrap = StandardBootstrapController(
            { snapshot }, StandardAiPreparationController { error("unavailable storage") },
            { OpponentPackSnapshot(emptyList()) }, Dispatchers.Unconfined,
        )
        bootstrap.prepare()
        assertIs<StandardBootstrapState.Ready>(bootstrap.state.value)
        assertIs<StandardAiPreparationState.Failed>(bootstrap.aiState.value)
    }

    @Test
    fun nativeUnavailableDoesNotDownloadOrBlockStandard() = runBlocking {
        val source = FakeEvaluationSource().apply { nativeAvailable = false }
        val bootstrap = bootstrap(source)
        bootstrap.prepare()
        assertIs<StandardBootstrapState.Ready>(bootstrap.state.value)
        assertIs<StandardAiPreparationState.Failed>(bootstrap.aiState.value)
        assertEquals(0, source.downloadCalls)
    }

    @Test
    fun reenteringStandardChecksPacksAgainButDoesNotDownloadEvalAgain() = runBlocking {
        val source = FakeEvaluationSource()
        val ai = StandardAiPreparationController(source)
        var contentCalls = 0
        repeat(2) {
            val entry = StandardBootstrapController({ contentCalls++; snapshot }, ai, { OpponentPackSnapshot(emptyList()) }, Dispatchers.Unconfined)
            entry.prepare()
            assertIs<StandardBootstrapState.Ready>(entry.state.value)
        }
        assertEquals(2, contentCalls)
        assertEquals(1, source.downloadCalls)
    }

    @Test
    fun unreadableLocalContentCanBeRetriedWithoutStartingAiOrClearingData() = runBlocking {
        val source = FakeEvaluationSource()
        var fail = true
        val bootstrap = bootstrap(source) {
            if (fail) error("unreadable local packs")
            snapshot
        }
        bootstrap.prepare()
        assertIs<StandardBootstrapState.Failed>(bootstrap.state.value)
        assertEquals(0, source.downloadCalls)
        fail = false
        bootstrap.prepare()
        assertSame(snapshot, assertIs<StandardBootstrapState.Ready>(bootstrap.state.value).content)
    }

    @Test
    fun cancellingEvalDoesNotMarkEntryReadyAndCanBeRetried() = runBlocking<Unit> {
        val source = FakeEvaluationSource().apply { download = { throw CancellationException() } }
        val bootstrap = bootstrap(source)
        assertFailsWith<CancellationException> { bootstrap.prepare() }
        assertIs<StandardBootstrapState.Preparing>(bootstrap.state.value)
        assertIs<StandardAiPreparationState.NotPrepared>(bootstrap.aiState.value)
        source.download = {}
        bootstrap.prepare()
        assertIs<StandardBootstrapState.Ready>(bootstrap.state.value)
    }

    @Test
    fun cancellingPackCheckDoesNotStartEvalAndCanBeRetried() = runBlocking<Unit> {
        val source = FakeEvaluationSource()
        var cancel = true
        val bootstrap = bootstrap(source) {
            if (cancel) throw CancellationException()
            snapshot
        }
        assertFailsWith<CancellationException> { bootstrap.prepare() }
        assertEquals(0, source.downloadCalls)
        cancel = false
        bootstrap.prepare()
        assertIs<StandardBootstrapState.Ready>(bootstrap.state.value)
    }

    private fun bootstrap(
        source: FakeEvaluationSource,
        content: suspend () -> StandardContentSnapshot = { snapshot },
    ) = StandardBootstrapController(content, StandardAiPreparationController(source), { OpponentPackSnapshot(emptyList()) }, Dispatchers.Unconfined)

    private class FakeEvaluationSource : StandardEvaluationDataSource {
        override var nativeAvailable = true
        val asset = StandardEvaluationAsset("standard/eval.dat", "a".repeat(64))
        var installed: StandardEvaluationAsset? = null
        var downloadCalls = 0
        var download: suspend () -> Unit = {}
        override fun current() = installed
        override suspend fun prepare(
            onPhase: suspend (StandardEvaluationPreparationPhase) -> Unit,
        ): StandardEvaluationAsset {
            downloadCalls++
            onPhase(StandardEvaluationPreparationPhase.DOWNLOADING)
            download()
            installed = asset
            return asset
        }
    }
}
