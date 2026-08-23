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
    private val accountScreen = File("src/main/kotlin/com/example/othello/AccountScreen.kt").readText()
    private val analysisScreens = File("src/main/kotlin/com/example/othello/AnalysisScreens.kt").readText()
    private val betaScreens = File("src/main/kotlin/com/example/othello/BetaScreens.kt").readText()
    private val sessionOwner = File("src/main/kotlin/com/example/othello/OnlineSessionViewModel.kt").readText()
    private val versionGate = File("src/main/kotlin/com/example/othello/VersionGate.kt").readText()

    @Test
    fun authGateWrapsTheAuthenticatedAppAndLoginHasNoBottomNavigation() {
        val versionRootBody = mainActivity.substringAfter("private fun OthelloApp(")
            .substringBefore("@Composable\nprivate fun AuthenticatedRoot")
        val authenticatedRootBody = mainActivity.substringAfter("private fun AuthenticatedRoot(")
            .substringBefore("@Composable\nprivate fun AuthenticatedApp")
        assertTrue("VersionGate(versionGateOwner)" in versionRootBody)
        assertTrue("AuthenticatedRoot(" in versionRootBody)
        assertFalse("AuthGate(" in versionRootBody)
        assertTrue("sessionOwner: OnlineSessionViewModel = viewModel()" in authenticatedRootBody)
        assertTrue("AuthGate(sessionOwner)" in authenticatedRootBody)
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
        assertTrue("VersionGateState.Supported -> supportedContent()" in versionGate)
    }

    @Test
    fun loginUsesExistingLauncherIconAndPurposeSpecificPasswordCopy() {
        assertTrue("R.mipmap.ic_launcher" in authGate)
        assertTrue("AndroidView" in authGate)
        assertTrue("R.string.password else R.string.chanriva_password" in authGate)
        assertTrue("R.string.password_guidance" in authGate)
        assertTrue("R.string.confirmation_email_sent" in authGate)
        assertTrue("R.string.reset_email_sent" in authGate)
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
        assertFalse("レート" in playBody)
        assertFalse("端末内最高" in playBody)
        assertTrue("R.string.online_match" in playBody)
        assertTrue("R.string.two_player_match" in playBody)
        assertTrue("R.string.play_against_ai" in playBody)
    }

    @Test
    fun logoutAndAuthenticatedOnlyActionsAreInNaturalScreens() {
        assertTrue("R.string.account" in topLevelScreens)
        assertTrue("onLogout" in accountScreen)
        assertTrue("R.string.logout" in accountScreen)
        assertTrue("R.string.current_rating" in accountScreen)
        assertFalse("\"昨日\"" in accountScreen)
        assertTrue("R.string.previous_day_ranking" in accountScreen)
        assertTrue("R.string.rating_previous_day_note" in accountScreen)
        assertTrue("R.string.best_local_record" in accountScreen)
        assertTrue("R.string.best_record_note" in accountScreen)
        assertTrue("recordIfBetter" in accountScreen)
        assertFalse("recordIfBetter" in mainActivity)
        val otherAppSources = File("src/main/kotlin/com/example/othello")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name in setOf("AccountScreen.kt", "RatingAchievementStore.kt") }
            .joinToString("\n") { it.readText() }
        assertFalse("recordIfBetter(" in otherAppSources)
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
