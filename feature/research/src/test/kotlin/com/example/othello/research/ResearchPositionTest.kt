package com.example.othello.research

import com.example.othello.game.Disc
import com.example.othello.game.GameState
import kotlin.test.Test
import kotlin.test.assertEquals

class ResearchPositionTest {
    @Test
    fun initialPositionUsesServerV1BitboardToken() {
        assertEquals(
            "r8v1n1:0000000810000000:0000001008000000:B",
            GameState().researchPositionToken(),
        )
    }

    @Test
    fun sideToMoveIsPartOfThePositionToken() {
        val whiteToMove = GameState(currentPlayer = Disc.WHITE)
        assertEquals(
            "r8v1n1:0000000810000000:0000001008000000:W",
            whiteToMove.researchPositionToken(),
        )
    }
}
