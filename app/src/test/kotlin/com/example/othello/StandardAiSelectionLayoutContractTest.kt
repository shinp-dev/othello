package com.example.othello

import java.io.File
import kotlin.test.assertFalse
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

    @Test
    fun standardMatchDoesNotOfferNewMatchDuringPlay() {
        val matchStart = source.indexOf("private fun StandardAiMatchScreen")
        val matchEnd = source.indexOf("private fun LocalGameRecord.humanOutcome", matchStart)
        val match = source.substring(matchStart, matchEnd)

        assertFalse("R.string.new_match" in match)
        assertTrue("R.string.standard_ai_exit_title" in match)
    }
}
