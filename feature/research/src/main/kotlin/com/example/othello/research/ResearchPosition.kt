package com.example.othello.research

import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.Position

/** Application-owned contract for the privacy-filtered research position API. */
interface ResearchPositionRepository {
    suspend fun getPosition(positionToken: String, segmentKey: String = "ALL"): ResearchPositionResult
}

sealed interface ResearchPositionResult {
    data class Available(val position: ResearchPosition) : ResearchPositionResult
    data class Unavailable(val reason: ResearchUnavailableReason) : ResearchPositionResult
    data class Failed(val message: String) : ResearchPositionResult
}

enum class ResearchUnavailableReason {
    NOT_ELIGIBLE,
    INSUFFICIENT_SAMPLE,
    NO_PUBLISHED_GENERATION,
    UNSUPPORTED_SEGMENT,
    UNKNOWN,
}

data class ResearchPosition(
    val positionToken: String,
    val generationId: Long,
    val segmentKey: String,
    val publishedAt: String?,
    val uniqueContributors: Int,
    val moves: List<ResearchMove>,
    val other: ResearchMove?,
)

data class ResearchMove(
    val kind: ResearchMoveKind,
    val coordinate: String?,
    val choiceRate: Double,
    val winRate: Double,
    val drawRate: Double,
    val lossRate: Double,
    val uniqueContributors: Int?,
    val canExplore: Boolean,
    val childPositionToken: String?,
)

enum class ResearchMoveKind { MOVE, OTHER }

/** Builds the v1 server position key without applying symmetry normalization. */
fun GameState.researchPositionToken(): String {
    var black = 0UL
    var white = 0UL
    for (row in 0 until 8) {
        for (column in 0 until 8) {
            val bit = 1UL shl (row * 8 + column)
            when (board[Position(row, column)]) {
                Disc.BLACK -> black = black or bit
                Disc.WHITE -> white = white or bit
                Disc.EMPTY -> Unit
            }
        }
    }
    val side = if (currentPlayer == Disc.BLACK) 'B' else 'W'
    return "r8v1n1:${black.toString(16).padStart(16, '0')}:${white.toString(16).padStart(16, '0')}:$side"
}
