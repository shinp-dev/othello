package com.example.othello.auth

data class UserSession(val userId: String, val displayName: String)

sealed interface SignUpResult {
    data class SignedIn(val session: UserSession) : SignUpResult
    data object EmailConfirmationRequired : SignUpResult
}

interface AuthGateway {
    suspend fun currentSession(): UserSession?
    suspend fun signIn(email: String, password: String): UserSession
    suspend fun signUp(email: String, password: String): SignUpResult
    suspend fun signIn(): UserSession
    suspend fun signOut()
}
