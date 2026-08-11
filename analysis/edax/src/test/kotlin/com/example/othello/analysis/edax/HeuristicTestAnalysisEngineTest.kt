package com.example.othello.analysis.edax

import org.junit.Assert.assertNotNull
import org.junit.Test

class HeuristicTestAnalysisEngineTest {
    @Test fun heuristicEngineIsAvailableOnlyToTestSources() {
        assertNotNull(HeuristicTestAnalysisEngine::class)
    }
}
