package com.example.othello.data.supabase

import com.example.othello.auth.AuthGateway
import com.example.othello.auth.UserSession
import com.example.othello.credential.CredentialRepository
import com.example.othello.credential.CredentialStatus
import com.example.othello.credential.FederationCredential
import com.example.othello.match.MatchFinishResult
import com.example.othello.match.MatchStartAck
import com.example.othello.match.MatchSubmission
import com.example.othello.match.OnlineMatchRepository
import com.example.othello.matchmaking.AssignedDisc
import com.example.othello.matchmaking.EnqueueResult
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.matchmaking.MatchmakingRepository
import com.example.othello.network.CURRENT_PROTOCOL_VERSION
import com.example.othello.profile.Profile
import com.example.othello.profile.ProfileRepository
import com.example.othello.records.FinishReason
import com.example.othello.records.GameRecord
import com.example.othello.records.GameRecordRepository
import com.example.othello.records.MatchResult
import com.example.othello.game.CanonicalMoves
import com.example.othello.game.Disc
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.*
import io.github.jan.supabase.postgrest.query.*
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.*
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.*
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.http.ContentType
import java.time.Instant

data class SupabaseConfig(val url: String, val anonKey: String) {
    fun validate(): Result<SupabaseConfig> = if (url.isBlank() || anonKey.isBlank()) {
        Result.failure(SupabaseConfigurationException("Supabase URL/anon key is not configured. Set supabase.url and supabase.anonKey in local.properties."))
    } else Result.success(this)
}

class SupabaseConfigurationException(message: String) : IllegalStateException(message)

object SupabaseClientFactory {
    fun config(): SupabaseConfig = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    fun create(config: SupabaseConfig = config()): Result<SupabaseClient> = runCatching {
        config.validate().getOrThrow()
        createSupabaseClient(config.url, config.anonKey) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }
}

@Serializable
private data class EnqueueRow(
    @SerialName("match_id") val matchId: String? = null,
    val matched: Boolean,
    @SerialName("opponent_id") val opponentId: String? = null,
    @SerialName("assigned_disc") val assignedDisc: String? = null,
)

class SupabaseMatchmakingRepository(private val client: SupabaseClient) : MatchmakingRepository {
    override suspend fun enqueueOrMatch(): EnqueueResult {
        val row = client.postgrest.rpc("enqueue_or_match").decodeList<EnqueueRow>().single()
        return if (!row.matched) EnqueueResult.Waiting else EnqueueResult.Matched(
            MatchAssignment(
                matchId = requireNotNull(row.matchId),
                opponentId = requireNotNull(row.opponentId),
                assignedDisc = when (row.assignedDisc) {
                    "BLACK" -> AssignedDisc.BLACK
                    "WHITE" -> AssignedDisc.WHITE
                    else -> error("invalid assigned disc")
                },
            ),
        )
    }

    override suspend fun cancelWaiting(): Boolean = client.postgrest.rpc("cancel_waiting").decodeSingle()
    override suspend fun heartbeatWaiting(): Boolean = client.postgrest.rpc("heartbeat_waiting").decodeSingle()
    override suspend fun claimMatchedAssignment(): MatchAssignment? = client.postgrest.rpc("claim_waiting_match")
        .decodeList<EnqueueRow>().firstOrNull()?.let { row ->
            MatchAssignment(
                requireNotNull(row.matchId), requireNotNull(row.opponentId),
                if (row.assignedDisc == "BLACK") AssignedDisc.BLACK else AssignedDisc.WHITE,
            )
        }
    override suspend fun reconcileCallerActiveMatch(): Boolean = client.postgrest.rpc("reconcile_caller_active_match").decodeSingle()
}

@Serializable
private data class AckParams(@SerialName("p_match_id") val matchId: String)

@Serializable
private data class SubmitResultParams(
    @SerialName("p_match_id") val matchId: String,
    @SerialName("p_canonical_moves") val canonicalMoves: String,
    @SerialName("p_result") val result: String,
    @SerialName("p_final_position_hash") val finalPositionHash: String,
    @SerialName("p_finish_reason") val finishReason: String,
    @SerialName("p_clock") val clock: String? = null,
)

