package com.example.othello.auth

data class UserSession(val userId: String, val displayName: String)

interface AuthGateway {
    suspend fun currentSession(): UserSession?
    suspend fun signIn(): UserSession
    suspend fun signOut()
}
