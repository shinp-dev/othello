package com.example.othello

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StandardContentRemoteRefreshGateTest {
    @Test
    fun runsSuccessfulRefreshOnlyOnce() = runBlocking {
        var calls = 0
        val gate = StandardContentRemoteRefreshGate {
            calls += 1
        }

        gate.runOnceBestEffort()
        gate.runOnceBestEffort()

        assertEquals(1, calls)
    }

    @Test
    fun ordinaryFailureKeepsCurrentContentAndIsNotRetriedInSameProcess() = runBlocking {
        var calls = 0
        val gate = StandardContentRemoteRefreshGate {
            calls += 1
            throw IOException("offline")
        }

        gate.runOnceBestEffort()
        gate.runOnceBestEffort()

        assertEquals(1, calls)
    }

    @Test
    fun cancellationCanBeRetried() {
        var calls = 0
        var cancel = true
        val gate = StandardContentRemoteRefreshGate {
            calls += 1
            if (cancel) {
                cancel = false
                throw CancellationException("cancelled")
            }
        }

        assertFailsWith<CancellationException> {
            runBlocking { gate.runOnceBestEffort() }
        }
        runBlocking { gate.runOnceBestEffort() }

        assertEquals(2, calls)
    }
}
