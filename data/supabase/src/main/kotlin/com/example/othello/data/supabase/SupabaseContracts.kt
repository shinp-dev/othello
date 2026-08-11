package com.example.othello.data.supabase

import com.example.othello.auth.AuthGateway
import com.example.othello.auth.SignUpResult
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
import com.example.othello.profile.AccountDeletionRepository
import com.example.othello.records.FinishReason
import com.example.othello.records.GameRecord
import com.example.othello.records.GameRecordRepository
import com.example.othello.records.MatchResult
import com.example.othello.research.ResearchParticipationRepository
import com.example.othello.research.ResearchParticipationStatus
import com.example.othello.research.ResearchMove
import com.example.othello.research.ResearchMoveKind
import com.example.othello.research.ResearchPosition
import com.example.othello.research.ResearchPositionRepository
import com.example.othello.research.ResearchPositionResult
import com.example.othello.research.ResearchUnavailableReason
import com.example.othello.game.CanonicalMoves
import com.example.othello.game.Disc
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.*
import io.github.jan.supabase.postgrest.query.*
import io.github.jan.supabase.postgrest.query.filter.FilterOperation
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.*
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.*
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.ktor.http.ContentType
import java.time.Instant

data class SupabaseConfig(val url: String, val anonKey: String) {
    fun validate(): Result<SupabaseConfig> = if (url.isBlank() || anonKey.isBlank()) {
        Result.failure(SupabaseConfigurationException("Supabase URL/anon key is not configured. Set supabase.url and supabase.anonKey in local.properties."))
    } else Result.success(this)
}

class SupabaseConfigurationException(message: String) : IllegalStateException(message)

