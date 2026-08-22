package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AuthUiArchitectureContractTest {
    private val authGate = File("src/main/kotlin/com/example/othello/AuthGate.kt").readText()
    private val mainActivity = File("src/main/kotlin/com/example/othello/MainActivity.kt").readText()
    private val topLevelScreens = File("src/main/kotlin/com/example/othello/TopLevelScreens.kt").readText()
    private val analysisScreens = File("src/main/kotlin/com/example/othello/AnalysisScreens.kt").readText()
    private val betaScreens = File("src/main/kotlin/com/example/othello/BetaScreens.kt").readText()
    private val sessionOwner = File("src/main/kotlin/com/example/othello/OnlineSessionViewModel.kt").readText()

    @Test
    fun authGateWrapsTheAuthenticatedAppAndLoginHasNoBottomNavigation() {
        assertTrue("AuthGate(sessionOwner)" in mainActivity)
        assertTrue("private fun AuthenticatedApp(" in mainActivity)
        assertTrue("session: UserSession" in mainActivity)
        assertFalse("currentSession()" in mainActivity)

        val authGateBody = authGate.substringAfter("internal fun AuthGate(")
            .substringBefore("private fun LoginRoute")
        assertTrue("AuthState.Checking -> AuthCheckingScreen()" in authGateBody)
        assertTrue("AuthState.Unauthenticated -> LoginRoute" in authGateBody)
        assertTrue("is AuthState.Authenticated -> key(state.session.userId)" in authGateBody)
        assertTrue("authenticatedContent(state.session)" in authGateBody)
        assertFalse("ChanrivaBottomNavigation" in authGate)
    }

    @Test
    fun loginUsesExistingLauncherIconAndPurposeSpecificPasswordCopy() {
        assertTrue("R.mipmap.ic_launcher" in authGate)
        assertTrue("AndroidView" in authGate)
        assertTrue("\"パスワード\" else \"ちゃんりば用パスワード\"" in authGate)
        assertTrue("Gmailなどで使っているパスワードとは別のものを設定してください。" in authGate)
        assertTrue("確認メールを送信しました。メール内のリンクを開いてからログインしてください。" in authGate)
        assertTrue("登録済みの場合はメールをご確認ください。" in authGate)
        assertTrue("var busy" in authGate)
        assertTrue("enabled = !busy" in authGate)
    }

    @Test
    fun playScreenContainsOnlyMatchActionsAndNoAuthFormOrLogout() {
        val playBody = mainActivity.substringAfter("private fun PlayScreen(")
            .substringBefore("internal fun opponentRatingLabel")
        assertFalse("メールアドレス" in playBody)
        assertFalse("パスワード" in playBody)
        assertFalse("ログイン済み" in playBody)
        assertFalse("ログアウト" in playBody)
        assertTrue("オンライン対局" in playBody)
        assertTrue("ふたりで対局" in playBody)
        assertTrue("AIと対局" in playBody)
    }

    @Test
    fun logoutAndAuthenticatedOnlyActionsAreInNaturalScreens() {
        assertTrue("onLogout" in topLevelScreens)
        assertTrue("ログアウト" in topLevelScreens)
        assertFalse("ログインが必要" in topLevelScreens)
        assertFalse("ログインが必要" in analysisScreens)
        assertFalse("ログインするとオンライン棋譜" in betaScreens)
        assertTrue("userId: String" in betaScreens)
        assertTrue("repository: GameRecordRepository" in betaScreens)
        assertTrue("matchmaking?.reset()" in sessionOwner)
        assertTrue("leaveCoordinator()" in sessionOwner)
        assertTrue("onAuthenticatedSessionEnding" in sessionOwner)
    }

    @Test
    fun existingFourAuthenticatedTabsRemainUnchanged() {
        val navigation = File("src/main/kotlin/com/example/othello/AppNavigation.kt").readText()
        val topLevelBody = navigation.substringAfter("internal val topLevelDestinations")
            .substringBefore("internal fun AppDestination.isTopLevel")
        assertEquals(4, Regex("AppDestination\\.").findAll(topLevelBody).count())
        listOf("PLAY", "STUDY", "SETTINGS", "MORE").forEach {
            assertTrue("AppDestination.$it" in topLevelBody)
        }
        assertFalse("LOGIN" in navigation)
    }
}
