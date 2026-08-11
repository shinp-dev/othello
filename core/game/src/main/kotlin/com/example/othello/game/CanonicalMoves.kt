package com.example.othello.game

/** Compact deterministic move history: column + one-based row; `--` is a pass. */
object CanonicalMoves {
    private const val PASS = "--"

    fun encode(moves: List<Position?>): String = buildString(moves.size * 2) {
        moves.forEach { move ->
            append(move?.let { "${('a'.code + it.column).toChar()}${it.row + 1}" } ?: PASS)
        }
    }

    fun decode(encoded: String): List<Position?> {
        require(encoded.length % 2 == 0) { "canonical moves must contain two characters per ply" }
        return encoded.chunked(2).map { token ->
            if (token == PASS) return@map null
            require(token[0] in 'a'..'h' && token[1] in '1'..'8') { "invalid canonical move: $token" }
            Position(token[1] - '1', token[0] - 'a')
        }
    }
}
