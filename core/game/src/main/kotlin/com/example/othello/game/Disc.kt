package com.example.othello.game

enum class Disc {
    EMPTY, BLACK, WHITE;

    fun opponent(): Disc = when (this) {
        BLACK -> WHITE
        WHITE -> BLACK
        EMPTY -> EMPTY
    }
}
