package com.example.othello

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VersionGateTest {
    @Test
    fun currentEqualToMinimumIsSupported() = runBlocking {
        assertEquals(VersionGateState.Supported, check(current = 7, response = validResponse(7)))
    }

    @Test
    fun currentGreaterThanMinimumIsSupported() = runBlocking {
        assertEquals(VersionGateState.Supported, check(current = 8, response = validResponse(7)))
    }

    @Test
    fun currentLowerThanMinimumIsUnsupported() = runBlocking {
        assertEquals(VersionGateState.Unsupported(6, 7), check(current = 6, response = validResponse(7)))
    }

    @Test
    fun networkFailureIsError() = runBlocking {
        assertEquals(VersionGateState.Error, check(current = 1, failure = IOException("offline")))
    }

    @Test
    fun timeoutIsError() = runBlocking {
        assertEquals(VersionGateState.Error, check(current = 1, failure = SocketTimeoutException("timeout")))
    }

    @Test
    fun nonSuccessResponseIsError() = runBlocking {
        assertEquals(VersionGateState.Error, check(current = 1, response = AppConfigHttpResponse(503, "{}")))
    }

    @Test
    fun malformedJsonIsError() = runBlocking {
        assertEquals(VersionGateState.Error, check(current = 1, response = AppConfigHttpResponse(200, "{")))
    }

    @Test
    fun missingFieldIsError() = runBlocking {
        assertEquals(VersionGateState.Error, check(current = 1, response = AppConfigHttpResponse(200, "{}")))
    }

    @Test
    fun invalidTypeIsError() = runBlocking {
        assertEquals(
            VersionGateState.Error,
            check(current = 1, response = AppConfigHttpResponse(200, "{\"android_min_version_code\":\"1\"}")),
        )
    }

    @Test
    fun nonPositiveMinimumIsError() = runBlocking {
        listOf(0, -1).forEach { minimum ->
            assertEquals(VersionGateState.Error, check(current = 1, response = validResponse(minimum)))
        }
    }

    @Test
    fun retryFetchesAgainAndCanBecomeSupported() = runBlocking {
        val responses = ArrayDeque(
            listOf(
                Result.failure<AppConfigHttpResponse>(IOException("offline")),
                Result.success(validResponse(1)),
            ),
        )
        var calls = 0
        val controller = VersionGateController(
            currentVersionCode = 1,
            fetcher = CloudflareAppConfigFetcher(transport = AppConfigHttpTransport {
                calls++
                responses.removeFirst().getOrThrow()
            }),
        )

        controller.check()
        assertEquals(VersionGateState.Error, controller.state.value)
        controller.check()

        assertEquals(2, calls)
        assertEquals(VersionGateState.Supported, controller.state.value)
    }

    @Test
    fun initialStateDoesNotPassTheGate() {
        val controller = VersionGateController(1) { 1 }

        assertIs<VersionGateState.Checking>(controller.state.value)
    }

    private suspend fun check(
        current: Int,
        response: AppConfigHttpResponse? = null,
        failure: Exception? = null,
    ): VersionGateState {
        val fetcher = CloudflareAppConfigFetcher(transport = AppConfigHttpTransport {
            failure?.let { throw it }
            requireNotNull(response)
        })
        return VersionGateController(current, fetcher).also { it.check() }.state.value
    }

    private fun validResponse(minimum: Int) = AppConfigHttpResponse(
        statusCode = 200,
        body = "{\"android_min_version_code\":$minimum}",
    )
}
