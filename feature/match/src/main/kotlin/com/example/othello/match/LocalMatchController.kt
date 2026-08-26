package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.GameStatus
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.TurnResolver
import com.example.othello.records.FinishReason
import com.example.othello.records.LocalGameRecord
import com.example.othello.records.LocalRecordType
import com.example.othello.records.MatchResult
import java.util.UUID

enum class LocalMatchMode { HUMAN, AI }

data class LocalMatchViewState(
    val game: GameState = GameState(),
    val mode: LocalMatchMode = LocalMatchMode.HUMAN,
    val humanDisc: Disc? = null,
    val aiDisc: Disc? = null,
    val message: String = "Black to move",
    val moves: List<Position?> = emptyList(),
    val aiThinking: Boolean = false,
    val error: String? = null,
    val completedRecord: LocalGameRecord? = null,
    val finishReason: FinishReason? = null,
    val canUndo: Boolean = false,
    val undoUsed: Boolean = false,
)

data class LocalMatchUndoResult(
    val invalidatedRecord: LocalGameRecord?,
)

/** Identifies one AI search against one exact local-match position. */
class LocalAiTurnRequest internal constructor(
    internal val requestId: Long,
    internal val positionGeneration: Long,
    val position: GameState,
)

private data class MoveCheckpoint(
    val game: GameState,
    val moves: List<Position?>,
    val mover: Disc,
)

