package com.example.othello.data.supabase

import android.util.Log
import com.example.othello.auth.AuthGateway
import com.example.othello.auth.AuthSessionStatus
import com.example.othello.auth.SignUpResult
import com.example.othello.auth.UserSession
import com.example.othello.match.MatchFinishResult
import com.example.othello.match.MatchStartAck
import com.example.othello.match.MatchSubmission
import com.example.othello.match.OnlineMatchRepository
import com.example.othello.matchmaking.AssignedDisc
import com.example.othello.matchmaking.EnqueueResult
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.matchmaking.MatchmakingRepository
import com.example.othello.network.CURRENT_PROTOCOL_VERSION
import com.example.othello.network.MAX_MATCH_NEGOTIATION_EPOCH
import com.example.othello.profile.AccountDeletionRepository
import com.example.othello.profile.CurrentRatingRepository
import com.example.othello.profile.RatingSummary
import com.example.othello.profile.YesterdayRanking
import com.example.othello.profile.isTokyoYesterday
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
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus as SupabaseSessionStatus
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.*
import io.github.jan.supabase.postgrest.query.*
import io.github.jan.supabase.postgrest.query.filter.FilterOperation
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.*
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val EMAIL_CONFIRMATION_REDIRECT_URL = "https://chanriva.shinp-studio.com/signup-complete"
private const val PASSWORD_RESET_REDIRECT_URL = "https://chanriva.shinp-studio.com/reset-password"
internal const val RELEASE_NETWORK_TIMEOUT_MILLIS = 10_000L
private const val RELEASE_MATCH_TRACE_TAG = "ReleaseMatchTrace"

internal data class ReleaseNetworkTraceContext(
    val rpcName: String,
    val matchId: String,
    val expectedEpoch: Int?,
)

