package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ModeSelectionUiContractTest {
    private val screen = File("src/main/kotlin/com/example/othello/ModeSelectionScreen.kt").readText()

    @Test
    fun choicesHavePurposeLedLabelsAndSeparateRectangularActions() {
        assertTrue("R.string.enjoy_casually" in screen)
        assertTrue("R.string.enjoy_together" in screen)
        assertTrue("R.string.enjoy_deeply" in screen)
        assertTrue("onRealEvent" in screen)
        assertTrue(".clickable(role = Role.Button" in screen)
        assertTrue(".verticalScroll(rememberScrollState())" in screen)
        assertFalse("R.string.standard_mode" in screen)
        assertFalse("R.string.advanced_mode" in screen)
    }

    @Test
    fun previewCoversBothRequestedWidths() {
        assertTrue("widthDp = 360" in screen)
        assertTrue("widthDp = 390" in screen)
    }
}
