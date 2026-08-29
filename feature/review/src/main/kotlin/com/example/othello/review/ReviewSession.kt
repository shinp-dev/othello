package com.example.othello.review

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.TurnResolver
import com.example.othello.records.FinishReason
import com.example.othello.records.GameRecord
import com.example.othello.records.MatchResult

data class Variation(val parentPly: Int, val moves: List<Position?>)

data class ReviewNavigationState(
    val canGoToFirst: Boolean,
    val canGoToPrevious: Boolean,
    val canGoToNext: Boolean,
    val canGoToLast: Boolean,
)

data class ReviewInput(
    val id: String,
    val moves: List<Position?>,
    val title: String = "",
    val result: MatchResult? = null,
    val finishReason: FinishReason? = null,
    val finishedAtEpochMillis: Long? = null,
    val localRecordId: String? = null,
    val localMemo: String? = null,
)

class ReviewSession(private val input: ReviewInput) {
    constructor(record: GameRecord) : this(
        ReviewInput(
            id = record.matchId,
            moves = record.moves,
            title = "オンライン棋譜",
            result = record.result,
            finishReason = record.finishReason,
            finishedAtEpochMillis = record.finishedAtEpochMillis,
        ),
    )

    private val states = buildStates(input.moves)
    private val variations = mutableListOf<Variation>()
    private var variationParentPly: Int? = null
    private val activeVariationMoves = mutableListOf<Position?>()
    private var activeVariationState: GameState? = null
    var cursor: Int = 0
        private set

    val current: GameState get() = activeVariationState ?: states[cursor]
    val reviewInput: ReviewInput get() = input
    val currentVariations: List<Variation> get() = variations.toList()
    val isInVariation: Boolean get() = activeVariationState != null
    val currentVariationLine: List<Position?>?
        get() {
            val parent = variationParentPly ?: return null
            return (input.moves.take(parent) + activeVariationMoves)
                .takeIf { activeVariationMoves.isNotEmpty() }
        }
    val mainLineLastPly: Int get() = states.lastIndex
    val navigationState: ReviewNavigationState
        get() {
            val canMoveBackward = !isInVariation && cursor > 0
            val canMoveForward = !isInVariation && cursor < states.lastIndex
            return ReviewNavigationState(
                canGoToFirst = canMoveBackward,
                canGoToPrevious = canMoveBackward,
                canGoToNext = canMoveForward,
                canGoToLast = canMoveForward,
            )
        }

    fun next() { if (navigationState.canGoToNext) cursor++ }
    fun previous() { if (navigationState.canGoToPrevious) cursor-- }
    fun seek(ply: Int) { if (!isInVariation) cursor = ply.coerceIn(0, states.lastIndex) }
    fun branch(moves: List<Position?>) { variations += Variation(cursor, moves.toList()) }

    fun beginVariation() {
        if (isInVariation) return
        variationParentPly = cursor
        activeVariationMoves.clear()
        activeVariationState = states[cursor]
    }

    fun playVariation(position: Position): Boolean {
        val base = activeVariationState ?: return false
        val played = base.play(position) as? MoveOutcome.Played ?: return false
        val resolution = TurnResolver.resolveForcedPasses(played.state)
        activeVariationMoves += position
        repeat(resolution.forcedPasses) { activeVariationMoves += null }
        activeVariationState = resolution.state
        return true
    }

    fun cancelVariation() {
        variationParentPly = null
        activeVariationMoves.clear()
        activeVariationState = null
    }

    /** Returns a complete initial-position line suitable for LocalGameRecord persistence. */
    fun saveVariationAndReturn(): List<Position?>? {
        val parent = variationParentPly ?: return null
        val variation = activeVariationMoves.toList()
        if (variation.isNotEmpty()) variations += Variation(parent, variation)
        val completeLine = input.moves.take(parent) + variation
        cancelVariation()
        return completeLine.takeIf { variation.isNotEmpty() }
    }

    suspend fun analyze(engine: AnalysisEngine, settings: AnalysisSettings = AnalysisSettings()): AnalysisResult =
        engine.analyze(ReviewPosition(current), settings)

    private fun buildStates(moves: List<Position?>): List<GameState> {
        val result = mutableListOf(GameState())
        moves.forEach { move ->
            val next = if (move == null) result.last().pass() else result.last().play(move)
            result += when (next) {
                is MoveOutcome.Played -> next.state
                is MoveOutcome.Passed -> next.state
                is MoveOutcome.Rejected -> error("record contains an invalid move at ply ${result.lastIndex}")
            }
        }
        return result
    }
}