class SupabaseOnlineMatchRepository(private val client: SupabaseClient) : OnlineMatchRepository {
    override suspend fun ackMatchStarted(matchId: String): MatchStartAck = MatchStartAck(
        serverStatus = client.postgrest.rpc("ack_match_started", AckParams(matchId)).decodeSingle(),
    )

    override suspend fun abandonMatch(matchId: String): Boolean = client.postgrest.rpc("abandon_match", AckParams(matchId)).decodeSingle<String>().isNotBlank()

    override suspend fun submitMatchResult(submission: MatchSubmission): MatchFinishResult {
        return MatchFinishResult(
            serverStatus = client.postgrest.rpc(
                "submit_match_result",
                SubmitResultParams(
                    submission.matchId,
                    submission.canonicalMoves,
                    submission.result.name,
                    submission.finalPositionHash,
                    submission.finishReason.name,
                    submission.clockPayload,
                ),
            ).decodeSingle(),
        )
    }
}

class SupabaseAuthGateway(private val client: SupabaseClient) : AuthGateway {
    override suspend fun currentSession(): UserSession? = client.auth.currentUserOrNull()?.let {
        UserSession(it.id, it.email ?: it.id)
    }

    override suspend fun signIn(): UserSession = currentSession()
        ?: throw IllegalStateException("Sign-in UI must establish a Supabase Auth session before matchmaking")

    override suspend fun signOut() { client.auth.signOut() }
}

