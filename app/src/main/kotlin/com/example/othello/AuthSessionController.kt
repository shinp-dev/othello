package com.example.othello

import com.example.othello.auth.AuthGateway
import com.example.othello.auth.SignUpResult
import com.example.othello.auth.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val onAuthenticatedSessionEnding: suspend () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Checking)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    suspend fun restoreSession() {
        mutableState.value = AuthState.Checking
        val gateway = gatewayResult.getOrElse {
            mutableState.value = AuthState.Error(
                AuthStartupErrorKind.CONFIGURATION,
                "認証サービスを初期化できませんでした。アプリの設定を確認してください。",
            )
            return
        }
        runCatching { gateway.currentSession() }
            .onSuccess { session ->
                if (session == null) {
                    mutableState.value = AuthState.Unauthenticated
                } else {
                    runCatching { gateway.touchLastActive() }
                    mutableState.value = AuthState.Authenticated(session)
                }
            }
            .onFailure {
                mutableState.value = AuthState.Error(
                    AuthStartupErrorKind.SESSION_RESTORE,
                    authErrorMessage(AuthOperation.SESSION, it),
                )
            }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = withGateway { gateway ->
        val session = gateway.signIn(email, password)
        runCatching { gateway.touchLastActive() }
        mutableState.value = AuthState.Authenticated(session)
    }

    suspend fun signUp(email: String, password: String): Result<SignUpResult> {
        val gateway = gatewayResult.getOrElse { return Result.failure(it) }
        return runCatching { gateway.signUp(email, password) }
            .onSuccess { result ->
                if (result is SignUpResult.SignedIn) {
                    runCatching { gateway.touchLastActive() }
                    mutableState.value = AuthState.Authenticated(result.session)
                }
            }
    }

    suspend fun requestPasswordReset(email: String): Result<Unit> = withGateway { gateway ->
        gateway.requestPasswordReset(email)
    }

    suspend fun signOut(): Result<Unit> {
        val result = withGateway { gateway -> gateway.signOut() }
        if (result.isSuccess) endAuthenticatedSession()
        return result
    }

    suspend fun finishAccountDeletionSession() {
        gatewayResult.getOrNull()?.let { gateway -> runCatching { gateway.signOut() } }
        endAuthenticatedSession()
    }

    private suspend fun endAuthenticatedSession() {
        runCatching { onAuthenticatedSessionEnding() }
        mutableState.value = AuthState.Unauthenticated
    }

    private suspend fun <T> withGateway(block: suspend (AuthGateway) -> T): Result<T> {
        val gateway = gatewayResult.getOrElse { return Result.failure(it) }
        return runCatching { block(gateway) }
    }
}
