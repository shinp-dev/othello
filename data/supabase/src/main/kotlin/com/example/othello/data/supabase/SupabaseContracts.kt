package com.example.othello.data.supabase

/** Supabase SDK adapters belong here; domain modules consume feature ports instead. */
data class SupabaseConfig(val url: String, val anonKey: String)
data class SignalingEnvelope(val matchId: String, val type: String, val payload: String)

interface SupabaseSignalingDataSource {
    suspend fun publish(envelope: SignalingEnvelope)
    fun subscribe(matchId: String, onEnvelope: (SignalingEnvelope) -> Unit): AutoCloseable
}
