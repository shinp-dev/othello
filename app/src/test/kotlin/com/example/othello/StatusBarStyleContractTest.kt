package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StatusBarStyleContractTest {
    private val mainActivity = File("src/main/kotlin/com/example/othello/MainActivity.kt").readText()
    private val authGate = File("src/main/kotlin/com/example/othello/AuthGate.kt").readText()
    private val styles = File("src/main/res/values/styles.xml").readText()

    @Test
    fun statusBarInsetUsesTheAppBackgroundAcrossTheAuthGate() {
        val setContent = mainActivity.substringAfter("setContent {").substringBefore("@Composable\nprivate fun OthelloApp")

        assertTrue("windowInsetsTopHeight(WindowInsets.statusBars)" in setContent)
        assertTrue("background(MaterialTheme.colorScheme.background)" in setContent)
        assertTrue(setContent.indexOf("windowInsetsTopHeight") < setContent.indexOf("OthelloApp("))
    }

    @Test
    fun statusBarContentInsetsRemainInEveryRootScreen() {
        val statusBarsPaddingCall = Regex("""statusBarsPadding\(\)""")
        assertEquals(1, statusBarsPaddingCall.findAll(mainActivity).count())
        assertEquals(3, statusBarsPaddingCall.findAll(authGate).count())
    }

    @Test
    fun statusIconsStayLightAndNavigationBarStyleIsUnchanged() {
        assertTrue("<item name=\"android:statusBarColor\">@color/chanriva_background</item>" in styles)
        assertTrue("<item name=\"android:windowLightStatusBar\">false</item>" in styles)
        assertTrue("<item name=\"android:navigationBarColor\">@color/chanriva_surface_elevated</item>" in styles)
    }
}
