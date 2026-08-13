package com.example.othello.match

import com.example.othello.game.Disc
import com.example.othello.game.Position
import com.example.othello.records.FinishReason
import com.example.othello.records.LocalRecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalMatchControllerTest {
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
    }

    @Test
    fun resignationIsSavedButLeavingAnInProgressGameIsNot() {
        val controller = LocalMatchController(humanDisc = Disc.BLACK)
        assertNotNull(controller.resign(Disc.BLACK))
        assertEquals(FinishReason.RESIGNATION, controller.viewState.completedRecord?.finishReason)
        assertFalse(LocalMatchController().viewState.completedRecord != null)
    }

    @Test
    fun aiModeAcceptsOnlyAiTurnThroughDedicatedMoveEntryPoint() {
        val controller = LocalMatchController(LocalMatchMode.AI, Disc.WHITE)
        assertFalse(controller.play(Position(2, 3)))
        assertTrue(controller.playAiMove(Position(2, 3)))
        assertEquals(Disc.WHITE, controller.viewState.game.currentPlayer)
    }
}
