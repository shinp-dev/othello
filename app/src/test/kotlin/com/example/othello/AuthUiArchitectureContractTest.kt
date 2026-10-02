package com.example.othello

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AuthUiArchitectureContractTest {
    private val authGate = File("src/main/kotlin/com/example/othello/AuthGate.kt").readText()
    private val mainActivity = File("src/main/kotlin/com/example/othello/MainActivity.kt")
        .readText()
        .replace("\r\n", "\n")
    private val topLevelScreens = File("src/main/kotlin/com/example/othello/TopLevelScreens.kt").readText()
    private val accountScreen = File("src/main/kotlin/com/example/othello/AccountScreen.kt").readText()
    private val deepEnjoyScreen = File("src/main/kotlin/com/example/othello/DeepEnjoyScreen.kt").readText()
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
        assertTrue("peoplePlaySession = sessionOwner.peoplePlaySession" in authenticatedRootBody)
        assertTrue("AuthenticatedModeRoute(" in authenticatedRootBody)
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
    fun emailValidationIsShownAtTheInputAndKeptSeparateFromResetFailures() {
        assertTrue("isError = emailError != null" in authGate)
        assertTrue("supportingText = emailError?.let" in authGate)
        assertTrue("failure.emailInputErrorResource()" in authGate)

        val passwordReset = authGate.substringAfter("onPasswordReset = {")
            .substringBefore("        )\n    }")
        assertTrue("if (!showEmailInputError(it))" in passwordReset)
        assertTrue("R.string.reset_email_failed" in passwordReset)
    }

    @Test
    fun deepHomeContainsProductActionsAndNoAuthFormOrLogout() {
        assertFalse("メールアドレス" in deepEnjoyScreen)
        assertFalse("パスワード" in deepEnjoyScreen)
        assertFalse("ログイン済み" in deepEnjoyScreen)
        assertFalse("ログアウト" in deepEnjoyScreen)
        assertFalse("レート" in deepEnjoyScreen)
        assertTrue("R.string.play_online" in deepEnjoyScreen)
        assertTrue("R.string.two_player_match" in deepEnjoyScreen)
        assertTrue("R.string.play_against_ai" in deepEnjoyScreen)
        assertTrue("R.string.position_review" in deepEnjoyScreen)
        assertTrue("R.string.match_settings" in deepEnjoyScreen)
    }

    @Test
    fun accountShowsChanrivaIdentityAndKeepsDangerousActionsTogether() {
        assertTrue("R.string.account" in topLevelScreens)
        assertTrue("onLogout" in accountScreen)
        assertTrue("R.string.logout" in accountScreen)
        assertTrue("R.string.account_deletion" in accountScreen)
        assertTrue("R.string.account_chanriva_name" in accountScreen)
        assertTrue("derivePeoplePlayAvatar" in accountScreen)
        assertTrue("playProfileRepository.findCurrentUserProfile()" in accountScreen)
        assertFalse("R.string.current_rating" in accountScreen)
        assertFalse("R.string.previous_day_ranking" in accountScreen)
        assertFalse("R.string.best_local_record" in accountScreen)
        assertFalse("recordIfBetter" in accountScreen)
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
    fun advancedModeUsesOneDeepHomeInsteadOfFourBottomTabs() {
        val navigation = File("src/main/kotlin/com/example/othello/AppNavigation.kt").readText()
        val topLevelBody = navigation.substringAfter("internal val topLevelDestinations")
            .substringBefore("internal fun AppDestination.isTopLevel")
        assertEquals(1, Regex("AppDestination\\.").findAll(topLevelBody).count())
        assertTrue("AppDestination.PLAY" in topLevelBody)
        assertFalse("AppDestination.STUDY" in topLevelBody)
        assertFalse("AppDestination.SETTINGS" in topLevelBody)
        assertFalse("AppDestination.MORE" in topLevelBody)
        assertFalse("ChanrivaBottomNavigation(" in mainActivity.substringAfter("private fun AuthenticatedApp(")
            .substringBefore("@Composable\nprivate fun OnlineMatchScreen"))
        assertFalse("LOGIN" in navigation)
    }
}
}
