package com.example.othello.auth

import kotlinx.coroutines.flow.Flow

data class UserSession(val userId: String)

sealed interface SignUpResult {
    data class SignedIn(val session: UserSession) : SignUpResult
    data object EmailConfirmationRequired : SignUpResult
}

/** SDK-independent view of the authentication session lifecycle. */
sealed interface AuthSessionStatus {
    data object Initializing : AuthSessionStatus
    data class Authenticated(val session: UserSession) : AuthSessionStatus
    data object Unauthenticated : AuthSessionStatus
    data object Recovering : AuthSessionStatus
}

interface AuthGateway {
    val sessionStatus: Flow<AuthSessionStatus>
    suspend fun currentSession(): UserSession?
    suspend fun signIn(email: String, password: String): UserSession
    suspend fun signUp(email: String, password: String): SignUpResult
    suspend fun requestPasswordReset(email: String)
    suspend fun touchLastActive()
    suspend fun signIn(): UserSession
    suspend fun signOut()
    suspend fun clearLocalSession()
}
