package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.TurnResolver
import com.example.othello.records.FinishReason
import com.example.othello.records.LocalRecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalMatchControllerTest {
    @Test
    fun initialPositionCannotBeUndone() {
        val controller = LocalMatchController()

        assertEquals(LocalMatchStatusMessage.Turn(Disc.BLACK), controller.viewState.message)
        assertFalse(controller.viewState.canUndo)
        assertNull(controller.undo())
        assertFalse(controller.viewState.undoUsed)
    }

    @Test
    fun humanMatchUndoesOneRealMoveAtATime() {
        val controller = LocalMatchController()
        val initial = controller.viewState.game
        assertTrue(controller.play(initial.legalMoves.first()))
        val afterFirst = controller.viewState.game
        val firstTranscript = controller.viewState.moves
        assertTrue(controller.play(afterFirst.legalMoves.first()))

        assertNotNull(controller.undo())
        assertEquals(afterFirst, controller.viewState.game)
        assertEquals(firstTranscript, controller.viewState.moves)
        assertTrue(controller.viewState.canUndo)

        assertNotNull(controller.undo())
        assertEquals(initial, controller.viewState.game)
        assertEquals(emptyList(), controller.viewState.moves)
        assertFalse(controller.viewState.canUndo)
        assertFalse(controller.viewState.undoUsed)
    }

    @Test
    fun humanMatchCanChooseAnotherMoveAfterUndo() {
        val controller = LocalMatchController()
        assertTrue(controller.play(controller.viewState.game.legalMoves.first()))
        val branchPoint = controller.viewState.game
        val discardedMove = branchPoint.legalMoves.first()
        assertTrue(controller.play(discardedMove))

        assertNotNull(controller.undo())
        val replacement = controller.viewState.game.legalMoves.first { it != discardedMove }
        assertTrue(controller.play(replacement))

        assertEquals(replacement, controller.viewState.moves.last())
        assertFalse(discardedMove == controller.viewState.moves.last())
    }

    @Test
    fun withdrawnMoveIsNotIncludedInLaterCompletedRecord() {
        val controller = LocalMatchController()
        val firstMove = controller.viewState.game.legalMoves.first()
        assertTrue(controller.play(firstMove))
        val branchPoint = controller.viewState.game
        val withdrawnMove = branchPoint.legalMoves.first()
        assertTrue(controller.play(withdrawnMove))
        assertNotNull(controller.undo())
        val replacementMove = controller.viewState.game.legalMoves.first { it != withdrawnMove }
        assertTrue(controller.play(replacementMove))

        val record = assertNotNull(controller.resign())

        assertEquals(listOf(firstMove, replacementMove), record.moves)
        assertFalse(record.moves.last() == withdrawnMove)
    }

    @Test
    fun humanUndoRestoresExactStateAcrossForcedPass() {
        val (position, move) = findMoveCausingForcedPass()
        val controller = LocalMatchController(initialGame = position)

        assertTrue(controller.play(move))
        assertEquals(null, controller.viewState.moves.last())
        assertEquals(
            LocalMatchStatusMessage.Turn(controller.viewState.game.currentPlayer, forcedPass = true),
            controller.viewState.message,
        )
        assertNotNull(controller.undo())

        assertEquals(position, controller.viewState.game)
        assertEquals(emptyList(), controller.viewState.moves)
        assertEquals(LocalMatchStatusMessage.Turn(position.currentPlayer), controller.viewState.message)
    }

    @Test
    fun humanMatchCanUndoAfterNormalGameEnd() {
        val controller = LocalMatchController()
        var beforeFinalMove = controller.viewState.game
        var transcriptBeforeFinalMove = controller.viewState.moves
        var guard = 0
        while (controller.viewState.completedRecord == null && guard++ < 120) {
            beforeFinalMove = controller.viewState.game
            transcriptBeforeFinalMove = controller.viewState.moves
            assertTrue(controller.play(controller.viewState.game.legalMoves.first()))
        }
        val completed = assertNotNull(controller.viewState.completedRecord)

        val undo = assertNotNull(controller.undo())

        assertEquals(completed, undo.invalidatedRecord)
        assertEquals(beforeFinalMove, controller.viewState.game)
        assertEquals(transcriptBeforeFinalMove, controller.viewState.moves)
        assertNull(controller.viewState.completedRecord)
        assertNull(controller.viewState.finishReason)
        assertTrue(controller.viewState.game.status is com.example.othello.game.GameStatus.InProgress)
    }

    @Test
    fun normalHumanGameEmitsOneCompleteLocalRecord() {
        val controller = LocalMatchController()
        var emitted = 0
        controller.observe { if (it.completedRecord != null) emitted++ }
        var guard = 0
        while (controller.viewState.completedRecord == null && guard++ < 120) controller.play(controller.viewState.game.legalMoves.first())
        assertTrue(guard < 120)
        assertEquals(1, emitted)
        assertEquals(LocalRecordType.LOCAL_HUMAN, controller.viewState.completedRecord?.type)
        assertEquals(FinishReason.NORMAL, controller.viewState.completedRecord?.finishReason)
        assertEquals(controller.viewState.moves, controller.viewState.completedRecord?.moves)
        val result = assertNotNull(controller.viewState.completedRecord?.result)
        assertEquals(LocalMatchStatusMessage.GameResult(result), controller.viewState.message)
    }

    @Test
    fun resignationIsSavedButLeavingAnInProgressGameIsNot() {
        val controller = LocalMatchController(humanDisc = Disc.BLACK)
        assertNotNull(controller.resign(Disc.BLACK))
        assertEquals(FinishReason.RESIGNATION, controller.viewState.completedRecord?.finishReason)
        assertEquals(LocalMatchStatusMessage.Resigned(Disc.BLACK), controller.viewState.message)
        assertFalse(LocalMatchController().viewState.completedRecord != null)
    }

    @Test
    fun aiModeAcceptsOnlyAiTurnThroughDedicatedMoveEntryPoint() {
        val controller = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        assertFalse(controller.play(Position(2, 3)))
        val request = assertNotNull(controller.beginAiTurn())
        assertTrue(controller.playAiMove(request, Position(2, 3)))
        assertEquals(Disc.WHITE, controller.viewState.game.currentPlayer)
    }

    @Test
    fun aiUndoReturnsToBeforeHumansLatestDecision() {
        val controller = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        val initial = controller.viewState.game
        assertTrue(controller.play(initial.legalMoves.first()))
        playAiMove(controller)

        assertNotNull(controller.undo())

        assertEquals(initial, controller.viewState.game)
        assertEquals(emptyList(), controller.viewState.moves)
        assertTrue(controller.viewState.undoUsed)
        assertFalse(controller.viewState.canUndo)
    }

    @Test
    fun aiOpeningMoveBeforeAnyHumanDecisionCannotBeUndone() {
        val controller = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        playAiMove(controller)

        assertFalse(controller.viewState.canUndo)
        assertNull(controller.undo())
        assertFalse(controller.viewState.undoUsed)
    }

    @Test
    fun aiUndoInvalidatesThinkingRequestAndRejectsLateMove() {
        val controller = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        val initial = controller.viewState.game
        assertTrue(controller.play(initial.legalMoves.first()))
        val request = assertNotNull(controller.beginAiTurn())
        val staleMove = request.position.legalMoves.first()

        assertNotNull(controller.undo())

        assertFalse(controller.playAiMove(request, staleMove))
        assertEquals(initial, controller.viewState.game)
        assertEquals(emptyList(), controller.viewState.moves)
        assertFalse(controller.viewState.aiThinking)
    }

    @Test
    fun aiUndoCrossesForcedPassAndRestoresTurnState() {
        val (position, move) = findMoveCausingForcedPass()
        val controller = LocalMatchController(
            mode = LocalMatchMode.AI,
            humanDisc = position.currentPlayer,
            initialGame = position,
        )

        assertTrue(controller.play(move))
        assertEquals(null, controller.viewState.moves.last())
        assertNotNull(controller.undo())

        assertEquals(position, controller.viewState.game)
        assertEquals(position.currentPlayer, controller.viewState.game.currentPlayer)
        assertTrue(controller.viewState.undoUsed)
    }

    @Test
    fun aiMatchCanUndoAfterGameEnd() {
        val controller = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        var beforeLatestHumanMove = controller.viewState.game
        var transcriptBeforeLatestHumanMove = controller.viewState.moves
        var guard = 0
        while (controller.viewState.completedRecord == null && guard++ < 120) {
            if (controller.viewState.game.currentPlayer == controller.viewState.humanDisc) {
                beforeLatestHumanMove = controller.viewState.game
                transcriptBeforeLatestHumanMove = controller.viewState.moves
                assertTrue(controller.play(controller.viewState.game.legalMoves.first()))
            } else {
                playAiMove(controller)
            }
        }
        assertNotNull(controller.viewState.completedRecord)

        assertNotNull(controller.undo())

        assertEquals(beforeLatestHumanMove, controller.viewState.game)
        assertEquals(transcriptBeforeLatestHumanMove, controller.viewState.moves)
        assertNull(controller.viewState.completedRecord)
        assertTrue(controller.viewState.undoUsed)
    }

    @Test
    fun undoUsedChangesOnlyOnSuccessfulAiUndoAndResetsForNewMatch() {
        val controller = LocalMatchController(LocalMatchMode.AI, Disc.BLACK)
        assertNull(controller.undo())
        assertFalse(controller.viewState.undoUsed)

        assertTrue(controller.play(controller.viewState.game.legalMoves.first()))
        assertNotNull(controller.undo())
        assertTrue(controller.viewState.undoUsed)
        assertNull(controller.undo())
        assertTrue(controller.viewState.undoUsed)

        assertTrue(controller.play(controller.viewState.game.legalMoves.last()))
        playAiMove(controller)
        assertTrue(controller.viewState.undoUsed)

        controller.reset()
        assertEquals(LocalMatchStatusMessage.Turn(Disc.BLACK), controller.viewState.message)
        assertFalse(controller.viewState.undoUsed)
        assertFalse(controller.viewState.canUndo)
    }

    @Test
    fun cancellingAiOpeningWithoutRestorableHumanMoveDoesNotMarkUndoUsed() {
        val controller = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        assertNotNull(controller.beginAiTurn())

        controller.cancelAiTurn()

        assertNull(controller.undo())
        assertFalse(controller.viewState.undoUsed)
        assertFalse(controller.viewState.canUndo)
    }

    @Test
    fun staleRequestCannotFinishOrMoveForNewRequestOnSamePosition() {
        val controller = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        val stale = assertNotNull(controller.beginAiTurn())
        controller.cancelAiTurn()
        val current = assertNotNull(controller.beginAiTurn())

        controller.finishAiTurn(stale)
        assertTrue(controller.viewState.aiThinking)
        assertFalse(controller.playAiMove(stale, stale.position.legalMoves.first()))
        assertTrue(controller.playAiMove(current, current.position.legalMoves.first()))

        assertEquals(1, controller.viewState.moves.filterNotNull().size)
        assertFalse(controller.viewState.aiThinking)
    }

    @Test
    fun aiForcedPassIsRecordedExactlyOnce() {
        var game = GameState()
        var found = false
        repeat(120) {
            if (found || game.status !is com.example.othello.game.GameStatus.InProgress) return@repeat
            val move = game.legalMoves.firstOrNull() ?: return@repeat
            val played = game.play(move) as? MoveOutcome.Played ?: return@repeat
            val resolution = TurnResolver.resolveForcedPasses(played.state)
            if (resolution.forcedPasses == 1) {
                val controller = LocalMatchController(
                    mode = LocalMatchMode.AI,
                    humanDisc = played.state.currentPlayer.opponent(),
                    initialGame = played.state,
                )
                assertTrue(controller.passAiTurn())
                assertEquals(listOf<Position?>(null), controller.viewState.moves)
                found = true
            }
            game = resolution.state
        }
        assertTrue(found, "expected a deterministic single forced-pass position")
    }

    private fun playAiMove(controller: LocalMatchController) {
        val request = assertNotNull(controller.beginAiTurn())
        assertTrue(controller.playAiMove(request, request.position.legalMoves.first()))
    }

    private fun findMoveCausingForcedPass(): Pair<GameState, Position> {
        var game = GameState()
        repeat(120) {
            val move = game.legalMoves.firstOrNull() ?: error("deterministic game ended before forced pass")
            val played = game.play(move) as MoveOutcome.Played
            val resolution = TurnResolver.resolveForcedPasses(played.state)
            if (resolution.forcedPasses == 1) return game to move
            game = resolution.state
        }
        error("expected a deterministic single forced-pass position")
    }
}
