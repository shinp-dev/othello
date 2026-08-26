package com.example.othello.review

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.TurnResolver
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val POSITION_IMPORT_FORMAT = "chanriva-position-v1"

val POSITION_IMPORT_PROMPT = """
    添付したオセロ盤画像を読み取ってください。
    盤面座標は左上をa1、右下をh8とし、黒石と白石の座標を小文字で列挙してください。
    黒石と白石の個数も数えてください。画像だけから手番を推測しないでください。
    説明文やMarkdownコードフェンスは付けず、次の形式のJSONだけを返してください。
    {
      "format": "chanriva-position-v1",
      "black": ["d4", "e5"],
      "white": ["e4", "d5"],
      "blackCount": 2,
      "whiteCount": 2
    }
""".trimIndent()

val POSITION_IMPORT_PROMPT_ENGLISH = """
    Read the attached Othello board image.
    Use a1 for the top-left square and h8 for the bottom-right square, and list the black and white disc coordinates in lowercase.
    Count the black and white discs as well. Do not infer which side is to move from the image.
    Do not add explanatory text or Markdown fences. Return only JSON in this format:
    {
      "format": "chanriva-position-v1",
      "black": ["d4", "e5"],
      "white": ["e4", "d5"],
      "blackCount": 2,
      "whiteCount": 2
    }
""".trimIndent()

enum class PositionImportError {
    EMPTY_INPUT,
    INVALID_JSON,
    INVALID_FORMAT,
    INVALID_COORDINATE,
    DUPLICATE_BLACK,
    DUPLICATE_WHITE,
    OVERLAPPING_COORDINATE,
    INVALID_COUNT,
    BLACK_COUNT_MISMATCH,
    WHITE_COUNT_MISMATCH,
    TOO_MANY_DISCS,
}

data class ImportedPosition(
    val board: Board,
    val blackCount: Int,
    val whiteCount: Int,
)

sealed interface PositionImportResult {
    data class Success(val position: ImportedPosition) : PositionImportResult
    data class Failure(val error: PositionImportError) : PositionImportResult
}

@Serializable
private data class PositionImportDto(
    val format: String,
    val black: List<String>,
    val white: List<String>,
    val blackCount: Int,
    val whiteCount: Int,
)

object PositionImportParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val coordinatePattern = Regex("^[a-h][1-8]$")

    fun parse(raw: String): PositionImportResult {
        if (raw.isBlank()) return PositionImportResult.Failure(PositionImportError.EMPTY_INPUT)
        val encoded = stripSingleMarkdownFence(raw.trim())
            ?: return PositionImportResult.Failure(PositionImportError.INVALID_JSON)
        val dto = runCatching { json.decodeFromString<PositionImportDto>(encoded) }.getOrElse {
            return PositionImportResult.Failure(PositionImportError.INVALID_JSON)
        }
        if (dto.format != POSITION_IMPORT_FORMAT) {
            return PositionImportResult.Failure(PositionImportError.INVALID_FORMAT)
        }
        if (dto.black.any { !coordinatePattern.matches(it) } || dto.white.any { !coordinatePattern.matches(it) }) {
            return PositionImportResult.Failure(PositionImportError.INVALID_COORDINATE)
        }
        if (dto.black.distinct().size != dto.black.size) {
            return PositionImportResult.Failure(PositionImportError.DUPLICATE_BLACK)
        }
        if (dto.white.distinct().size != dto.white.size) {
            return PositionImportResult.Failure(PositionImportError.DUPLICATE_WHITE)
        }
        if (dto.black.toSet().intersect(dto.white.toSet()).isNotEmpty()) {
            return PositionImportResult.Failure(PositionImportError.OVERLAPPING_COORDINATE)
        }
        if (dto.blackCount < 0 || dto.whiteCount < 0) {
            return PositionImportResult.Failure(PositionImportError.INVALID_COUNT)
        }
        if (dto.blackCount.toLong() + dto.whiteCount.toLong() > Board.SIZE * Board.SIZE) {
            return PositionImportResult.Failure(PositionImportError.TOO_MANY_DISCS)
        }
        if (dto.black.size != dto.blackCount) {
            return PositionImportResult.Failure(PositionImportError.BLACK_COUNT_MISMATCH)
        }
        if (dto.white.size != dto.whiteCount) {
            return PositionImportResult.Failure(PositionImportError.WHITE_COUNT_MISMATCH)
        }
        val black = dto.black.map(::coordinateToPosition).toSet()
        val white = dto.white.map(::coordinateToPosition).toSet()
        return PositionImportResult.Success(
            ImportedPosition(
                board = boardFromPositions(black, white),
                blackCount = black.size,
                whiteCount = white.size,
            ),
        )
    }

    private fun stripSingleMarkdownFence(input: String): String? {
        if (!input.startsWith("```")) return input
        val lines = input.lines()
        if (lines.size < 3 || lines.last().trim() != "```") return null
        val opening = lines.first().trim()
        if (opening != "```" && opening.lowercase() != "```json") return null
        return lines.subList(1, lines.lastIndex).joinToString("\n").trim()
    }
}

