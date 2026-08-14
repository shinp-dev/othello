package com.example.othello

import kotlin.test.assertEquals
import org.junit.Test

class AnalysisFileSizeTest {
    @Test
    fun subKibibyteBookDoesNotDisplayAsZeroKibibytes() {
        assertEquals("42 B（1 KiB未満）", formatAnalysisFileSize(42L))
    }

    @Test
    fun evaluationDataUsesReadableMebibytes() {
        assertEquals("13.3 MiB", formatAnalysisFileSize(13_952_436L))
    }
}
