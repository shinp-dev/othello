package com.example.othello.game

class Board private constructor(private val cells: IntArray) {
    init { require(cells.size == CELL_COUNT) }

    operator fun get(position: Position): Disc = Disc.entries[cells[position.index()]]

    fun count(disc: Disc): Int = cells.count { it == disc.ordinal }

    fun emptyCount(): Int = count(Disc.EMPTY)

    fun positionsOf(disc: Disc): List<Position> = buildList {
        cells.forEachIndexed { index, value ->
            if (value == disc.ordinal) add(Position(index / SIZE, index % SIZE))
        }
    }

    fun legalMoves(player: Disc): Set<Position> = positionsOf(Disc.EMPTY)
        .filterTo(linkedSetOf()) { capturedForMove(it, player).isNotEmpty() }

    fun flipped(position: Position, player: Disc): Board {
        val captured = capturedForMove(position, player)
        require(get(position) == Disc.EMPTY && captured.isNotEmpty()) { "position is not a legal move" }
        val next = cells.copyOf()
        next[position.index()] = player.ordinal
        captured.forEach { next[it.index()] = player.ordinal }
        return Board(next)
    }

    fun toCompactString(): String = cells.joinToString(separator = "") { it.toString() }

    fun stateHash(): String {
        var hash = FNV_OFFSET
        cells.forEach { value ->
            hash = (hash xor value.toLong()) * FNV_PRIME
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }

    internal fun capturedForMove(position: Position, player: Disc): List<Position> = DIRECTIONS.flatMap { (dr, dc) ->
        val line = mutableListOf<Position>()
        var row = position.row + dr
        var column = position.column + dc
        while (row in 0 until SIZE && column in 0 until SIZE && get(Position(row, column)) == player.opponent()) {
            line += Position(row, column)
            row += dr
            column += dc
        }
        if (line.isNotEmpty() && row in 0 until SIZE && column in 0 until SIZE && get(Position(row, column)) == player) line else emptyList()
    }

    override fun equals(other: Any?): Boolean = other is Board && cells.contentEquals(other.cells)
    override fun hashCode(): Int = cells.contentHashCode()

    companion object {
        const val SIZE = 8
        private const val CELL_COUNT = SIZE * SIZE
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
        private val DIRECTIONS = listOf(
            -1 to -1, -1 to 0, -1 to 1,
            0 to -1, 0 to 1,
            1 to -1, 1 to 0, 1 to 1,
        )

        fun initial(): Board {
            val cells = IntArray(CELL_COUNT) { Disc.EMPTY.ordinal }
            cells[Position(3, 3).index()] = Disc.WHITE.ordinal
            cells[Position(3, 4).index()] = Disc.BLACK.ordinal
            cells[Position(4, 3).index()] = Disc.BLACK.ordinal
            cells[Position(4, 4).index()] = Disc.WHITE.ordinal
            return Board(cells)
        }

        fun fromRows(rows: List<String>): Board {
            require(rows.size == SIZE && rows.all { it.length == SIZE })
            val cells = rows.flatMap { row -> row.map { char ->
                when (char) { 'B' -> Disc.BLACK.ordinal; 'W' -> Disc.WHITE.ordinal; '.' -> Disc.EMPTY.ordinal; else -> error("unknown cell") }
            } }.toIntArray()
            return Board(cells)
        }
    }
}
