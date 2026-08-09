package com.example.othello.profile

data class Profile(
    val userId: String,
    val displayName: String,
    val currentRating: Int,
    val peakRating: Int,
    val stableRatingBand: String,
    val federationGrade: String? = null,
    val federationGradeVerified: Boolean = false,
)

interface ProfileRepository { suspend fun get(userId: String): Profile; suspend fun updateDisplayName(userId: String, displayName: String): Profile }