@Serializable
private data class ProfileRow(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
private data class RatingRow(
    @SerialName("user_id") val userId: String,
    @SerialName("current_rating") val currentRating: Int,
    @SerialName("peak_rating") val peakRating: Int,
)

@Serializable
private data class DisplayNameUpdate(@SerialName("display_name") val displayName: String)

@Serializable
private data class CredentialInsert(
    @SerialName("user_id") val userId: String,
    val organization: String,
    @SerialName("credential_type") val credentialType: String,
    val value: String,
    val status: String = "SELF_DECLARED",
)

@Serializable
private data class SubmitVerificationParams(
    @SerialName("p_credential_id") val credentialId: String,
    @SerialName("p_evidence_path") val evidencePath: String,
)

class SupabaseProfileRepository(private val client: SupabaseClient) : ProfileRepository {
    override suspend fun get(userId: String): Profile {
        val row = client.from("profiles").select { filter { eq("id", userId) } }.decodeSingle<ProfileRow>()
        val rating = client.from("ratings").select { filter { eq("user_id", userId) } }.decodeSingle<RatingRow>()
        return Profile(row.id, row.displayName, rating.currentRating, rating.peakRating, "CALCULATING")
    }

    override suspend fun updateDisplayName(userId: String, displayName: String): Profile {
        client.from("profiles").update(DisplayNameUpdate(displayName)) { filter { eq("id", userId) } }
        return get(userId)
    }
}

class SupabaseGameRecordRepository(private val client: SupabaseClient) : GameRecordRepository {
    override suspend fun recent(userId: String, limit: Int): List<GameRecord> = client.from("game_records")
        .select { filter { eq("players", "{$userId}") }; limit(limit.toLong()) }
        .decodeList<GameRecordRow>().map(GameRecordRow::toDomain)

    override suspend fun get(matchId: String): GameRecord = client.from("game_records")
        .select { filter { eq("match_id", matchId) } }.decodeSingle<GameRecordRow>().toDomain()
}

@Serializable
private data class GameRecordRow(
    @SerialName("match_id") val matchId: String,
    val players: List<String>,
    @SerialName("canonical_moves") val canonicalMoves: String,
    val result: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    @SerialName("time_control") val timeControl: String,
    @SerialName("finish_reason") val finishReason: String,
) {
    fun toDomain() = GameRecord(
        matchId, players, CanonicalMoves.decode(canonicalMoves), MatchResult.valueOf(result),
        Instant.parse(startedAt).toEpochMilli(), Instant.parse(finishedAt).toEpochMilli(), timeControl, FinishReason.valueOf(finishReason),
    )
}

class SupabaseCredentialRepository(
    private val client: SupabaseClient,
    private val userId: String,
) : CredentialRepository {
    @Serializable
    data class CredentialRow(
        val id: String,
        val organization: String,
        @SerialName("credential_type") val credentialType: String,
        val value: String,
        val status: String,
        @SerialName("verified_at") val verifiedAt: String? = null,
    ) {
        fun toDomain() = FederationCredential(organization, credentialType, value, CredentialStatus.valueOf(status), verifiedAt?.let { Instant.parse(it).toEpochMilli() }, id)
    }

    override suspend fun selfDeclare(value: String): FederationCredential {
        return client.from("federation_credentials").insert(CredentialInsert(userId, "日本オセロ連盟", "SELF_DECLARED", value)) { select() }
            .decodeSingle<CredentialRow>().toDomain()
    }

    override suspend fun uploadEvidence(fileName: String, mimeType: String, bytes: ByteArray): String {
        require(bytes.size <= 5 * 1024 * 1024) { "verification evidence must be at most 5 MB" }
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp")) { "verification evidence MIME type is not allowed" }
        require(fileName.substringAfterLast('/').isNotBlank() && '/' !in fileName.substringAfterLast('/'))
        val path = "$userId/${fileName.substringAfterLast('/')}"
        client.storage.from("verification").upload(path, bytes) {
            upsert = false
            contentType = ContentType.parse(mimeType)
        }
        return path
    }

    override suspend fun submitVerification(credential: FederationCredential, evidencePath: String): FederationCredential {
        require(evidencePath.startsWith("$userId/")) { "evidence object ownership required" }
        val id = requireNotNull(credential.id) { "credential id is required" }
        client.postgrest.rpc("submit_verification_submission", SubmitVerificationParams(id, evidencePath))
        return credential.copy(status = CredentialStatus.PENDING)
    }
}

@Serializable
data class SignalingEnvelope(
    val matchId: String,
    val senderId: String,
    val type: String,
    val sdp: String,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
)

interface SupabaseSignalingDataSource {
    suspend fun publish(envelope: SignalingEnvelope)
    fun subscribe(matchId: String, onEnvelope: (SignalingEnvelope) -> Unit): AutoCloseable
}

class SupabaseRealtimeSignalingDataSource(
    private val client: SupabaseClient,
    private val scope: CoroutineScope,
) : SupabaseSignalingDataSource {
    private val channels = mutableMapOf<String, io.github.jan.supabase.realtime.RealtimeChannel>()
    private val jobs = mutableMapOf<String, Job>()

    override suspend fun publish(envelope: SignalingEnvelope) {
        val channel = channels[envelope.matchId] ?: open(envelope.matchId)
        channel.broadcast("signal", buildJsonObject {
            put("matchId", envelope.matchId)
            put("senderId", envelope.senderId)
            put("type", envelope.type)
            put("sdp", envelope.sdp)
            put("protocolVersion", envelope.protocolVersion)
        })
    }

    override fun subscribe(matchId: String, onEnvelope: (SignalingEnvelope) -> Unit): AutoCloseable {
        val job = scope.launch {
            val channel = open(matchId)
            channel.broadcastFlow<SignalingEnvelope>(event = "signal").onEach(onEnvelope).launchIn(this)
        }
        return AutoCloseable {
            job.cancel()
            scope.launch {
                channels.remove(matchId)?.let { channel -> client.realtime.removeChannel(channel) }
                jobs.remove(matchId)?.cancel()
            }
        }
    }

    private suspend fun open(matchId: String): io.github.jan.supabase.realtime.RealtimeChannel = channels.getOrPut(matchId) {
        client.channel("match:$matchId")
    }.also { it.subscribe(blockUntilSubscribed = true) }
}
