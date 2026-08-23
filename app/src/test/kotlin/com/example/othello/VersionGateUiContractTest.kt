package com.example.othello

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class VersionGateUiContractTest {
    private val versionGate = File("src/main/kotlin/com/example/othello/VersionGate.kt").readText()

    @Test
    fun unsupportedScreenUsesChanrivaUpdateGuidanceWithoutActions() {
        assertTrue("is VersionGateState.Unsupported -> VersionGateUnsupportedScreen()" in versionGate)

        val unsupportedScreen = versionGate.substringAfter("private fun VersionGateUnsupportedScreen()")
            .substringBefore("@Composable\nprivate fun VersionGateMessageScreen")
        assertTrue("R.mipmap.ic_launcher" in unsupportedScreen)
        assertTrue("Modifier.size(96.dp)" in unsupportedScreen)
        assertTrue("R.string.update_required" in unsupportedScreen)
        assertTrue("R.string.unsupported_version" in unsupportedScreen)
        assertFalse("Button(" in unsupportedScreen)
        assertFalse("再試行" in unsupportedScreen)
    }
}
