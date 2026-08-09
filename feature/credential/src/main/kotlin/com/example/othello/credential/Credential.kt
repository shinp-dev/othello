package com.example.othello.credential

enum class CredentialStatus { SELF_DECLARED, PENDING, VERIFIED, REJECTED }
data class FederationCredential(
    val organization: String,
    val credentialType: String,
    val value: String,
    val status: CredentialStatus,
    val verifiedAtEpochMillis: Long?,
    val id: String? = null,
)
interface CredentialRepository {
    suspend fun selfDeclare(value: String): FederationCredential
    suspend fun submitVerification(credential: FederationCredential, evidencePath: String): FederationCredential
    suspend fun uploadEvidence(fileName: String, mimeType: String, bytes: ByteArray): String
}
