package com.example.othello.profile

interface AccountDeletionRepository {
    /** Queues a server-side deletion/anonymization request; Android never receives service-role authority. */
    suspend fun requestDeletion(): String
}
