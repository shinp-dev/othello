package com.example.othello

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

class StandardAiSelectionLayoutContractTest {
    private val source = File("src/main/kotlin/com/example/othello/StandardAiScreens.kt").readText()

    @Test
    fun levelSelectionCentersChallengeContentBelowFixedHeader() {
        val selectionStart = source.indexOf("internal fun StandardAiLevelSelectionContent")
        val selectionEnd = source.indexOf("private fun StandardAiMatchScreen", selectionStart)
        val selection = source.substring(selectionStart, selectionEnd)

        assertTrue("ChanrivaScreenHeader(" in selection)
        assertTrue(".weight(1f)" in selection)
        assertTrue("contentAlignment = Alignment.Center" in selection)
        assertTrue("StandardOpponentSelectionPanel(" in selection)
        assertTrue("Surface(Modifier.fillMaxSize().statusBarsPadding())" in selection)
    }
}