object PositionBoardEditor {
    fun cycle(board: Board, position: Position): Board {
        val replacement = when (board[position]) {
            Disc.EMPTY -> Disc.BLACK
            Disc.BLACK -> Disc.WHITE
            Disc.WHITE -> Disc.EMPTY
        }
        val black = board.positionsOf(Disc.BLACK).toMutableSet()
        val white = board.positionsOf(Disc.WHITE).toMutableSet()
        black.remove(position)
        white.remove(position)
        when (replacement) {
            Disc.BLACK -> black += position
            Disc.WHITE -> white += position
            Disc.EMPTY -> Unit
        }
        return boardFromPositions(black, white)
    }
}

sealed interface PositionReviewStartDecision {
    data class Ready(val sideToMove: Disc) : PositionReviewStartDecision
    data class RequiresPass(val selectedSide: Disc, val sideAfterPass: Disc) : PositionReviewStartDecision
    data object Finished : PositionReviewStartDecision
}

object PositionReviewStartValidator {
    fun evaluate(board: Board, selectedSide: Disc): PositionReviewStartDecision {
        require(selectedSide != Disc.EMPTY)
        if (board.legalMoves(selectedSide).isNotEmpty()) {
            return PositionReviewStartDecision.Ready(selectedSide)
        }
        val opponent = selectedSide.opponent()
        return if (board.legalMoves(opponent).isNotEmpty()) {
            PositionReviewStartDecision.RequiresPass(selectedSide, opponent)
        } else {
            PositionReviewStartDecision.Finished
        }
    }
}

data class PositionReviewRecord(
    val id: String,
    val title: String,
    val initialBoard: Board,
    val initialSideToMove: Disc,
    val moves: List<Position>,
    val cursor: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(initialSideToMove != Disc.EMPTY)
        require(moves.size <= Board.SIZE * Board.SIZE)
        require(cursor in 0..moves.size)
        require(createdAtEpochMillis >= 0)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
    }
}

@Serializable
private data class PositionReviewRecordDto(
    val id: String,
    val title: String,
    @SerialName("initial_board") val initialBoard: String,
    @SerialName("initial_side_to_move") val initialSideToMove: String,
    val moves: List<String>,
    val cursor: Int,
    @SerialName("created_at") val createdAtEpochMillis: Long,
    @SerialName("updated_at") val updatedAtEpochMillis: Long,
)

data class PositionReviewDecodeResult(
    val records: List<PositionReviewRecord>,
    val corruptLines: List<String>,
) {
    val hasCorruption: Boolean get() = corruptLines.isNotEmpty()
}

object PositionReviewRecordJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(record: PositionReviewRecord): String = json.encodeToString(
        PositionReviewRecordDto(
            id = record.id,
            title = record.title,
            initialBoard = record.initialBoard.toCompactString(),
            initialSideToMove = record.initialSideToMove.name,
            moves = record.moves.map(::positionToCoordinate),
            cursor = record.cursor,
            createdAtEpochMillis = record.createdAtEpochMillis,
            updatedAtEpochMillis = record.updatedAtEpochMillis,
        ),
    )

    fun decode(encoded: String): PositionReviewRecord {
        val dto = json.decodeFromString<PositionReviewRecordDto>(encoded)
        val record = PositionReviewRecord(
            id = dto.id,
            title = dto.title,
            initialBoard = boardFromCompactString(dto.initialBoard),
            initialSideToMove = Disc.valueOf(dto.initialSideToMove),
            moves = dto.moves.map { coordinate ->
                require(Regex("^[a-h][1-8]$").matches(coordinate)) { "invalid saved coordinate" }
                coordinateToPosition(coordinate)
            },
            cursor = dto.cursor,
            createdAtEpochMillis = dto.createdAtEpochMillis,
            updatedAtEpochMillis = dto.updatedAtEpochMillis,
        )
        PositionReviewSession(record)
        return record
    }

    fun encodeList(records: List<PositionReviewRecord>): String = records.joinToString("\n", transform = ::encode)

    fun decodeListRecovering(encoded: String): PositionReviewDecodeResult {
        val records = mutableListOf<PositionReviewRecord>()
        val corruptLines = mutableListOf<String>()
        encoded.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            runCatching { decode(line) }
                .onSuccess(records::add)
                .onFailure { corruptLines += line }
        }
        return PositionReviewDecodeResult(records, corruptLines)
    }
}

