package com.example.othello

import android.content.Context

import com.example.othello.auth.AuthGateway
import com.example.othello.auth.AuthSessionStatus
import com.example.othello.auth.SignUpResult
import com.example.othello.auth.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

internal enum class AuthStartupErrorKind { CONFIGURATION, SESSION_RESTORE }

internal sealed interface AuthState {
    data object Checking : AuthState
    data class Authenticated(val session: UserSession) : AuthState
    data object Unauthenticated : AuthState
    data class Error(val kind: AuthStartupErrorKind, val message: String) : AuthState
}

internal enum class AuthGateContent { CHECKING, AUTHENTICATED_APP, LOGIN, ERROR }

internal fun AuthState.gateContent(): AuthGateContent = when (this) {
    AuthState.Checking -> AuthGateContent.CHECKING
    is AuthState.Authenticated -> AuthGateContent.AUTHENTICATED_APP
    AuthState.Unauthenticated -> AuthGateContent.LOGIN
    is AuthState.Error -> AuthGateContent.ERROR
}

/** Owns the single transition point between startup auth, login, and the authenticated app. */
internal class AuthSessionController(
    private val gatewayResult: Result<AuthGateway>,
    private val context: Context? = null,
    private val onBeforeSignOut: suspend () -> Unit = {},
    private val onAuthenticatedSessionEnding: suspend () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Checking)
    private val transitionMutex = Mutex()
    private val sessionEventVersion = AtomicLong()
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    suspend fun runSessionLifecycle() {
        restoreSession()
        observeSessionLifecycle()
    }

    suspend fun restoreSession() {
        transitionMutex.withLock {
            if (mutableState.value is AuthState.Authenticated) return
            mutableState.value = AuthState.Checking
        }
        val eventVersionAtStart = sessionEventVersion.get()
        val gateway = gatewayResult.getOrElse {
            transitionMutex.withLock {
                mutableState.value = AuthState.Error(
                    AuthStartupErrorKind.CONFIGURATION,
                    context?.getString(R.string.auth_configuration_failed)
                        ?: "認証サービスを初期化できませんでした。アプリの設定を確認してください。",
                )
            }
            return
        }
        try {
            val session = gateway.currentSession()
            if (sessionEventVersion.get() != eventVersionAtStart) return
            if (session == null) {
                transitionMutex.withLock {
                    if (mutableState.value !is AuthState.Authenticated) {
                        mutableState.value = AuthState.Unauthenticated
                    }
                }
            } else {
                transitionToAuthenticated(gateway, session)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (sessionEventVersion.get() != eventVersionAtStart) return
            transitionMutex.withLock {
                if (mutableState.value !is AuthState.Authenticated) {
                    mutableState.value = AuthState.Error(
                        AuthStartupErrorKind.SESSION_RESTORE,
                        authErrorMessage(AuthOperation.SESSION, failure, context),
                    )
                }
            }
        }
    }

    private suspend fun observeSessionLifecycle() {
        val gateway = gatewayResult.getOrNull() ?: return
        gateway.sessionStatus.collect { status ->
            sessionEventVersion.incrementAndGet()
            when (status) {
                is AuthSessionStatus.Authenticated -> transitionToAuthenticated(gateway, status.session)
                AuthSessionStatus.Unauthenticated -> transitionToUnauthenticated()
                AuthSessionStatus.Initializing,
                AuthSessionStatus.Recovering,
                -> Unit
            }
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = withGateway { gateway ->
        val session = gateway.signIn(email, password)
        transitionToAuthenticated(gateway, session)
    }

    suspend fun signUp(email: String, password: String): Result<SignUpResult> {
        val gateway = gatewayResult.getOrElse { return Result.failure(it) }
        return runCatching { gateway.signUp(email, password) }
            .onSuccess { result ->
                if (result is SignUpResult.SignedIn) {
                    transitionToAuthenticated(gateway, result.session)
                }
            }
    }

    suspend fun requestPasswordReset(email: String): Result<Unit> = withGateway { gateway ->
        gateway.requestPasswordReset(email)
    }

    suspend fun signOut(): Result<Unit> {
        val result = withGateway { gateway ->
            bestEffort { onBeforeSignOut() }
            gateway.signOut()
        }
        if (result.isSuccess) transitionToUnauthenticated()
        return result
    }

    suspend fun finishAccountDeletionSession() {
        gatewayResult.getOrNull()?.let { gateway ->
            bestEffort { onBeforeSignOut() }
            try {
                gateway.signOut()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                bestEffort { gateway.clearLocalSession() }
            }
        }
        transitionToUnauthenticated()
    }

    private suspend fun transitionToAuthenticated(gateway: AuthGateway, session: UserSession) {
        transitionMutex.withLock {
            val previous = mutableState.value as? AuthState.Authenticated
            if (previous?.session == session) return
            if (previous != null) bestEffort { onAuthenticatedSessionEnding() }
            bestEffort { gateway.touchLastActive() }
            mutableState.value = AuthState.Authenticated(session)
        }
    }

    private suspend fun transitionToUnauthenticated() {
        transitionMutex.withLock {
            if (mutableState.value is AuthState.Authenticated) {
                bestEffort { onAuthenticatedSessionEnding() }
            }
            mutableState.value = AuthState.Unauthenticated
        }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Session state must still converge even when optional cleanup/activity RPCs fail.
        }
    }

    private suspend fun <T> withGateway(block: suspend (AuthGateway) -> T): Result<T> {
        val gateway = gatewayResult.getOrElse { return Result.failure(it) }
        return runCatching { block(gateway) }
    }
}
