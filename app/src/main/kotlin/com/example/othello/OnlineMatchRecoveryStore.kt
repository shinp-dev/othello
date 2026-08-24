package com.example.othello

import android.content.Context
import com.example.othello.game.CanonicalMoves
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.GameStatus
import com.example.othello.network.ClockSnapshot
import com.example.othello.matchmaking.AssignedDisc
import com.example.othello.matchmaking.MatchAssignment
import com.example.othello.network.MAX_MATCH_NEGOTIATION_EPOCH
import com.example.othello.records.FinishReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One small, app-private recovery checkpoint for the current online match.
 *
 * The server intentionally does not receive move-by-move traffic. Persisting the compact
 * canonical line here lets a restarted process prove and resynchronize its last local state
 * without turning Supabase into a gameplay log.
 */
data class OnlineMatchRecoverySnapshot(
    val userId: String,
    val matchId: String,
    val opponentId: String,
    val assignedDisc: AssignedDisc,
    val opponentRating: Int?,
    val negotiationEpoch: Int = 0,
    val canonicalMoves: String,
    val stateHash: String,
    val blackRemainingMillis: Long,
    val whiteRemainingMillis: Long,
    val runningDisc: Disc? = null,
    val pendingFinishReason: FinishReason? = null,
    val pendingLoserDisc: Disc? = null,
    val pendingResultRequestId: String? = null,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(userId.isNotBlank() && matchId.isNotBlank() && opponentId.isNotBlank())
        require(opponentRating == null || opponentRating > 0)
        require(negotiationEpoch in 0..MAX_MATCH_NEGOTIATION_EPOCH)
        require(canonicalMoves.length <= 240)
        CanonicalMoves.decode(canonicalMoves)
        require(blackRemainingMillis >= 0 && whiteRemainingMillis >= 0)
        require(pendingLoserDisc != Disc.EMPTY)
        require(runningDisc != Disc.EMPTY)
        require(pendingResultRequestId == null || pendingResultRequestId.isNotBlank())
        require(updatedAtEpochMillis > 0)
        if (pendingFinishReason == null) {
            require(pendingLoserDisc == null && pendingResultRequestId == null)
        } else {
            require(pendingResultRequestId != null)
            require(
                (pendingFinishReason == FinishReason.NORMAL && pendingLoserDisc == null) ||
                    (pendingFinishReason != FinishReason.NORMAL && pendingLoserDisc != null),
            )
        }
        val recoveredGame = replayRecoveryTranscript(canonicalMoves)
        require(recoveredGame.stateHash() == stateHash) {
            "recovery state hash does not match transcript"
        }
        require(runningDisc == null ||
            (recoveredGame.status is GameStatus.InProgress && runningDisc == recoveredGame.currentPlayer)
        ) { "recovery running clock does not match the position" }
    }

    fun assignment(lifecycleStatus: String? = null) = MatchAssignment(
        matchId = matchId,
        opponentId = opponentId,
        assignedDisc = assignedDisc,
        opponentRating = opponentRating,
        lifecycleStatus = lifecycleStatus,
        negotiationEpoch = negotiationEpoch,
    )

    fun adjustedClock(nowEpochMillis: Long = System.currentTimeMillis()): ClockSnapshot {
        val elapsed = (nowEpochMillis - updatedAtEpochMillis).coerceAtLeast(0L)
        return when (runningDisc) {
            Disc.BLACK -> ClockSnapshot((blackRemainingMillis - elapsed).coerceAtLeast(0L), whiteRemainingMillis)
            Disc.WHITE -> ClockSnapshot(blackRemainingMillis, (whiteRemainingMillis - elapsed).coerceAtLeast(0L))
            Disc.EMPTY, null -> ClockSnapshot(blackRemainingMillis, whiteRemainingMillis)
        }
    }
}

internal object OnlineMatchRecoveryCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(value: OnlineMatchRecoverySnapshot): String = buildJsonObject {
        put("version", VERSION)
        put("userId", value.userId)
        put("matchId", value.matchId)
        put("opponentId", value.opponentId)
        put("assignedDisc", value.assignedDisc.name)
        value.opponentRating?.let { put("opponentRating", it) }
        put("negotiationEpoch", value.negotiationEpoch)
        put("canonicalMoves", value.canonicalMoves)
        put("stateHash", value.stateHash)
        put("blackRemainingMillis", value.blackRemainingMillis)
        put("whiteRemainingMillis", value.whiteRemainingMillis)
        value.runningDisc?.let { put("runningDisc", it.name) }
        value.pendingFinishReason?.let { put("pendingFinishReason", it.name) }
        value.pendingLoserDisc?.let { put("pendingLoserDisc", it.name) }
        value.pendingResultRequestId?.let { put("pendingResultRequestId", it) }
        put("updatedAtEpochMillis", value.updatedAtEpochMillis)
    }.toString()

    fun decode(encoded: String): OnlineMatchRecoverySnapshot {
        val value = json.parseToJsonElement(encoded).jsonObject
        require(value.getValue("version").jsonPrimitive.intOrNull == VERSION) { "unsupported recovery version" }
        return OnlineMatchRecoverySnapshot(
            userId = value.requiredString("userId"),
            matchId = value.requiredString("matchId"),
            opponentId = value.requiredString("opponentId"),
            assignedDisc = AssignedDisc.valueOf(value.requiredString("assignedDisc")),
            opponentRating = value["opponentRating"]?.jsonPrimitive?.intOrNull,
            negotiationEpoch = value["negotiationEpoch"]?.jsonPrimitive?.intOrNull ?: 0,
            canonicalMoves = value.requiredString("canonicalMoves"),
            stateHash = value.requiredString("stateHash"),
            blackRemainingMillis = value.requiredLong("blackRemainingMillis"),
            whiteRemainingMillis = value.requiredLong("whiteRemainingMillis"),
            runningDisc = value["runningDisc"]?.jsonPrimitive?.contentOrNull?.let(Disc::valueOf),
            pendingFinishReason = value["pendingFinishReason"]?.jsonPrimitive?.contentOrNull?.let(FinishReason::valueOf),
            pendingLoserDisc = value["pendingLoserDisc"]?.jsonPrimitive?.contentOrNull?.let(Disc::valueOf),
            pendingResultRequestId = value["pendingResultRequestId"]?.jsonPrimitive?.contentOrNull,
            updatedAtEpochMillis = value.requiredLong("updatedAtEpochMillis"),
        )
    }

    private fun kotlinx.serialization.json.JsonObject.requiredString(name: String): String =
        getValue(name).jsonPrimitive.content.also { require(it.isNotBlank()) }

    private fun kotlinx.serialization.json.JsonObject.requiredLong(name: String): Long =
        requireNotNull(getValue(name).jsonPrimitive.longOrNull) { "$name must be a number" }

    private const val VERSION = 1
}

private fun replayRecoveryTranscript(encoded: String): GameState {
    var game = GameState()
    CanonicalMoves.decode(encoded).forEachIndexed { index, move ->
        game = when (val outcome = move?.let(game::play) ?: game.pass()) {
            is MoveOutcome.Played -> outcome.state
            is MoveOutcome.Passed -> outcome.state
            is MoveOutcome.Rejected -> throw IllegalArgumentException("invalid recovery transcript at ply $index")
        }
    }
    return game
}

class OnlineMatchRecoveryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): OnlineMatchRecoverySnapshot? {
        val encoded = preferences.getString(KEY, null) ?: return null
        return try {
            OnlineMatchRecoveryCodec.decode(encoded)
        } catch (_: Exception) {
            preferences.edit().remove(KEY).commit()
            null
        }
    }

    /** commit() is deliberate: a move checkpoint must reach disk before process death can follow. */
    fun save(snapshot: OnlineMatchRecoverySnapshot): Boolean =
        preferences.edit().putString(KEY, OnlineMatchRecoveryCodec.encode(snapshot)).commit()

    fun clear(matchId: String? = null): Boolean {
        if (matchId != null && load()?.matchId != matchId) return false
        return preferences.edit().remove(KEY).commit()
    }

    private companion object {
        const val PREFERENCES = "online_match_recovery_v1"
        const val KEY = "active_match"
    }
}
