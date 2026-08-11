package com.example.othello.profile

data class Profile(
    val userId: String,
    val displayName: String,
    val currentRating: Int,
    val peakRating: Int,
    val stableRatingBand: String,
    val federationGrade: String? = null,
    val federationGradeVerified: Boolean = false,
    val federationVerificationStatus: String? = null,
)

interface ProfileRepository { suspend fun get(userId: String): Profile; suspend fun updateDisplayName(userId: String, displayName: String): Profile }

interface AccountDeletionRepository {
    /** Queues a server-side deletion/anonymization request; Android never receives service-role authority. */
    suspend fun requestDeletion(): String
}
