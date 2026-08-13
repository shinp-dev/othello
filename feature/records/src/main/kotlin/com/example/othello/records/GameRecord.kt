package com.example.othello.records

import com.example.othello.game.Disc
import com.example.othello.game.Position
import com.example.othello.game.CanonicalMoves
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class FinishReason { NORMAL, RESIGNATION, TIMEOUT, DISCONNECT, DISPUTED }
enum class MatchResult { BLACK_WIN, WHITE_WIN, DRAW }

data class GameRecord(
    val matchId: String,
    val players: List<String>,
    val moves: List<Position?>,
    val result: MatchResult,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val timeControl: String,
    val finishReason: FinishReason,
    val finalPositionHash: String? = null,
) {
    init {
        require(players.size == 2 && players.distinct().size == 2)
        require(moves.size <= 120)
        require(finalPositionHash == null || FINAL_POSITION_HASH.matches(finalPositionHash))
    }

    val blackPlayerId: String get() = players[0]
    val whitePlayerId: String get() = players[1]

    private companion object {
        val FINAL_POSITION_HASH = Regex("^[0-9a-f]{16}:[0-2]:[0-2]:[0-9]{1,3}$")
    }
}

fun MatchResult.toDisc(): Disc? = when (this) {
    MatchResult.BLACK_WIN -> Disc.BLACK
    MatchResult.WHITE_WIN -> Disc.WHITE
    MatchResult.DRAW -> null
}

interface GameRecordRepository {
    suspend fun recent(userId: String, limit: Int = 50): List<GameRecord>
    suspend fun get(matchId: String): GameRecord
}

enum class LocalRecordType { LOCAL_HUMAN, LOCAL_AI, RESEARCH_LINE }

/** A device-only record. It never enters the Server GameRecord repository. */
data class LocalGameRecord(
    val localId: String,
    val moves: List<Position?>,
    val createdAtEpochMillis: Long,
    val type: LocalRecordType,
    val result: MatchResult? = null,
    val finishReason: FinishReason? = null,
    val playerDisc: Disc? = null,
) {
    init {
        require(localId.isNotBlank())
        require(moves.size <= 120)
        require((result == null) == (finishReason == null)) { "result and finishReason must be provided together" }
        replay(moves)
    }

    val canonicalMoves: String get() = CanonicalMoves.encode(moves)
    val isAiGame: Boolean get() = type == LocalRecordType.LOCAL_AI

    companion object {
        fun replay(moves: List<Position?>): List<GameState> {
            val states = mutableListOf(GameState())
            moves.forEach { move ->
                val outcome = if (move == null) states.last().pass() else states.last().play(move)
                states += when (outcome) {
                    is MoveOutcome.Played -> outcome.state
                    is MoveOutcome.Passed -> outcome.state
                    is MoveOutcome.Rejected -> error("invalid local move at ply ${states.lastIndex}")
                }
            }
            return states
        }
    }
}

@Serializable
private data class LocalGameRecordDto(
    @SerialName("local_id") val localId: String,
    val moves: String,
    @SerialName("created_at") val createdAtEpochMillis: Long,
    val type: String,
    val result: String? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    @SerialName("player_disc") val playerDisc: String? = null,
)

/** Stable, dependency-free-on-Android representation for the private local store. */
object LocalGameRecordJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(record: LocalGameRecord): String = json.encodeToString(
        LocalGameRecordDto(
            localId = record.localId,
            moves = record.canonicalMoves,
            createdAtEpochMillis = record.createdAtEpochMillis,
            type = record.type.name,
            result = record.result?.name,
            finishReason = record.finishReason?.name,
            playerDisc = record.playerDisc?.name,
        ),
    )

    fun decode(encoded: String): LocalGameRecord {
        val dto = json.decodeFromString<LocalGameRecordDto>(encoded)
        return LocalGameRecord(
            localId = dto.localId,
            moves = CanonicalMoves.decode(dto.moves),
            createdAtEpochMillis = dto.createdAtEpochMillis,
            type = LocalRecordType.valueOf(dto.type),
            result = dto.result?.let(MatchResult::valueOf),
            finishReason = dto.finishReason?.let(FinishReason::valueOf),
            playerDisc = dto.playerDisc?.let(Disc::valueOf),
        )
    }

    fun encodeList(records: List<LocalGameRecord>): String = records.joinToString("\n", transform = ::encode)

    fun decodeList(encoded: String): List<LocalGameRecord> = encoded.lineSequence()
        .filter { it.isNotBlank() }
        .map(::decode)
        .toList()

    fun decodeListRecovering(encoded: String): LocalGameRecordDecodeResult {
        val records = mutableListOf<LocalGameRecord>()
        val corruptLines = mutableListOf<String>()
        encoded.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                runCatching { decode(line) }
                    .onSuccess(records::add)
                    .onFailure { corruptLines += line }
            }
        return LocalGameRecordDecodeResult(records, corruptLines)
    }
}

data class LocalGameRecordDecodeResult(
    val records: List<LocalGameRecord>,
    val corruptLines: List<String>,
) {
    val hasCorruption: Boolean get() = corruptLines.isNotEmpty()
}

data class LocalGameRecordReadResult(
    val records: List<LocalGameRecord>,
    val corruptLineCount: Int = 0,
    val recoveryCompleted: Boolean = false,
)

interface LocalGameRecordStore {
    suspend fun list(limit: Int = 50): List<LocalGameRecord>
    suspend fun listResult(limit: Int = 50): LocalGameRecordReadResult =
        LocalGameRecordReadResult(list(limit))
    suspend fun save(record: LocalGameRecord)
    suspend fun delete(localId: String)
}