private val releaseTraceJson = Json { ignoreUnknownKeys = true }
private val releaseTraceUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
private val releaseTraceEmail = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
private val releaseTraceJwt = Regex("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
private val releaseTraceBodyFields = listOf(
    "lifecycle_status",
    "negotiation_epoch",
    "local_acked",
    "both_acked",
    "release_deadline",
    "reconnect_deadline",
)

private fun ReleaseNetworkTraceContext.prefix(stage: String): String =
    "stage=$stage rpc=$rpcName matchId=$matchId expectedEpoch=${expectedEpoch ?: "none"}"

private fun safeReleaseTraceMessage(message: String?): String = message
    ?.replace(releaseTraceJwt, "<redacted-jwt>")
    ?.replace(releaseTraceEmail, "<redacted-email>")
    ?.replace(releaseTraceUuid, "<redacted-uuid>")
    ?.take(500)
    ?: "<no-message>"

private fun traceRelease(context: ReleaseNetworkTraceContext, stage: String, detail: String = "") {
    if (!BuildConfig.DEBUG) return
    Log.d(RELEASE_MATCH_TRACE_TAG, "${context.prefix(stage)}${if (detail.isEmpty()) "" else " $detail"}")
}

private fun traceReleaseFailure(context: ReleaseNetworkTraceContext, stage: String, error: Throwable) {
    if (!BuildConfig.DEBUG) return
    Log.e(
        RELEASE_MATCH_TRACE_TAG,
        "${context.prefix(stage)} exceptionClass=${error.javaClass.name} " +
            "message=${safeReleaseTraceMessage(error.message)}",
    )
}

private fun safeReleaseBodySummary(body: String): String = runCatching {
    releaseTraceJson.parseToJsonElement(body).jsonArray.joinToString(prefix = "[", postfix = "]") { element ->
        val row = element.jsonObject
        releaseTraceBodyFields.joinToString(prefix = "{", postfix = "}") { field ->
            "$field=${row[field] ?: "<missing>"}"
        }
    }
}.getOrElse { error ->
    "<summary-unavailable exceptionClass=${error.javaClass.name} " +
        "message=${safeReleaseTraceMessage(error.message)}>"
}

internal fun parsePostgrestTimestamp(value: String): Instant =
    OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()

internal class ReleaseNetworkTimeoutException(cause: Throwable) :
    IllegalStateException("Release match network operation timed out", cause)

internal suspend fun <T> boundedReleaseNetwork(
    timeoutMillis: Long = RELEASE_NETWORK_TIMEOUT_MILLIS,
    traceContext: ReleaseNetworkTraceContext? = null,
    block: suspend () -> T,
): T = try {
    traceContext?.let { traceRelease(it, "bounded_network_enter", "timeoutMillis=$timeoutMillis") }
    withTimeout(timeoutMillis) { block() }
        .also { traceContext?.let { context -> traceRelease(context, "bounded_network_success") } }
} catch (error: TimeoutCancellationException) {
    val wrapped = ReleaseNetworkTimeoutException(error)
    traceContext?.let { traceReleaseFailure(it, "bounded_network_timeout_wrapped", wrapped) }
    throw wrapped
} catch (error: CancellationException) {
    traceContext?.let { traceReleaseFailure(it, "bounded_network_cancelled", error) }
    throw error
} catch (error: Exception) {
    traceContext?.let { traceReleaseFailure(it, "bounded_network_exception", error) }
    throw error
}

data class SupabaseConfig(val url: String, val anonKey: String) {
    fun validate(): Result<SupabaseConfig> = if (url.isBlank() || anonKey.isBlank()) {
        Result.failure(SupabaseConfigurationException("Supabase URL/anon key is not configured. Set supabase.url and supabase.anonKey in local.properties."))
    } else Result.success(this)
}

class SupabaseConfigurationException(message: String) : IllegalStateException(message)

@OptIn(SupabaseInternal::class)
internal object SupabaseClientFactory {
    fun config(): SupabaseConfig = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    internal fun create(config: SupabaseConfig = config()): Result<SupabaseClient> = runCatching {
        config.validate().getOrThrow()
        createSupabaseClient(config.url, config.anonKey) {
            if (BuildConfig.DEBUG) {
                httpConfig {
                    install(ResponseObserver) {
                        onResponse { response ->
                            val path = response.call.request.url.encodedPath
                            val rpcName = path.substringAfterLast('/')
                            if (rpcName in setOf(
                                    "ack_match_started_v2",
                                    "get_release_match_state_v2",
                                    "resume_match_v2",
                                )
                            ) {
                                Log.d(
                                    RELEASE_MATCH_TRACE_TAG,
                                    "stage=http_response rpc=$rpcName status=${response.status.value}",
                                )
                            }
                        }
                    }
                }
            }
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}

@Serializable
private data class EnqueueRow(
    @SerialName("match_id") val matchId: String? = null,
    val matched: Boolean,
    @SerialName("opponent_id") val opponentId: String? = null,
    @SerialName("assigned_disc") val assignedDisc: String? = null,
    @SerialName("opponent_rating") val opponentRating: Int? = null,
    @SerialName("lifecycle_status") val lifecycleStatus: String? = null,
    @SerialName("negotiation_epoch") val negotiationEpoch: Int? = null,
) {
    fun toDomain(): EnqueueResult = if (!matched) {
        EnqueueResult.Waiting
    } else {
        EnqueueResult.Matched(
            MatchAssignment(
                matchId = requireNotNull(matchId),
                opponentId = requireNotNull(opponentId),
                assignedDisc = assignedDisc.toAssignedDisc(),
                opponentRating = opponentRating,
                lifecycleStatus = lifecycleStatus,
                negotiationEpoch = requireNotNull(negotiationEpoch),
            ),
        )
    }
}

@Serializable
private data class ClaimRow(
    @SerialName("match_id") val matchId: String,
    @SerialName("opponent_id") val opponentId: String,
    @SerialName("assigned_disc") val assignedDisc: String,
    @SerialName("opponent_rating") val opponentRating: Int? = null,
    @SerialName("lifecycle_status") val lifecycleStatus: String? = null,
    @SerialName("negotiation_epoch") val negotiationEpoch: Int,
) {
    fun toDomain() = MatchAssignment(
        matchId = matchId,
        opponentId = opponentId,
        assignedDisc = assignedDisc.toAssignedDisc(),
        opponentRating = opponentRating,
        lifecycleStatus = lifecycleStatus,
        negotiationEpoch = negotiationEpoch,
    )
}

@Serializable
private data class MatchmakingRequestParams(
    @SerialName("p_request_id") val requestId: String,
)

private fun String?.toAssignedDisc(): AssignedDisc = when (this) {
    "BLACK" -> AssignedDisc.BLACK
    "WHITE" -> AssignedDisc.WHITE
    else -> error("invalid assigned disc")
}

internal fun <T> List<T>.singleOrNullForRpc(rpcName: String): T? = when (size) {
    0 -> null
    1 -> single()
    else -> throw IllegalStateException("$rpcName returned $size rows")
}

@Serializable
private data class MatchNotificationRow(
    @SerialName("user_id") val userId: String,
    @SerialName("match_id") val matchId: String,
)

internal class MatchNotificationTracker {
    private var observedMatchIds = emptySet<String>()

    fun observe(matchIds: Set<String>): Boolean {
        val hasUnseenMatch = (matchIds - observedMatchIds).isNotEmpty()
        observedMatchIds = matchIds
        return hasUnseenMatch
    }
}

@OptIn(SupabaseExperimental::class)
internal class SupabaseMatchmakingRepository(
    private val client: SupabaseClient,
    private val scope: CoroutineScope,
) : MatchmakingRepository {
    override suspend fun enqueueOrMatch(requestId: String): EnqueueResult = boundedReleaseNetwork {
        client.postgrest
            .rpc("enqueue_or_match_v2", MatchmakingRequestParams(requestId.requireUuid()))
            .decodeList<EnqueueRow>()
            .single()
            .toDomain()
    }

    override suspend fun cancelWaiting(requestId: String): MatchAssignment? = boundedReleaseNetwork {
        client.postgrest
            .rpc("cancel_waiting_v2", MatchmakingRequestParams(requestId.requireUuid()))
            .decodeList<ClaimRow>()
            .singleOrNullForRpc("cancel_waiting_v2")
            ?.toDomain()
    }

    override suspend fun claimActiveMatch(): MatchAssignment? = boundedReleaseNetwork {
        client.postgrest
            .rpc("claim_active_match_v2")
            .decodeList<ClaimRow>()
            .singleOrNullForRpc("claim_active_match_v2")
            ?.toDomain()
    }
    override fun subscribeToMatchNotifications(
        onMatchAvailable: () -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable {
        val job = scope.launch {
            val userId = client.auth.currentUserOrNull()?.id
            if (userId == null) {
                onError(IllegalStateException("authenticated session required for match notifications"))
                return@launch
            }
            val notificationTracker = MatchNotificationTracker()
            client.from("match_notifications").selectAsFlow(
                MatchNotificationRow::matchId,
                channelName = "match-notifications:$userId",
                filter = FilterOperation("user_id", FilterOperator.EQ, userId),
            ).retryWhen { _, attempt ->
                if (attempt >= 2) false else {
                    kotlinx.coroutines.delay(500L * (attempt + 1))
                    true
                }
            }.catch { onError(it) }
                .collect { rows ->
                    val matchIds = rows.mapTo(mutableSetOf(), MatchNotificationRow::matchId)
                    if (notificationTracker.observe(matchIds)) onMatchAvailable()
                }
        }
        return AutoCloseable { job.cancel() }
    }
}

private fun String.requireUuid(): String = also { UUID.fromString(it) }

@Serializable
private data class AckParams(@SerialName("p_match_id") val matchId: String)

@Serializable
private data class NegotiationEpochParams(
    @SerialName("p_match_id") val matchId: String,
    @SerialName("p_expected_epoch") val expectedNegotiationEpoch: Int,
)

@Serializable
private data class ReleaseMatchStateRow(
    @SerialName("lifecycle_status") val lifecycleStatus: String,
    @SerialName("release_deadline") val releaseDeadline: String? = null,
    @SerialName("reconnect_deadline") val reconnectDeadline: String? = null,
    @SerialName("negotiation_epoch") val negotiationEpoch: Int,
    @SerialName("local_acked") val localAcked: Boolean,
    @SerialName("both_acked") val bothAcked: Boolean,
    @SerialName("final_result") val finalResult: String? = null,
    @SerialName("final_position_hash") val finalPositionHash: String? = null,
) {
    private fun deadlineEpochMillis(): Long? = (reconnectDeadline ?: releaseDeadline)
        ?.let(::parsePostgrestTimestamp)
        ?.toEpochMilli()

    private fun deadlineEpochMillis(context: ReleaseNetworkTraceContext): Long? {
        val deadline = reconnectDeadline ?: releaseDeadline
        traceRelease(context, "deadline_parse_start", "value=${deadline ?: "null"}")
        if (deadline == null) {
            traceRelease(context, "deadline_parse_success", "epochMillis=null")
            return null
        }
        return try {
            parsePostgrestTimestamp(deadline).toEpochMilli().also { epochMillis ->
                traceRelease(context, "deadline_parse_success", "epochMillis=$epochMillis")
            }
        } catch (error: Exception) {
            traceReleaseFailure(context, "deadline_parse_failure", error)
            throw error
        }
    }

    fun toStartAck(context: ReleaseNetworkTraceContext): MatchStartAck {
        traceRelease(
            context,
            "to_start_ack_start",
            "lifecycleStatus=$lifecycleStatus negotiationEpoch=$negotiationEpoch " +
                "localAcked=$localAcked bothAcked=$bothAcked releaseDeadline=${releaseDeadline ?: "null"} " +
                "reconnectDeadline=${reconnectDeadline ?: "null"}",
        )
        return try {
            MatchStartAck(
                serverStatus = lifecycleStatus,
                localAcked = localAcked,
                bothAcked = bothAcked,
                deadlineEpochMillis = deadlineEpochMillis(context),
                negotiationEpoch = negotiationEpoch,
            ).also { ack ->
                traceRelease(
                    context,
                    "to_start_ack_success",
                    "serverStatus=${ack.serverStatus} negotiationEpoch=${ack.negotiationEpoch} " +
                        "localAcked=${ack.localAcked} bothAcked=${ack.bothAcked} " +
                        "deadlineEpochMillis=${ack.deadlineEpochMillis ?: "null"}",
                )
            }
        } catch (error: Exception) {
            traceReleaseFailure(context, "to_start_ack_failure", error)
            throw error
        }
    }

    fun toFinishResult() = MatchFinishResult(
        serverStatus = lifecycleStatus,
        finalResult = finalResult?.let(MatchResult::valueOf),
        finalPositionHash = finalPositionHash,
        deadlineEpochMillis = deadlineEpochMillis(),
        negotiationEpoch = negotiationEpoch,
    )
}

@Serializable
private data class SubmitReleaseResultParams(
    @SerialName("p_match_id") val matchId: String,
    @SerialName("p_request_id") val requestId: String,
    @SerialName("p_canonical_moves") val canonicalMoves: String,
    @SerialName("p_finish_reason") val finishReason: String,
    @SerialName("p_loser_disc") val loserDisc: String? = null,
    @SerialName("p_clock") val clock: JsonElement? = null,
)

@Serializable
private data class ReleaseResultRow(
    @SerialName("server_status") val serverStatus: String,
    @SerialName("rating_before") val ratingBefore: Int? = null,
    @SerialName("rating_after") val ratingAfter: Int? = null,
    @SerialName("rating_delta") val ratingDelta: Int? = null,
    @SerialName("current_rating") val currentRating: Int? = null,
    @SerialName("peak_rating") val peakRating: Int? = null,
    @SerialName("final_result") val finalResult: String? = null,
    @SerialName("final_position_hash") val finalPositionHash: String? = null,
) {
    fun toDomain() = MatchFinishResult(
        serverStatus = serverStatus,
        ratingBefore = ratingBefore,
        ratingAfter = ratingAfter,
        ratingDelta = ratingDelta,
        currentRating = currentRating,
        peakRating = peakRating,
        finalResult = finalResult?.let(MatchResult::valueOf),
        finalPositionHash = finalPositionHash,
    )
}

internal class SupabaseOnlineMatchRepository(private val client: SupabaseClient) : OnlineMatchRepository {
    override suspend fun ackMatchStarted(
        matchId: String,
        expectedNegotiationEpoch: Int,
    ): MatchStartAck {
        val context = ReleaseNetworkTraceContext(
            rpcName = "ack_match_started_v2",
            matchId = matchId,
            expectedEpoch = expectedNegotiationEpoch,
        )
        return releaseStartStateRpc(context) {
            client.postgrest.rpc(
                context.rpcName,
                NegotiationEpochParams(matchId.requireUuid(), expectedNegotiationEpoch),
            )
        }
    }

    override suspend fun getMatchStartState(matchId: String): MatchStartAck {
        val context = ReleaseNetworkTraceContext(
            rpcName = "get_release_match_state_v2",
            matchId = matchId,
            expectedEpoch = null,
        )
        return releaseStartStateRpc(context) {
            client.postgrest.rpc(context.rpcName, AckParams(matchId.requireUuid()))
        }
    }

    private suspend fun releaseStartStateRpc(
        context: ReleaseNetworkTraceContext,
        request: suspend () -> io.github.jan.supabase.postgrest.result.PostgrestResult,
    ): MatchStartAck = try {
        boundedReleaseNetwork(traceContext = context) {
            traceRelease(context, "rpc_request")
            val response = try {
                request()
            } catch (error: Exception) {
                traceReleaseFailure(context, "rpc_response_failure", error)
                throw error
            }
            traceRelease(context, "rpc_response_acquired")
            if (BuildConfig.DEBUG) {
                traceRelease(
                    context,
                    "response_body",
                    "received=true bodyLength=${response.data.length} safeBody=${safeReleaseBodySummary(response.data)}",
                )
            }

            traceRelease(context, "decode_start")
            val rows = try {
                response.decodeList<ReleaseMatchStateRow>().also { decoded ->
                    traceRelease(context, "decode_success", "rowCount=${decoded.size}")
                    decoded.forEachIndexed { index, row ->
                        traceRelease(
                            context,
                            "decoded_row",
                            "index=$index lifecycleStatus=${row.lifecycleStatus} " +
                                "negotiationEpoch=${row.negotiationEpoch} localAcked=${row.localAcked} " +
                                "bothAcked=${row.bothAcked} releaseDeadline=${row.releaseDeadline ?: "null"} " +
                                "reconnectDeadline=${row.reconnectDeadline ?: "null"}",
                        )
                    }
                }
            } catch (error: Exception) {
                traceReleaseFailure(context, "decode_failure", error)
                throw error
            }

            val row = try {
                rows.single().also { traceRelease(context, "single_success", "rowCount=${rows.size}") }
            } catch (error: Exception) {
                traceReleaseFailure(context, "single_failure", error)
                throw error
            }
            row.toStartAck(context)
        }.also { ack ->
            traceRelease(
                context,
                "repository_return_success",
                "serverStatus=${ack.serverStatus} negotiationEpoch=${ack.negotiationEpoch} " +
                    "localAcked=${ack.localAcked} bothAcked=${ack.bothAcked}",
            )
        }
    } catch (error: CancellationException) {
        traceReleaseFailure(context, "repository_boundary_cancelled", error)
        throw error
    } catch (error: Exception) {
        traceReleaseFailure(context, "repository_boundary_exception", error)
        throw error
    }

    override suspend fun abandonMatch(matchId: String): Boolean = boundedReleaseNetwork {
        client.postgrest.rpc("abandon_match_v2", AckParams(matchId.requireUuid()))
            .decodeAs<String>()
            .isNotBlank()
    }

    override suspend fun resumeMatch(
        matchId: String,
        expectedNegotiationEpoch: Int,
    ): MatchStartAck {
        val context = ReleaseNetworkTraceContext(
            rpcName = "resume_match_v2",
            matchId = matchId,
            expectedEpoch = expectedNegotiationEpoch,
        )
        return releaseStartStateRpc(context) {
            client.postgrest.rpc(
                context.rpcName,
                NegotiationEpochParams(matchId.requireUuid(), expectedNegotiationEpoch),
            )
        }
    }

    override suspend fun reconcileMatch(matchId: String): MatchFinishResult = releaseStateRpc(
        "reconcile_match_v2",
        matchId,
    )

    override suspend fun submitMatchResult(submission: MatchSubmission): MatchFinishResult = boundedReleaseNetwork {
        client.postgrest.rpc(
            "submit_match_result_v2",
            SubmitReleaseResultParams(
                matchId = submission.matchId.requireUuid(),
                requestId = submission.requestId.requireUuid(),
                canonicalMoves = submission.canonicalMoves,
                finishReason = submission.finishReason.name,
                loserDisc = submission.loserDisc?.name,
                clock = submission.clockPayload?.let(Json::parseToJsonElement),
            ),
        ).decodeList<ReleaseResultRow>().single().toDomain()
    }

    private suspend fun releaseStateRpc(name: String, matchId: String): MatchFinishResult =
        boundedReleaseNetwork {
            client.postgrest.rpc(name, AckParams(matchId.requireUuid()))
                .decodeList<ReleaseMatchStateRow>()
                .single()
                .toFinishResult()
        }
}

internal class SupabaseAuthGateway(private val client: SupabaseClient) : AuthGateway {
    override val sessionStatus = client.auth.sessionStatus
        .map(::toDomainSessionStatus)
        .distinctUntilChanged()

    override suspend fun currentSession(): UserSession? {
        client.auth.awaitInitialization()
        return when (val status = client.auth.sessionStatus.value) {
            is SupabaseSessionStatus.Authenticated -> UserSession(
                requireNotNull(status.session.user?.id) { "Authenticated Supabase session has no user" },
            )
            is SupabaseSessionStatus.NotAuthenticated -> null
            is SupabaseSessionStatus.RefreshFailure -> throw sessionRestoreFailure(status)
            SupabaseSessionStatus.Initializing -> error("Supabase Auth initialization did not complete")
        }
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
        client.auth.signUpWith(Email, redirectUrl = EMAIL_CONFIRMATION_REDIRECT_URL) {
            this.email = email
            this.password = password
        }
        return currentSession()?.let(SignUpResult::SignedIn)
            ?: SignUpResult.EmailConfirmationRequired
    }

    override suspend fun requestPasswordReset(email: String) {
        require(email.isNotBlank()) { "email is required" }
        client.auth.resetPasswordForEmail(email, redirectUrl = PASSWORD_RESET_REDIRECT_URL)
    }

    override suspend fun touchLastActive() {
        client.postgrest.rpc("touch_last_active")
    }

    override suspend fun signIn(): UserSession = currentSession()
        ?: throw IllegalStateException("Sign-in UI must establish a Supabase Auth session before matchmaking")

    override suspend fun signOut() { client.auth.signOut() }
    override suspend fun clearLocalSession() { client.auth.clearSession() }

    private fun toDomainSessionStatus(status: SupabaseSessionStatus): AuthSessionStatus = when (status) {
        is SupabaseSessionStatus.Authenticated -> status.session.user?.id
            ?.let { AuthSessionStatus.Authenticated(UserSession(it)) }
            ?: AuthSessionStatus.Recovering
        is SupabaseSessionStatus.NotAuthenticated -> AuthSessionStatus.Unauthenticated
        is SupabaseSessionStatus.RefreshFailure -> AuthSessionStatus.Recovering
        SupabaseSessionStatus.Initializing -> AuthSessionStatus.Initializing
    }

    @Suppress("DEPRECATION")
    private fun sessionRestoreFailure(status: SupabaseSessionStatus.RefreshFailure): Throwable {
        val cause = when (val failure = status.cause) {
            is RefreshFailureCause.NetworkError -> failure.exception
            is RefreshFailureCause.InternalServerError -> failure.exception
        }
        return IllegalStateException("Supabase session refresh failed during restore", cause)
    }
}

@Serializable
private data class RatingRow(
    @SerialName("user_id") val userId: String,
    @SerialName("current_rating") val currentRating: Int,
    @SerialName("peak_rating") val peakRating: Int,
)

@Serializable
internal data class YesterdayRankingRow(
    @SerialName("snapshot_date") val snapshotDate: String,
    val rank: Int,
    @SerialName("active_user_count") val activeUserCount: Int,
    @SerialName("top_percentile") val topPercentile: Double,
) {
    fun toDomainOrNull(clock: Clock = Clock.systemUTC()): YesterdayRanking? = runCatching {
        YesterdayRanking(snapshotDate, rank, activeUserCount, topPercentile)
    }.getOrNull()?.takeIf { it.isTokyoYesterday(clock) }
}

internal class SupabaseGameRecordRepository(private val client: SupabaseClient) : GameRecordRepository {
    override suspend fun recent(userId: String, limit: Int): List<GameRecord> = client.from("game_records")
        .select {
            filter { contains("players", listOf(userId)) }
            order("finished_at", Order.DESCENDING)
            limit(limit.coerceIn(1, 50).toLong())
        }
        .decodeList<JsonObject>()
        .let(::decodeGameRecordRows)

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
        parsePostgrestTimestamp(startedAt).toEpochMilli(), parsePostgrestTimestamp(finishedAt).toEpochMilli(), timeControl,
        FinishReason.valueOf(finishReason), finalPositionHash,
    )
}

internal class SupabaseAccountDeletionRepository(private val client: SupabaseClient) : AccountDeletionRepository {
    override suspend fun requestDeletion(): String = client.postgrest.rpc("request_account_deletion").decodeAs()
}

internal class SupabaseCurrentRatingRepository(private val client: SupabaseClient) : CurrentRatingRepository {
    override suspend fun getCurrentRating(): Int = client.from("ratings").select().decodeSingle<RatingRow>().currentRating

    override suspend fun getRatingSummary(): RatingSummary {
        val current = client.from("ratings").select().decodeSingle<RatingRow>().currentRating
        val yesterday = try {
            client.from("rating_daily_snapshot").select().decodeSingle<YesterdayRankingRow>().toDomainOrNull()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The snapshot is additive and may not exist until the first server run.
            // A missing or unavailable snapshot must not hide the independently read current rating.
            null
        }
        return RatingSummary(current, yesterday)
    }
}

internal class InvalidGameRecordRowsException : IllegalStateException("Online Game Record rows were present but none were valid")

private val gameRecordJson = Json { ignoreUnknownKeys = true }

/** Decodes rows independently so one legacy or malformed row cannot poison the whole list. */
internal fun decodeGameRecordRows(rows: List<JsonObject>): List<GameRecord> {
    val records = rows.mapNotNull { row ->
        runCatching {
            gameRecordJson.decodeFromJsonElement<GameRecordRow>(row).toDomain()
        }.getOrNull()
    }
    if (rows.isNotEmpty() && records.isEmpty()) throw InvalidGameRecordRowsException()
    return records
}

@Serializable
data class SignalingEnvelope(
    val matchId: String,
    val senderUserId: String,
    val type: String,
    val sdp: String,
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val negotiationEpoch: Int = 0,
)

internal fun validateSignalingEnvelope(envelope: SignalingEnvelope) {
    require(envelope.protocolVersion == CURRENT_PROTOCOL_VERSION)
    require(envelope.negotiationEpoch in 0..MAX_MATCH_NEGOTIATION_EPOCH)
    require(envelope.matchId.isNotBlank() && envelope.senderUserId.isNotBlank())
    require(envelope.type in setOf("OFFER", "ANSWER", "RESUME"))
    require(envelope.sdp.length in 1..16_384)
}

@Serializable
private data class SignalingRow(
    val id: Long,
    @SerialName("match_id") val matchId: String,
    @SerialName("sender_id") val senderUserId: String,
    @SerialName("signal_type") val type: String,
    val sdp: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("negotiation_epoch") val negotiationEpoch: Int,
) {
    fun toEnvelope() = SignalingEnvelope(
        matchId = matchId,
        senderUserId = senderUserId,
        type = type,
        sdp = sdp,
        protocolVersion = protocolVersion,
        negotiationEpoch = negotiationEpoch,
    )

    fun deliveryKey() = SignalingDeliveryKey(id, senderUserId, type, sdp, protocolVersion, negotiationEpoch)
}

@Serializable
private data class PublishMatchSignalV2Params(
    @SerialName("p_match_id") val matchId: String,
    @SerialName("p_signal_type") val signalType: String,
    @SerialName("p_sdp") val sdp: String,
    @SerialName("p_protocol_version") val protocolVersion: Int,
    @SerialName("p_negotiation_epoch") val negotiationEpoch: Int,
)

internal data class SignalingDeliveryKey(
    val id: Long,
    val senderUserId: String,
    val type: String,
    val sdp: String,
    val protocolVersion: Int,
    val negotiationEpoch: Int,
)

internal class SignalingDeliveryTracker(private val capacity: Int = 16) {
    private val delivered = linkedSetOf<SignalingDeliveryKey>()

    init {
        require(capacity > 0)
    }

    fun observe(key: SignalingDeliveryKey): Boolean {
        if (!delivered.add(key)) return false
        while (delivered.size > capacity) {
            delivered.iterator().run {
                next()
                remove()
            }
        }
        return true
    }

    internal val size: Int get() = delivered.size
}

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
        validateSignalingEnvelope(envelope)
        boundedReleaseNetwork {
            client.postgrest.rpc(
                "publish_match_signal_v2",
                PublishMatchSignalV2Params(
                    envelope.matchId.requireUuid(),
                    envelope.type,
                    envelope.sdp,
                    envelope.protocolVersion,
                    envelope.negotiationEpoch,
                ),
            )
        }
    }

    override fun subscribe(
        matchId: String,
        onEnvelope: (SignalingEnvelope) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable {
        jobs.remove(matchId)?.cancel()
        val job = scope.launch {
            val deliveryTracker = SignalingDeliveryTracker()
            val deliveryMutex = Mutex()
            suspend fun deliver(row: SignalingRow) {
                val envelope = row.toEnvelope()
                if (envelope.matchId != matchId) return
                try { validateSignalingEnvelope(envelope) } catch (_: IllegalArgumentException) { return }
                if (deliveryMutex.withLock { deliveryTracker.observe(row.deliveryKey()) }) onEnvelope(envelope)
            }
            // selectAsFlow can miss a row inserted between its initial SELECT and channel join.
            // Its initial SELECT plus one delayed reconciliation bounds this path to two snapshots.
            val realtimeJob = launch {
                client.from("match_signals_v2").selectAsFlow(
                    SignalingRow::id,
                    channelName = "match-signaling:$matchId",
                    filter = FilterOperation("match_id", FilterOperator.EQ, matchId),
                ).catch { onError(it) }
                    .collect { rows -> rows.sortedBy(SignalingRow::id).forEach { deliver(it) } }
            }
            try {
                delay(500)
                try {
                    val rows = boundedReleaseNetwork {
                        client.from("match_signals_v2").select {
                            filter { eq("match_id", matchId) }
                            order("id", Order.ASCENDING)
                        }.decodeList<SignalingRow>()
                    }
                    rows.forEach { deliver(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Realtime remains active after the one bounded reconciliation attempt.
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
    private val client: SupabaseClient,
    val authGateway: AuthGateway,
    val matchmakingRepository: MatchmakingRepository,
    val onlineMatchRepository: OnlineMatchRepository,
    val accountDeletionRepository: AccountDeletionRepository,
    val currentRatingRepository: CurrentRatingRepository,
    val gameRecordRepository: GameRecordRepository,
    val researchParticipationRepository: ResearchParticipationRepository,
    val researchPositionRepository: ResearchPositionRepository,
    val signalingDataSource: SupabaseSignalingDataSource,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        signalingDataSource.close()
        scope.cancel()
        closeScope.launch {
            try {
                client.close()
            } finally {
                closeScope.cancel()
            }
        }
    }

    internal companion object {
        fun create(client: SupabaseClient, scope: CoroutineScope): SupabaseComponent = SupabaseComponent(
            client = client,
            authGateway = SupabaseAuthGateway(client),
            matchmakingRepository = SupabaseMatchmakingRepository(client, scope),
            onlineMatchRepository = SupabaseOnlineMatchRepository(client),
            accountDeletionRepository = SupabaseAccountDeletionRepository(client),
            currentRatingRepository = SupabaseCurrentRatingRepository(client),
            gameRecordRepository = SupabaseGameRecordRepository(client),
            researchParticipationRepository = SupabaseResearchParticipationRepository(client),
            researchPositionRepository = SupabaseResearchPositionRepository(client),
            signalingDataSource = SupabaseRealtimeSignalingDataSource(client, scope),
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
