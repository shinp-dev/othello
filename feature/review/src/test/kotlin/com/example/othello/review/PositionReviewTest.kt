package com.example.othello.review

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.ReviewPosition
import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.Position
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PositionReviewTest {
    @Test
    fun fixedPromptDefinesCoordinatesCountsJsonOnlyAndOmitsTurn() {
        assertTrue(POSITION_IMPORT_PROMPT.contains("左上をa1、右下をh8"))
        assertTrue(POSITION_IMPORT_PROMPT.contains("黒石と白石の個数"))
        assertTrue(POSITION_IMPORT_PROMPT.contains("手番を推測しない"))
        assertTrue(POSITION_IMPORT_PROMPT.contains("JSONだけ"))
        assertFalse(POSITION_IMPORT_PROMPT.contains("\"turn\""))
    }

    @Test
    fun parsesValidJsonAndBuildsBoard() {
        val imported = assertIs<PositionImportResult.Success>(PositionImportParser.parse(validJson())).position

        assertEquals(Disc.BLACK, imported.board[Position(3, 3)])
        assertEquals(Disc.BLACK, imported.board[Position(4, 4)])
        assertEquals(Disc.WHITE, imported.board[Position(3, 4)])
        assertEquals(Disc.WHITE, imported.board[Position(4, 3)])
        assertEquals(2, imported.blackCount)
        assertEquals(2, imported.whiteCount)
    }

    @Test
    fun acceptsOneMarkdownFenceAndUnknownFields() {
        val fenced = """
            ```json
            ${validJson().dropLast(1)}, "futureField": "ignored"}
            ```
        """.trimIndent()

        assertIs<PositionImportResult.Success>(PositionImportParser.parse(fenced))
    }

    @Test
    fun rejectsInvalidInputContracts() {
        assertFailure(PositionImportError.INVALID_JSON, "not json")
        assertFailure(PositionImportError.INVALID_JSON, validJson().replace("[\"d4\", \"e5\"]", "\"d4\""))
        assertFailure(PositionImportError.INVALID_FORMAT, validJson().replace(POSITION_IMPORT_FORMAT, "other"))
        assertFailure(PositionImportError.INVALID_COORDINATE, validJson().replace("d4", "i4"))
        assertFailure(PositionImportError.DUPLICATE_BLACK, validJson().replace("[\"d4\", \"e5\"]", "[\"d4\", \"d4\"]"))
        assertFailure(PositionImportError.DUPLICATE_WHITE, validJson().replace("[\"e4\", \"d5\"]", "[\"e4\", \"e4\"]"))
        assertFailure(PositionImportError.OVERLAPPING_COORDINATE, validJson().replace("[\"e4\", \"d5\"]", "[\"d4\", \"d5\"]"))
        assertFailure(PositionImportError.BLACK_COUNT_MISMATCH, validJson().replace("\"blackCount\": 2", "\"blackCount\": 1"))
        assertFailure(PositionImportError.WHITE_COUNT_MISMATCH, validJson().replace("\"whiteCount\": 2", "\"whiteCount\": 1"))
        assertFailure(PositionImportError.TOO_MANY_DISCS, validJson().replace("\"blackCount\": 2", "\"blackCount\": 63"))
    }

    @Test
    fun boardTapCyclesEmptyBlackWhiteEmpty() {
        val position = Position(0, 0)
        val black = PositionBoardEditor.cycle(Board.initial(), position)
        val white = PositionBoardEditor.cycle(black, position)
        val empty = PositionBoardEditor.cycle(white, position)

        assertEquals(Disc.BLACK, black[position])
        assertEquals(Disc.WHITE, white[position])
        assertEquals(Disc.EMPTY, empty[position])
    }

    @Test
    fun selectedSideControlsLegalMovesFromArbitraryBoard() {
        val board = Board.initial()
        val black = PositionReviewSession(board, Disc.BLACK)
        val white = PositionReviewSession(board, Disc.WHITE)

        assertEquals(board.legalMoves(Disc.BLACK), black.current.legalMoves)
        assertEquals(board.legalMoves(Disc.WHITE), white.current.legalMoves)
        assertNotEquals(black.current.currentPlayer, white.current.currentPlayer)
    }

    @Test
    fun startValidationRequiresExplicitPassAndRejectsFinishedPositions() {
        val whiteOnlyMove = Board.fromRows(
            listOf("WB......") + List(7) { "........" },
        )
        assertEquals(
            PositionReviewStartDecision.RequiresPass(Disc.BLACK, Disc.WHITE),
            PositionReviewStartValidator.evaluate(whiteOnlyMove, Disc.BLACK),
        )
        assertEquals(
            PositionReviewStartDecision.Ready(Disc.WHITE),
            PositionReviewStartValidator.evaluate(whiteOnlyMove, Disc.WHITE),
        )
        val finished = Board.fromRows(listOf("B.......") + List(7) { "........" })
        assertEquals(PositionReviewStartDecision.Finished, PositionReviewStartValidator.evaluate(finished, Disc.BLACK))
    }

    @Test
    fun playUndoRedoAndBranchReplacementUseBrowserHistory() {
        val session = PositionReviewSession(Board.initial(), Disc.BLACK)
        val first = session.current.legalMoves.first()
        assertTrue(session.play(first))
        val originalSecond = session.current.legalMoves.first()
        assertTrue(session.play(originalSecond))
        val originalEnd = session.current

        session.previous()
        assertTrue(session.canRedo)
        session.next()
        assertEquals(originalEnd, session.current)

        session.previous()
        val replacement = session.current.legalMoves.first { it != originalSecond }
        assertTrue(session.play(replacement))
        assertFalse(session.canRedo)
        assertEquals(listOf(first, replacement), session.history)

        session.reset()
        assertEquals(session.rootState, session.current)
    }

    @Test
    fun arbitraryPositionUsesExistingAnalysisBoundary() = runBlocking {
        val state = GameState(Board.initial(), Disc.WHITE)
        val session = PositionReviewSession(state.board, state.currentPlayer)
        var received: ReviewPosition? = null
        val engine = object : AnalysisEngine {
            override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult {
                received = position
                return AnalysisResult(emptyList())
            }
        }

        session.analyze(engine)

        assertEquals(state, received?.state)
    }

    @Test
    fun savedRecordRestoresInitialBoardSideHistoryAndCursor() {
        val session = PositionReviewSession(Board.initial(), Disc.WHITE)
        val first = session.current.legalMoves.first()
        session.play(first)
        val second = session.current.legalMoves.first()
        session.play(second)
        session.previous()
        val record = session.toRecord("position-1", "大会局面", 10, 20)

        val decoded = PositionReviewRecordJson.decode(PositionReviewRecordJson.encode(record))
        val restored = PositionReviewSession(decoded)

        assertEquals(record, decoded)
        assertEquals(session.rootState, restored.rootState)
        assertEquals(session.history, restored.history)
        assertEquals(session.cursor, restored.cursor)
        assertEquals(session.current, restored.current)
        assertTrue(restored.canRedo)
    }

    private fun assertFailure(expected: PositionImportError, json: String) {
        assertEquals(expected, assertIs<PositionImportResult.Failure>(PositionImportParser.parse(json)).error)
    }

    private fun validJson(): String = """
        {
          "format": "$POSITION_IMPORT_FORMAT",
          "black": ["d4", "e5"],
          "white": ["e4", "d5"],
          "blackCount": 2,
          "whiteCount": 2
        }
    """.trimIndent()
}
