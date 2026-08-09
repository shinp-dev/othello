package com.example.othello.game

data class Position(val row: Int, val column: Int) {
    init {
        require(row in 0 until Board.SIZE) { "row must be in 0..7" }
        require(column in 0 until Board.SIZE) { "column must be in 0..7" }
    }

    fun index(): Int = row * Board.SIZE + column
}
