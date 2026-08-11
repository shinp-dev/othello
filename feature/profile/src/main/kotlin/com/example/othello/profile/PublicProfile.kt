package com.example.othello.profile

data class PublicProfile(
    val userId: String,
    val displayName: String,
    val currentRating: Int,
    val peakRating: Int,
    val stableRatingBand: String,
    val federationGrade: String?,
    val federationVerificationStatus: String?,
)

interface PublicProfileRepository { suspend fun getPublicProfile(userId: String): PublicProfile }