interface PositionReviewStore {
    suspend fun list(limit: Int = 50): List<PositionReviewRecord>
    suspend fun save(record: PositionReviewRecord)
    suspend fun delete(id: String)
}

class PositionReviewSession private constructor(
    val rootState: GameState,
    initialMoves: List<Position>,
    initialCursor: Int,
) {
    constructor(board: Board, sideToMove: Disc) : this(
        rootState = GameState(board = board, currentPlayer = sideToMove),
        initialMoves = emptyList(),
        initialCursor = 0,
    )

    constructor(record: PositionReviewRecord) : this(
        rootState = GameState(board = record.initialBoard, currentPlayer = record.initialSideToMove),
        initialMoves = record.moves,
        initialCursor = record.cursor,
    )

    private val moves = initialMoves.toMutableList()
    private val states = buildStates(rootState, initialMoves).toMutableList()
    var cursor: Int = initialCursor
        private set

    init {
        require(rootState.currentPlayer != Disc.EMPTY)
        require(initialCursor in states.indices)
    }

    val current: GameState get() = states[cursor]
    val history: List<Position> get() = moves.toList()
    val lastIndex: Int get() = states.lastIndex
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < states.lastIndex

    fun play(position: Position): Boolean {
        val played = current.play(position) as? MoveOutcome.Played ?: return false
        if (cursor < moves.size) {
            moves.subList(cursor, moves.size).clear()
            states.subList(cursor + 1, states.size).clear()
        }
        val resolved = TurnResolver.resolveForcedPasses(played.state).state
        moves += position
        states += resolved
        cursor++
        return true
    }

    fun previous() {
        if (canUndo) cursor--
    }

    fun next() {
        if (canRedo) cursor++
    }

    fun reset() {
        cursor = 0
    }

    suspend fun analyze(engine: AnalysisEngine, settings: AnalysisSettings = AnalysisSettings()): AnalysisResult =
        engine.analyze(ReviewPosition(current), settings)

    fun toRecord(
        id: String,
        title: String,
        createdAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ): PositionReviewRecord = PositionReviewRecord(
        id = id,
        title = title.trim().ifEmpty { "盤面検討" },
        initialBoard = rootState.board,
        initialSideToMove = rootState.currentPlayer,
        moves = history,
        cursor = cursor,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private companion object {
        fun buildStates(root: GameState, moves: List<Position>): List<GameState> {
            val result = mutableListOf(root)
            moves.forEachIndexed { index, move ->
                val played = result.last().play(move) as? MoveOutcome.Played
                    ?: error("saved position review contains an invalid move at index $index")
                result += TurnResolver.resolveForcedPasses(played.state).state
            }
            return result
        }
    }
}

private fun coordinateToPosition(coordinate: String): Position = Position(
    row = coordinate[1].digitToInt() - 1,
    column = coordinate[0] - 'a',
)

private fun positionToCoordinate(position: Position): String =
    "${('a'.code + position.column).toChar()}${position.row + 1}"

private fun boardFromPositions(black: Set<Position>, white: Set<Position>): Board = Board.fromRows(
    List(Board.SIZE) { row ->
        buildString(Board.SIZE) {
            repeat(Board.SIZE) { column ->
                val position = Position(row, column)
                append(
                    when (position) {
                        in black -> 'B'
                        in white -> 'W'
                        else -> '.'
                    },
                )
            }
        }
    },
)

private fun boardFromCompactString(encoded: String): Board {
    require(encoded.length == Board.SIZE * Board.SIZE) { "invalid saved board length" }
    return Board.fromRows(
        encoded.chunked(Board.SIZE).map { row ->
            row.map { cell ->
                when (cell) {
                    '0' -> '.'
                    '1' -> 'B'
                    '2' -> 'W'
                    else -> error("invalid saved board cell")
                }
            }.joinToString("")
        },
    )
}
