package com.example.othello

import com.example.othello.auth.AuthGateway
import com.example.othello.auth.AuthSessionStatus
import com.example.othello.auth.SignUpResult
import com.example.othello.auth.UserSession
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.fail
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AuthSessionControllerTest {
    @Test
    fun initialStateIsCheckingAndGateShowsNeitherLoginNorApp() {
        val controller = AuthSessionController(Result.success(FakeAuthGateway()))

        assertEquals(AuthState.Checking, controller.state.value)
        assertEquals(AuthGateContent.CHECKING, controller.state.value.gateContent())
    }

    @Test
    fun restoredSessionTouchesActivityAndEntersAppWithoutLogin() = runBlocking {
        val gateway = FakeAuthGateway(current = UserSession("restored-user"))
        val controller = AuthSessionController(Result.success(gateway))

        controller.restoreSession()

        assertEquals(UserSession("restored-user"), assertIs<AuthState.Authenticated>(controller.state.value).session)
        assertEquals(AuthGateContent.AUTHENTICATED_APP, controller.state.value.gateContent())
        assertEquals(1, gateway.currentSessionCalls)
        assertEquals(1, gateway.touchCalls)
        assertEquals(0, gateway.emailSignInCalls)
    }

    @Test
    fun initializationDoesNotBecomeUnauthenticatedBeforeRestoreCompletes() = runBlocking {
        val restored = CompletableDeferred<UserSession?>()
        val gateway = FakeAuthGateway(
            status = AuthSessionStatus.Initializing,
            currentSessionBlock = { restored.await() },
        )
        val controller = AuthSessionController(Result.success(gateway))

        val restore = launch { controller.restoreSession() }
        delay(20)
        assertEquals(AuthState.Checking, controller.state.value)

        restored.complete(UserSession("cold-start-user"))
        restore.join()
        assertEquals(
            UserSession("cold-start-user"),
            assertIs<AuthState.Authenticated>(controller.state.value).session,
        )
        assertEquals(1, gateway.touchCalls)
    }

    @Test
    fun missingSessionBecomesUnauthenticatedAndShowsLogin() = runBlocking {
        val controller = AuthSessionController(Result.success(FakeAuthGateway(current = null)))

        controller.restoreSession()

        assertEquals(AuthState.Unauthenticated, controller.state.value)
        assertEquals(AuthGateContent.LOGIN, controller.state.value.gateContent())
    }

    @Test
    fun componentFailureIsConfigurationErrorNotLoginFailure() = runBlocking {
        val controller = AuthSessionController(
            Result.failure(IllegalStateException("missing Supabase configuration")),
        )

        controller.restoreSession()

        val error = assertIs<AuthState.Error>(controller.state.value)
        assertEquals(AuthStartupErrorKind.CONFIGURATION, error.kind)
        assertEquals(AuthGateContent.ERROR, error.gateContent())
        assertFalse("パスワード" in error.message)
    }

    @Test
    fun sessionRestoreExceptionIsDistinctFromNoSession() = runBlocking {
        val gateway = FakeAuthGateway(currentFailure = IllegalStateException("network unavailable"))
        val controller = AuthSessionController(Result.success(gateway))

        controller.restoreSession()

        val error = assertIs<AuthState.Error>(controller.state.value)
        assertEquals(AuthStartupErrorKind.SESSION_RESTORE, error.kind)
        assertEquals(AuthGateContent.ERROR, error.gateContent())
    }

    @Test
    fun loginSuccessTouchesActivityAndLoginFailureStaysUnauthenticated() = runBlocking {
        val gateway = FakeAuthGateway(current = null, signedIn = UserSession("login-user"))
        val controller = AuthSessionController(Result.success(gateway))
        controller.restoreSession()

        assertTrue(controller.signIn("person@example.test", "password").isSuccess)
        assertEquals(UserSession("login-user"), assertIs<AuthState.Authenticated>(controller.state.value).session)
        assertEquals(1, gateway.emailSignInCalls)
        assertEquals(1, gateway.touchCalls)

        val failingGateway = FakeAuthGateway(
            current = null,
            signInFailure = IllegalArgumentException("invalid_credentials"),
        )
        val failingController = AuthSessionController(Result.success(failingGateway))
        failingController.restoreSession()
        assertTrue(failingController.signIn("person@example.test", "bad-password").isFailure)
        assertEquals(AuthState.Unauthenticated, failingController.state.value)
        assertEquals(0, failingGateway.touchCalls)
    }

    @Test
    fun invalidEmailDoesNotCallLoginGateway() = runBlocking {
        val gateway = FakeAuthGateway(current = null)
        val controller = AuthSessionController(Result.success(gateway))
        controller.restoreSession()

        val result = controller.signIn("name", "password")

        assertIs<InvalidEmailAddressException>(result.exceptionOrNull())
        assertEquals(0, gateway.emailSignInCalls)
        assertEquals(AuthState.Unauthenticated, controller.state.value)
    }

    @Test
    fun signedInSignupTouchesActivityWhileConfirmationRequiredStaysOnLogin() = runBlocking {
        val signedInGateway = FakeAuthGateway(
            current = null,
            signUpResult = SignUpResult.SignedIn(UserSession("new-user")),
        )
        val signedInController = AuthSessionController(Result.success(signedInGateway))
        signedInController.restoreSession()

        assertTrue(signedInController.signUp("new@example.test", "new-password").isSuccess)
        assertIs<AuthState.Authenticated>(signedInController.state.value)
        assertEquals(1, signedInGateway.touchCalls)

        val confirmationGateway = FakeAuthGateway(
            current = null,
            signUpResult = SignUpResult.EmailConfirmationRequired,
        )
        val confirmationController = AuthSessionController(Result.success(confirmationGateway))
        confirmationController.restoreSession()
        assertEquals(
            SignUpResult.EmailConfirmationRequired,
            confirmationController.signUp("confirm@example.test", "new-password").getOrThrow(),
        )
        assertEquals(AuthState.Unauthenticated, confirmationController.state.value)
        assertEquals(0, confirmationGateway.touchCalls)
    }

    @Test
    fun failedSignupAndPasswordResetNeverAuthenticate() = runBlocking {
        val gateway = FakeAuthGateway(
            current = null,
            signUpFailure = IllegalStateException("signup failed"),
            passwordResetFailure = IllegalStateException("reset failed"),
        )
        val controller = AuthSessionController(Result.success(gateway))
        controller.restoreSession()

        assertTrue(controller.signUp("new@example.test", "new-password").isFailure)
        assertEquals(AuthState.Unauthenticated, controller.state.value)
        assertTrue(controller.requestPasswordReset("new@example.test").isFailure)
        assertEquals(1, gateway.passwordResetCalls)
        assertEquals(AuthState.Unauthenticated, controller.state.value)
    }

    @Test
    fun passwordResetUsesExistingGatewayAndLogoutClearsSessionAfterCleanup() = runBlocking {
        val gateway = FakeAuthGateway(current = UserSession("user"))
        var cleanupCalls = 0
        val controller = AuthSessionController(Result.success(gateway)) { cleanupCalls++ }
        controller.restoreSession()

        assertTrue(controller.requestPasswordReset("person@example.test").isSuccess)
        assertEquals(1, gateway.passwordResetCalls)
        assertTrue(controller.signOut().isSuccess)
        assertEquals(1, gateway.signOutCalls)
        assertEquals(1, cleanupCalls)
        assertEquals(AuthState.Unauthenticated, controller.state.value)
    }

    @Test
    fun invalidEmailDoesNotCallPasswordResetGateway() = runBlocking {
        val gateway = FakeAuthGateway(current = null)
        val controller = AuthSessionController(Result.success(gateway))
        controller.restoreSession()

        val result = controller.requestPasswordReset("name")

        assertIs<InvalidEmailAddressException>(result.exceptionOrNull())
        assertEquals(0, gateway.passwordResetCalls)
        assertEquals(AuthState.Unauthenticated, controller.state.value)
    }

    @Test
    fun failedLogoutDoesNotExposeAuthenticatedAppAsLoggedOut() = runBlocking {
        val gateway = FakeAuthGateway(
            current = UserSession("user"),
            signOutFailure = IllegalStateException("sign out failed"),
        )
        var cleanupCalls = 0
        val controller = AuthSessionController(Result.success(gateway)) { cleanupCalls++ }
        controller.restoreSession()

        assertTrue(controller.signOut().isFailure)
        assertIs<AuthState.Authenticated>(controller.state.value)
        assertEquals(0, cleanupCalls)
    }

    @Test
    fun runtimeTransientStatusesDoNotLogoutButTerminalLossDoes() = runBlocking {
        val session = UserSession("runtime-user")
        val gateway = FakeAuthGateway(current = session)
        var cleanupCalls = 0
        val controller = AuthSessionController(Result.success(gateway)) { cleanupCalls++ }
        val lifecycle = launch { controller.runSessionLifecycle() }
        awaitState { controller.state.value is AuthState.Authenticated }

        gateway.emit(AuthSessionStatus.Initializing)
        gateway.emit(AuthSessionStatus.Recovering)
        delay(20)
        assertIs<AuthState.Authenticated>(controller.state.value)
        assertEquals(0, cleanupCalls)

        gateway.emit(AuthSessionStatus.Unauthenticated)
        awaitState { controller.state.value == AuthState.Unauthenticated }
        assertEquals(1, cleanupCalls)
        lifecycle.cancelAndJoin()
    }

    @Test
    fun runtimeAuthenticatedEventCanRecoverAStartupRestoreError() = runBlocking {
        val gateway = FakeAuthGateway(
            currentFailure = IllegalStateException("refresh temporarily unavailable"),
            status = AuthSessionStatus.Recovering,
        )
        val controller = AuthSessionController(Result.success(gateway))
        val lifecycle = launch { controller.runSessionLifecycle() }
        awaitState { controller.state.value is AuthState.Error }

        gateway.emit(AuthSessionStatus.Authenticated(UserSession("recovered-user")))
        awaitState { controller.state.value is AuthState.Authenticated }
        assertEquals(1, gateway.touchCalls)
        lifecycle.cancelAndJoin()
    }

    @Test
    fun successfulRuntimeRecoveryIsNotOverwrittenByAStaleRetryFailure() = runBlocking {
        val attempts = AtomicInteger()
        val retryResult = CompletableDeferred<UserSession?>()
        val gateway = FakeAuthGateway(
            status = AuthSessionStatus.Recovering,
            currentSessionBlock = {
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("initial refresh failure")
                }
                retryResult.await()
            },
        )
        val controller = AuthSessionController(Result.success(gateway))
        val lifecycle = launch { controller.runSessionLifecycle() }
        awaitState { controller.state.value is AuthState.Error }

        val retry = launch { controller.restoreSession() }
        awaitState { controller.state.value == AuthState.Checking }
        gateway.emit(AuthSessionStatus.Authenticated(UserSession("recovered-user")))
        awaitState { controller.state.value is AuthState.Authenticated }
        retryResult.completeExceptionally(IllegalStateException("stale retry failure"))
        retry.join()

        assertEquals(
            UserSession("recovered-user"),
            assertIs<AuthState.Authenticated>(controller.state.value).session,
        )
        lifecycle.cancelAndJoin()
    }

    @Test
    fun terminalRuntimeLossIsNotOverwrittenByAStaleRetrySuccess() = runBlocking {
        val attempts = AtomicInteger()
        val retryResult = CompletableDeferred<UserSession?>()
        val gateway = FakeAuthGateway(
            status = AuthSessionStatus.Recovering,
            currentSessionBlock = {
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("initial refresh failure")
                }
                retryResult.await()
            },
        )
        val controller = AuthSessionController(Result.success(gateway))
        val lifecycle = launch { controller.runSessionLifecycle() }
        awaitState { controller.state.value is AuthState.Error }

        val retry = launch { controller.restoreSession() }
        awaitState { controller.state.value == AuthState.Checking }
        gateway.emit(AuthSessionStatus.Unauthenticated)
        awaitState { controller.state.value == AuthState.Unauthenticated }
        retryResult.complete(UserSession("stale-session"))
        retry.join()

        assertEquals(AuthState.Unauthenticated, controller.state.value)
        lifecycle.cancelAndJoin()
    }

    @Test
    fun logoutRunsRemoteQueueCleanupBeforeSdkSignOut() = runBlocking {
        val events = mutableListOf<String>()
        val gateway = FakeAuthGateway(
            current = UserSession("user"),
            onSignOut = { events += "sdk-sign-out" },
        )
        val controller = AuthSessionController(
            gatewayResult = Result.success(gateway),
            onBeforeSignOut = { events += "queue-cleanup" },
        )
        controller.restoreSession()

        assertTrue(controller.signOut().isSuccess)
        assertEquals(listOf("queue-cleanup", "sdk-sign-out"), events)
    }

    @Test
    fun sdkSignOutEventAndExplicitLogoutShareOneAuthenticatedCleanup() = runBlocking {
        val gateway = FakeAuthGateway(current = UserSession("user"))
        var cleanupCalls = 0
        val controller = AuthSessionController(Result.success(gateway)) { cleanupCalls++ }
        val lifecycle = launch { controller.runSessionLifecycle() }
        awaitState { controller.state.value is AuthState.Authenticated }

        assertTrue(controller.signOut().isSuccess)
        awaitState { controller.state.value == AuthState.Unauthenticated }
        assertEquals(1, cleanupCalls)
        lifecycle.cancelAndJoin()
    }

    @Test
    fun acceptedAccountDeletionAlwaysClearsLocalAuthenticatedState() = runBlocking {
        val gateway = FakeAuthGateway(
            current = UserSession("deleting-user"),
            signOutFailure = IllegalStateException("session already invalidated"),
        )
        var cleanupCalls = 0
        val controller = AuthSessionController(Result.success(gateway)) { cleanupCalls++ }
        controller.restoreSession()

        controller.finishAccountDeletionSession()

        assertEquals(1, gateway.signOutCalls)
        assertEquals(1, gateway.clearLocalSessionCalls)
        assertEquals(1, cleanupCalls)
        assertEquals(AuthState.Unauthenticated, controller.state.value)
    }

    private suspend fun awaitState(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            delay(10)
        }
        fail("Auth state did not reach the expected value")
    }

    private class FakeAuthGateway(
        private val current: UserSession? = null,
        private val signedIn: UserSession = UserSession("signed-in"),
        private val signUpResult: SignUpResult = SignUpResult.EmailConfirmationRequired,
        private val currentFailure: Throwable? = null,
        private val signInFailure: Throwable? = null,
        private val signUpFailure: Throwable? = null,
        private val passwordResetFailure: Throwable? = null,
        private val signOutFailure: Throwable? = null,
        status: AuthSessionStatus = current?.let(AuthSessionStatus::Authenticated)
            ?: AuthSessionStatus.Unauthenticated,
        private val currentSessionBlock: (suspend () -> UserSession?)? = null,
        private val onSignOut: () -> Unit = {},
    ) : AuthGateway {
        private val mutableSessionStatus = MutableStateFlow(status)
        override val sessionStatus = mutableSessionStatus
        var currentSessionCalls = 0
        var emailSignInCalls = 0
        var touchCalls = 0
        var passwordResetCalls = 0
        var signOutCalls = 0
        var clearLocalSessionCalls = 0

        fun emit(status: AuthSessionStatus) {
            mutableSessionStatus.value = status
        }

        override suspend fun currentSession(): UserSession? {
            currentSessionCalls++
            currentFailure?.let { throw it }
            return currentSessionBlock?.invoke() ?: current
        }

        override suspend fun signIn(email: String, password: String): UserSession {
            emailSignInCalls++
            signInFailure?.let { throw it }
            return signedIn
        }

        override suspend fun signUp(email: String, password: String): SignUpResult {
            signUpFailure?.let { throw it }
            return signUpResult
        }

        override suspend fun requestPasswordReset(email: String) {
            passwordResetCalls++
            passwordResetFailure?.let { throw it }
        }

        override suspend fun touchLastActive() {
            touchCalls++
        }

        override suspend fun signIn(): UserSession = signedIn

        override suspend fun signOut() {
            signOutCalls++
            signOutFailure?.let { throw it }
            onSignOut()
            mutableSessionStatus.value = AuthSessionStatus.Unauthenticated
        }

        override suspend fun clearLocalSession() {
            clearLocalSessionCalls++
            mutableSessionStatus.value = AuthSessionStatus.Unauthenticated
        }
    }
}
