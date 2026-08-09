package com.example.othello.records

import com.example.othello.game.Disc
import com.example.othello.game.Position

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
) {
    init { require(players.size == 2); require(moves.size <= 120) }
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