/** Local-only rules coordinator. It has no transport or server dependencies. */
class LocalMatchController(
    private val mode: LocalMatchMode = LocalMatchMode.HUMAN,
    private val humanDisc: Disc? = null,
    initialGame: GameState = GameState(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(mode == LocalMatchMode.HUMAN || humanDisc != null)
    }

    private var state = LocalMatchViewState(
        game = initialGame,
        mode = mode,
        humanDisc = humanDisc,
        aiDisc = humanDisc?.opponent(),
        message = messageFor(initialGame),
    )
    private val listeners = mutableSetOf<(LocalMatchViewState) -> Unit>()
    private val startedAt = nowMillis()
    private val moveCheckpoints = mutableListOf<MoveCheckpoint>()
    private var positionGeneration = 0L
    private var nextAiRequestId = 0L
    private var activeAiRequest: LocalAiTurnRequest? = null

    val viewState: LocalMatchViewState get() = state

    fun observe(listener: (LocalMatchViewState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    fun play(position: Position): Boolean {
        if (!canHumanAct()) return false
        return applyMove(position)
    }

    fun beginAiTurn(): LocalAiTurnRequest? {
        if (mode != LocalMatchMode.AI || state.game.status is GameStatus.Finished ||
            state.finishReason != null || state.game.currentPlayer != state.aiDisc ||
            state.game.legalMoves.isEmpty() || state.aiThinking || activeAiRequest != null
        ) return null
        val request = LocalAiTurnRequest(++nextAiRequestId, positionGeneration, state.game)
        activeAiRequest = request
        update { copy(aiThinking = true, error = null) }
        return request
    }

    fun playAiMove(request: LocalAiTurnRequest, position: Position): Boolean {
        if (!isCurrent(request) || position !in request.position.legalMoves) return false
        activeAiRequest = null
        return applyMove(position)
    }

    fun showAiError(request: LocalAiTurnRequest, message: String): Boolean {
        if (!isCurrent(request)) return false
        activeAiRequest = null
        update { copy(error = message, message = "AI cannot move", aiThinking = false) }
        return true
    }

    fun finishAiTurn(request: LocalAiTurnRequest) {
        if (!isCurrent(request)) return
        activeAiRequest = null
        update { copy(aiThinking = false) }
    }

    /** Invalidates a search even if its native cancellation completes too late. */
    fun cancelAiTurn() {
        if (activeAiRequest == null && !state.aiThinking) return
        activeAiRequest = null
        positionGeneration++
        update { copy(aiThinking = false) }
    }

    fun passAiTurn(): Boolean {
        if (mode != LocalMatchMode.AI || state.aiThinking || state.game.status is GameStatus.Finished || state.game.currentPlayer != state.aiDisc || state.game.legalMoves.isNotEmpty()) return false
        resolveForcedPasses(state.game, state.moves)
        return true
    }

    fun resign(resigningDisc: Disc = humanDisc ?: state.game.currentPlayer): LocalGameRecord? {
        if (state.game.status is GameStatus.Finished || state.finishReason != null) return null
        invalidateAiRequest()
        val record = complete(
            result = resultFor(resigningDisc.opponent()),
            finishReason = FinishReason.RESIGNATION,
        )
        update {
            copy(
                message = "${resigningDisc.name} resigned",
                finishReason = FinishReason.RESIGNATION,
                completedRecord = record,
                aiThinking = false,
            )
        }
        return record
    }

    fun reset() {
        invalidateAiRequest()
        moveCheckpoints.clear()
        state = LocalMatchViewState(
            mode = mode,
            humanDisc = humanDisc,
            aiDisc = humanDisc?.opponent(),
            message = "Black to move",
        )
        notifyListeners()
    }

    /**
     * HUMAN mode restores one real move. AI mode restores the checkpoint before
     * the human's latest real move and removes every later AI response.
     */
    fun undo(): LocalMatchUndoResult? {
        val targetIndex = undoTargetIndex() ?: return null
        val checkpoint = moveCheckpoints[targetIndex]
        val invalidatedRecord = state.completedRecord
        invalidateAiRequest()
        moveCheckpoints.subList(targetIndex, moveCheckpoints.size).clear()
        state = state.copy(
            game = checkpoint.game,
            moves = checkpoint.moves,
            message = messageFor(checkpoint.game),
            aiThinking = false,
            error = null,
            completedRecord = null,
            finishReason = null,
            canUndo = undoTargetIndex() != null,
            undoUsed = state.undoUsed || mode == LocalMatchMode.AI,
        )
        notifyListeners()
        return LocalMatchUndoResult(invalidatedRecord)
    }

    private fun canHumanAct(): Boolean = state.finishReason == null &&
        state.game.status is GameStatus.InProgress &&
        !state.aiThinking &&
        (mode == LocalMatchMode.HUMAN || state.game.currentPlayer == humanDisc)

    private fun applyMove(position: Position): Boolean {
        return when (val outcome = state.game.play(position)) {
            is MoveOutcome.Played -> {
                moveCheckpoints += MoveCheckpoint(state.game, state.moves, state.game.currentPlayer)
                resolveForcedPasses(outcome.state, state.moves + position)
                true
            }
            else -> false
        }
    }

    private fun resolveForcedPasses(game: GameState, moves: List<Position?>) {
        invalidateAiRequest()
        val resolution = TurnResolver.resolveForcedPasses(game)
        val completeMoves = moves + List(resolution.forcedPasses) { null }
        if (resolution.state.status is GameStatus.Finished) {
            val result = resolution.state.result()
            val record = complete(result, FinishReason.NORMAL, completeMoves)
            update {
                copy(
                    game = resolution.state,
                    moves = completeMoves,
                    message = result.label(),
                    completedRecord = record,
                    finishReason = FinishReason.NORMAL,
                    aiThinking = false,
                    error = null,
                    canUndo = undoTargetIndex() != null,
                )
            }
        } else {
            update {
                copy(
                    game = resolution.state,
                    moves = completeMoves,
                    message = messageFor(resolution.state, resolution.forcedPasses),
                    aiThinking = false,
                    error = null,
                    completedRecord = null,
                    finishReason = null,
                    canUndo = undoTargetIndex() != null,
                )
            }
        }
    }

    private fun complete(result: MatchResult, finishReason: FinishReason, moves: List<Position?> = state.moves): LocalGameRecord =
        LocalGameRecord(
            localId = UUID.randomUUID().toString(),
            moves = moves,
            createdAtEpochMillis = nowMillis(),
            type = if (mode == LocalMatchMode.AI) LocalRecordType.LOCAL_AI else LocalRecordType.LOCAL_HUMAN,
            result = result,
            finishReason = finishReason,
            playerDisc = humanDisc,
        )

    private fun resultFor(winner: Disc): MatchResult = when (winner) {
        Disc.BLACK -> MatchResult.BLACK_WIN
        Disc.WHITE -> MatchResult.WHITE_WIN
        Disc.EMPTY -> MatchResult.DRAW
    }

    private fun GameState.result(): MatchResult = when {
        board.count(Disc.BLACK) > board.count(Disc.WHITE) -> MatchResult.BLACK_WIN
        board.count(Disc.WHITE) > board.count(Disc.BLACK) -> MatchResult.WHITE_WIN
        else -> MatchResult.DRAW
    }

    private fun MatchResult.label(): String = when (this) {
        MatchResult.BLACK_WIN -> "Black wins"
        MatchResult.WHITE_WIN -> "White wins"
        MatchResult.DRAW -> "Draw"
    }

    private fun messageFor(game: GameState, forcedPasses: Int = 0): String =
        if (forcedPasses > 0) "Forced pass — ${game.currentPlayer.name} to move" else "${game.currentPlayer.name} to move"

    private fun undoTargetIndex(): Int? = when (mode) {
        LocalMatchMode.HUMAN -> moveCheckpoints.lastIndex.takeIf { it >= 0 }
        LocalMatchMode.AI -> moveCheckpoints.indexOfLast { it.mover == humanDisc }.takeIf { it >= 0 }
    }

    private fun isCurrent(request: LocalAiTurnRequest): Boolean =
        activeAiRequest === request &&
            activeAiRequest?.requestId == request.requestId &&
            request.positionGeneration == positionGeneration &&
            request.position == state.game &&
            state.aiThinking

    private fun invalidateAiRequest() {
        activeAiRequest = null
        positionGeneration++
    }

    private fun update(transform: LocalMatchViewState.() -> LocalMatchViewState) {
        state = state.transform()
        notifyListeners()
    }

    private fun notifyListeners() = listeners.toList().forEach { it(state) }
}