internal object SupabaseClientFactory {
    fun config(): SupabaseConfig = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    internal fun create(config: SupabaseConfig = config()): Result<SupabaseClient> = runCatching {
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

@Serializable
private data class ClaimRow(
    @SerialName("match_id") val matchId: String,
    @SerialName("opponent_id") val opponentId: String,
    @SerialName("assigned_disc") val assignedDisc: String,
)

internal class SupabaseMatchmakingRepository(private val client: SupabaseClient) : MatchmakingRepository {
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

    override suspend fun cancelWaiting(): Boolean = client.postgrest.rpc("cancel_waiting").decodeAs<Boolean>()
    override suspend fun heartbeatWaiting(): Boolean = client.postgrest.rpc("heartbeat_waiting").decodeAs<Boolean>()
    override suspend fun claimMatchedAssignment(): MatchAssignment? = client.postgrest.rpc("claim_waiting_match")
        .decodeList<ClaimRow>().firstOrNull()?.let { row ->
            MatchAssignment(
                row.matchId,
                row.opponentId,
                when (row.assignedDisc) {
                    "BLACK" -> AssignedDisc.BLACK
                    "WHITE" -> AssignedDisc.WHITE
                    else -> error("invalid assigned disc")
                },
            )
        }
    override suspend fun reconcileCallerActiveMatch(): Boolean =
        client.postgrest.rpc("reconcile_expired_active_match_for_user").decodeAs<Int>() > 0
}

@Serializable
private data class AckParams(@SerialName("p_match_id") val matchId: String)

@Serializable
private data class MatchStartStateRow(
    @SerialName("server_status") val serverStatus: String,
    @SerialName("local_acked") val localAcked: Boolean,
    @SerialName("both_acked") val bothAcked: Boolean,
) {
    fun toDomain() = MatchStartAck(serverStatus, localAcked, bothAcked)
}

@Serializable
private data class SubmitResultParams(
    @SerialName("p_match_id") val matchId: String,
    @SerialName("p_canonical_moves") val canonicalMoves: String,
    @SerialName("p_result") val result: String,
    @SerialName("p_final_position_hash") val finalPositionHash: String,
    @SerialName("p_finish_reason") val finishReason: String,
    @SerialName("p_clock") val clock: String? = null,
)

@Serializable
private data class MatchRatingHistoryRow(
    val rating: Int,
    val delta: Int,
)

internal class SupabaseOnlineMatchRepository(private val client: SupabaseClient) : OnlineMatchRepository {
    override suspend fun ackMatchStarted(matchId: String): MatchStartAck {
        client.postgrest.rpc("ack_match_started", AckParams(matchId)).decodeAs<String>()
        return getMatchStartState(matchId)
    }

    override suspend fun getMatchStartState(matchId: String): MatchStartAck = client.postgrest
        .rpc("get_match_start_state", AckParams(matchId))
        .decodeList<MatchStartStateRow>()
        .single()
        .toDomain()

    override suspend fun abandonMatch(matchId: String): Boolean = client.postgrest.rpc("abandon_match", AckParams(matchId)).decodeAs<String>().isNotBlank()

    override suspend fun submitMatchResult(submission: MatchSubmission): MatchFinishResult {
        val serverStatus = client.postgrest.rpc(
            "submit_match_result",
            SubmitResultParams(
                submission.matchId,
                submission.canonicalMoves,
                submission.result.name,
                submission.finalPositionHash,
                submission.finishReason.name,
                submission.clockPayload,
            ),
        ).decodeAs<String>()
        if (serverStatus != "CONFIRMED") return MatchFinishResult(serverStatus)
        return try {
            val history = client.from("rating_history").select {
                filter { eq("match_id", submission.matchId) }
                limit(1)
            }.decodeSingle<MatchRatingHistoryRow>()
            val current = client.from("ratings").select().decodeSingle<RatingRow>()
            MatchFinishResult(
                serverStatus = serverStatus,
                ratingBefore = history.rating - history.delta,
                ratingAfter = history.rating,
                ratingDelta = history.delta,
                currentRating = current.currentRating,
                peakRating = current.peakRating,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            MatchFinishResult(serverStatus)
        }
    }
}

internal class SupabaseAuthGateway(private val client: SupabaseClient) : AuthGateway {
    override suspend fun currentSession(): UserSession? = client.auth.currentUserOrNull()?.let {
        UserSession(it.id, it.email ?: it.id)
    }

    override suspend fun signIn(email: String, password: String): UserSession {
        require(email.isNotBlank()) { "email is required" }
        require(password.isNotBlank()) { "password is required" }
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        return requireNotNull(currentSession())
    }

    override suspend fun signUp(email: String, password: String): SignUpResult {
        require(email.isNotBlank()) { "email is required" }
        require(password.length >= 8) { "password must be at least 8 characters" }
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        return currentSession()?.let(SignUpResult::SignedIn)
            ?: SignUpResult.EmailConfirmationRequired
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
private data class ProfileProjectionRow(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("current_rating") val currentRating: Int,
    @SerialName("peak_rating") val peakRating: Int,
    @SerialName("stable_rating_band") val stableRatingBand: String,
    @SerialName("federation_grade") val federationGrade: String? = null,
    @SerialName("federation_verification_status") val federationVerificationStatus: String? = null,
)

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

internal class SupabaseProfileRepository(private val client: SupabaseClient) : ProfileRepository {
    override suspend fun get(userId: String): Profile {
        val row = client.from("public_profiles").select { filter { eq("id", userId) } }.decodeSingle<ProfileProjectionRow>()
        return Profile(
            row.id,
            row.displayName,
            row.currentRating,
            row.peakRating,
            row.stableRatingBand,
            row.federationGrade,
            row.federationVerificationStatus == "VERIFIED",
            row.federationVerificationStatus,
        )
    }

    override suspend fun updateDisplayName(userId: String, displayName: String): Profile {
        client.from("profiles").update(DisplayNameUpdate(displayName)) { filter { eq("id", userId) } }
        return get(userId)
    }
}

internal class SupabaseGameRecordRepository(private val client: SupabaseClient) : GameRecordRepository {
    override suspend fun recent(userId: String, limit: Int): List<GameRecord> = client.from("game_records")
        .select {
            filter { contains("players", listOf(userId)) }
            order("finished_at", Order.DESCENDING)
            limit(limit.coerceIn(1, 50).toLong())
        }
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
    @SerialName("final_position_hash") val finalPositionHash: String? = null,
) {
    fun toDomain() = GameRecord(
        matchId, players, CanonicalMoves.decode(canonicalMoves), MatchResult.valueOf(result),
        Instant.parse(startedAt).toEpochMilli(), Instant.parse(finishedAt).toEpochMilli(), timeControl,
        FinishReason.valueOf(finishReason), finalPositionHash,
    )
}

internal class SupabaseCredentialRepository(
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

    override suspend fun current(): FederationCredential? = client.from("federation_credentials")
        .select { filter { eq("user_id", userId) }; limit(1) }
        .decodeList<CredentialRow>()
        .firstOrNull()
        ?.toDomain()

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

internal class SupabaseAccountDeletionRepository(private val client: SupabaseClient) : AccountDeletionRepository {
    override suspend fun requestDeletion(): String = client.postgrest.rpc("request_account_deletion").decodeAs()
}

@Serializable
data class SignalingEnvelope(
    val matchId: String,
    val senderUserId: String,
    val type: String,
    val sdp: String,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
)

@Serializable
private data class SignalingRow(
    val id: Long,
    @SerialName("match_id") val matchId: String,
    @SerialName("sender_id") val senderUserId: String,
    @SerialName("signal_type") val type: String,
    val sdp: String,
    @SerialName("protocol_version") val protocolVersion: Int,
) {
    fun toEnvelope() = SignalingEnvelope(matchId, senderUserId, type, sdp, protocolVersion)
}

@Serializable
private data class SignalingInsert(
    @SerialName("match_id") val matchId: String,
    @SerialName("sender_id") val senderUserId: String,
    @SerialName("signal_type") val type: String,
    val sdp: String,
    @SerialName("protocol_version") val protocolVersion: Int,
)

interface SupabaseSignalingDataSource {
    suspend fun publish(envelope: SignalingEnvelope)
    fun subscribe(
        matchId: String,
        onEnvelope: (SignalingEnvelope) -> Unit,
        onError: (Throwable) -> Unit = {},
    ): AutoCloseable
    fun close() {}
}

@OptIn(SupabaseExperimental::class)
internal class SupabaseRealtimeSignalingDataSource(
    private val client: SupabaseClient,
    private val scope: CoroutineScope,
) : SupabaseSignalingDataSource {
    private val jobs = mutableMapOf<String, Job>()

    override suspend fun publish(envelope: SignalingEnvelope) {
        validate(envelope)
        client.from("match_signaling").insert(
            SignalingInsert(envelope.matchId, envelope.senderUserId, envelope.type, envelope.sdp, envelope.protocolVersion),
        )
    }

    override fun subscribe(
        matchId: String,
        onEnvelope: (SignalingEnvelope) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable {
        jobs.remove(matchId)?.cancel()
        val job = scope.launch {
            val delivered = mutableSetOf<String>()
            val deliveryMutex = Mutex()
            suspend fun deliver(envelope: SignalingEnvelope) {
                if (envelope.matchId != matchId) return
                try { validate(envelope) } catch (_: IllegalArgumentException) { return }
                val key = "${envelope.senderUserId}|${envelope.type}|${envelope.sdp}|${envelope.protocolVersion}"
                if (deliveryMutex.withLock { delivered.add(key) }) onEnvelope(envelope)
            }
            // selectAsFlow can miss a row inserted between its initial SELECT and channel join.
            // Brief catch-up reads close that signaling-only race; moves never use this path.
            val realtimeJob = launch {
                client.from("match_signaling").selectAsFlow(
                    SignalingRow::id,
                    channelName = "match-signaling:$matchId",
                    filter = FilterOperation("match_id", FilterOperator.EQ, matchId),
                ).retryWhen { _, attempt ->
                    if (attempt >= 2) false else {
                        kotlinx.coroutines.delay(500L * (attempt + 1))
                        true
                    }
                }.catch { onError(it) }
                    .collect { rows -> rows.sortedBy(SignalingRow::id).forEach { deliver(it.toEnvelope()) } }
            }
            try {
                // Keep a finite signaling-only fallback alive even if the realtime flow joins late
                // or completes. Slow emulator boots can otherwise miss an ANSWER after four seconds.
                repeat(20) { attempt ->
                    try {
                        val rows = client.from("match_signaling").select {
                            filter { eq("match_id", matchId) }
                            order("id", Order.ASCENDING)
                        }.decodeList<SignalingRow>()
                        rows.forEach { deliver(it.toEnvelope()) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Realtime remains active; a later catch-up attempt can recover.
                    }
                    if (attempt < 19) kotlinx.coroutines.delay(500)
                }
                realtimeJob.join()
            } finally {
                realtimeJob.cancel()
            }
        }
        jobs[matchId] = job
        return AutoCloseable {
            if (jobs[matchId] === job) jobs.remove(matchId)
            job.cancel()
        }
    }

    override fun close() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    private fun validate(envelope: SignalingEnvelope) {
        require(envelope.protocolVersion == CURRENT_PROTOCOL_VERSION)
        require(envelope.matchId.isNotBlank() && envelope.senderUserId.isNotBlank())
        require(envelope.type == "OFFER" || envelope.type == "ANSWER")
        require(envelope.sdp.length in 1..16_384)
    }
}

@Serializable
private data class SetResearchParticipationParams(
    @SerialName("p_enabled") val enabled: Boolean,
    @SerialName("p_accepted_consent_version") val acceptedConsentVersion: Int? = null,
)

@Serializable
private data class ResearchParticipationStatusRow(
    @SerialName("participation_on") val participationOn: Boolean,
    @SerialName("current_consent_version") val currentConsentVersion: Int,
    @SerialName("agreed_consent_version") val agreedConsentVersion: Int? = null,
    @SerialName("reconsent_required") val reconsentRequired: Boolean,
    @SerialName("research_subject_linked") val researchSubjectLinked: Boolean,
    @SerialName("current_period_exists") val currentPeriodExists: Boolean,
    @SerialName("current_participation_id") val currentParticipationId: String? = null,
    @SerialName("current_period_started_at") val currentPeriodStartedAt: String? = null,
    val eligible: Boolean,
    @SerialName("can_view_research_data") val canViewResearchData: Boolean,
    @SerialName("qualifying_game_count") val qualifyingGameCount: Int,
    @SerialName("required_game_count") val requiredGameCount: Int,
    @SerialName("window_days") val windowDays: Int,
    @SerialName("collection_enabled") val collectionEnabled: Boolean,
    @SerialName("collection_allowed") val collectionAllowed: Boolean,
) {
    fun toDomain() = ResearchParticipationStatus(
        participationOn = participationOn,
        currentConsentVersion = currentConsentVersion,
        agreedConsentVersion = agreedConsentVersion,
        reconsentRequired = reconsentRequired,
        researchSubjectLinked = researchSubjectLinked,
        currentPeriodExists = currentPeriodExists,
        currentParticipationId = currentParticipationId,
        currentPeriodStartedAtEpochMillis = currentPeriodStartedAt?.let(Instant::parse)?.toEpochMilli(),
        eligible = eligible,
        canViewResearchData = canViewResearchData,
        qualifyingGameCount = qualifyingGameCount,
        requiredGameCount = requiredGameCount,
        windowDays = windowDays,
        collectionEnabled = collectionEnabled,
        collectionAllowed = collectionAllowed,
    )
}

internal class SupabaseResearchParticipationRepository(
    private val client: SupabaseClient,
) : ResearchParticipationRepository {
    override suspend fun status(): ResearchParticipationStatus = client.postgrest
        .rpc("get_research_participation_status")
        .decodeList<ResearchParticipationStatusRow>()
        .single()
        .toDomain()

    override suspend fun setParticipation(
        enabled: Boolean,
        acceptedConsentVersion: Int?,
    ): ResearchParticipationStatus = client.postgrest
        .rpc(
            "set_research_participation",
            SetResearchParticipationParams(enabled, acceptedConsentVersion),
        )
        .decodeList<ResearchParticipationStatusRow>()
        .single()
        .toDomain()
}

@Serializable
private data class ResearchPositionParams(
    @SerialName("p_position_token") val positionToken: String,
    @SerialName("p_segment_key") val segmentKey: String,
)

@Serializable
private data class ResearchMoveRow(
    val kind: String,
    val coordinate: String? = null,
    @SerialName("choice_rate") val choiceRate: Double = 0.0,
    @SerialName("win_rate") val winRate: Double = 0.0,
    @SerialName("draw_rate") val drawRate: Double = 0.0,
    @SerialName("loss_rate") val lossRate: Double = 0.0,
    @SerialName("unique_contributors") val uniqueContributors: Int? = null,
    @SerialName("can_explore") val canExplore: Boolean = false,
    @SerialName("child_position_token") val childPositionToken: String? = null,
) {
    fun toDomain() = ResearchMove(
        kind = if (kind == "OTHER") ResearchMoveKind.OTHER else ResearchMoveKind.MOVE,
        coordinate = coordinate,
        choiceRate = choiceRate,
        winRate = winRate,
        drawRate = drawRate,
        lossRate = lossRate,
        uniqueContributors = uniqueContributors,
        canExplore = canExplore,
        childPositionToken = childPositionToken,
    )
}

@Serializable
private data class ResearchPositionResponseRow(
    val available: Boolean,
    val reason: String? = null,
    @SerialName("position_token") val positionToken: String? = null,
    @SerialName("generation_id") val generationId: Long? = null,
    @SerialName("segment_key") val segmentKey: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("unique_contributors") val uniqueContributors: Int? = null,
    val moves: List<ResearchMoveRow> = emptyList(),
    val other: ResearchMoveRow? = null,
) {
    fun toDomain(): ResearchPositionResult = if (!available) {
        ResearchPositionResult.Unavailable(
            when (reason) {
                "NOT_ELIGIBLE" -> ResearchUnavailableReason.NOT_ELIGIBLE
                "INSUFFICIENT_SAMPLE" -> ResearchUnavailableReason.INSUFFICIENT_SAMPLE
                "NO_PUBLISHED_GENERATION" -> ResearchUnavailableReason.NO_PUBLISHED_GENERATION
                "UNSUPPORTED_SEGMENT" -> ResearchUnavailableReason.UNSUPPORTED_SEGMENT
                else -> ResearchUnavailableReason.UNKNOWN
            },
        )
    } else {
        ResearchPositionResult.Available(
            ResearchPosition(
                positionToken = requireNotNull(positionToken),
                generationId = requireNotNull(generationId),
                segmentKey = requireNotNull(segmentKey),
                publishedAt = publishedAt,
                uniqueContributors = requireNotNull(uniqueContributors),
                moves = moves.map(ResearchMoveRow::toDomain),
                other = other?.toDomain(),
            ),
        )
    }
}

internal class SupabaseResearchPositionRepository(
    private val client: SupabaseClient,
) : ResearchPositionRepository {
    override suspend fun getPosition(positionToken: String, segmentKey: String): ResearchPositionResult = runCatching {
        client.postgrest
            .rpc("get_research_position", ResearchPositionParams(positionToken, segmentKey))
            .decodeAs<ResearchPositionResponseRow>()
            .toDomain()
    }.getOrElse { ResearchPositionResult.Failed("研究データを取得できませんでした") }
}

/** Composition root for Supabase infrastructure. SDK types never cross this boundary. */
class SupabaseComponent private constructor(
    val authGateway: AuthGateway,
    val matchmakingRepository: MatchmakingRepository,
    val onlineMatchRepository: OnlineMatchRepository,
    val profileRepository: ProfileRepository,
    val accountDeletionRepository: AccountDeletionRepository,
    val gameRecordRepository: GameRecordRepository,
    val researchParticipationRepository: ResearchParticipationRepository,
    val researchPositionRepository: ResearchPositionRepository,
    val signalingDataSource: SupabaseSignalingDataSource,
    private val client: SupabaseClient,
    private val scope: CoroutineScope,
) : AutoCloseable {
    fun credentialRepository(userId: String): CredentialRepository = SupabaseCredentialRepository(client, userId)

    override fun close() {
        signalingDataSource.close()
        scope.cancel()
    }

    internal companion object {
        fun create(client: SupabaseClient, scope: CoroutineScope): SupabaseComponent = SupabaseComponent(
            authGateway = SupabaseAuthGateway(client),
            matchmakingRepository = SupabaseMatchmakingRepository(client),
            onlineMatchRepository = SupabaseOnlineMatchRepository(client),
            profileRepository = SupabaseProfileRepository(client),
            accountDeletionRepository = SupabaseAccountDeletionRepository(client),
            gameRecordRepository = SupabaseGameRecordRepository(client),
            researchParticipationRepository = SupabaseResearchParticipationRepository(client),
            researchPositionRepository = SupabaseResearchPositionRepository(client),
            signalingDataSource = SupabaseRealtimeSignalingDataSource(client, scope),
            client = client,
            scope = scope,
        )
    }
}

/** Android composition root; callers receive only application-owned ports. */
object SupabaseModule {
    fun create(config: SupabaseConfig? = null, scope: CoroutineScope): Result<SupabaseComponent> =
        SupabaseClientFactory.create(config ?: SupabaseClientFactory.config()).map { client ->
            SupabaseComponent.create(client, scope)
        }
}
