package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import org.junit.Test

class ReviewNavigationUiContractTest {
    private val betaScreens = File("src/main/kotlin/com/example/othello/BetaScreens.kt").readText()

    @Test
    fun bothReviewScreensBindEveryNavigationButtonToSessionState() {
        assertEquals(2, betaScreens.countOccurrences("enabled = navigation.canGoToFirst"))
        assertEquals(2, betaScreens.countOccurrences("enabled = navigation.canGoToPrevious"))
        assertEquals(2, betaScreens.countOccurrences("enabled = navigation.canGoToNext"))
        assertEquals(2, betaScreens.countOccurrences("enabled = navigation.canGoToLast"))
    }

    private fun String.countOccurrences(value: String): Int =
        windowed(value.length).count { it == value }
}
