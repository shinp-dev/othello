package com.example.othello

import com.example.othello.game.Position
import kotlin.test.assertEquals
import org.junit.Test

class BoardCoordinateTest {
    @Test
    fun coordinateLabelsUseFixedLowercaseNotation() {
        assertEquals("a1", Position(0, 0).coordinateLabel())
        assertEquals("h8", Position(7, 7).coordinateLabel())
    }
}
