package com.example.othello.theory

import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position

enum class TheoryMetricDirection {
    LOWER_IS_GENERALLY_BETTER,
    HIGHER_IS_GENERALLY_BETTER,
}

data class TheoryMetricText(
    val displayName: String,
    val directionLabel: String,
    val shortDescription: String,
    val detailedDescription: String,
    val rangeDescription: String,
    val caution: String,
)

/**
 * A deterministic board-derived metric. Implementations must not use Edax output or search state.
 */
class TheoryMetricDefinition internal constructor(
    val id: String,
    val direction: TheoryMetricDirection,
    private val japaneseText: TheoryMetricText,
    private val englishText: TheoryMetricText,
    private val calculator: (TheoryCandidateContext) -> Int,
    private val formatter: (Int) -> String = Int::toString,
) {
    fun text(languageTag: String): TheoryMetricText =
        if (languageTag.lowercase().startsWith("ja")) japaneseText else englishText

    internal fun calculate(context: TheoryCandidateContext): Int = calculator(context)

    fun format(value: Int): String = formatter(value)
}

internal data class TheoryCandidateContext(
    val mover: Disc,
    val move: Position,
    val boardAfterMove: Board,
    val flipped: List<Position>,
)

data class TheoryCandidateMetrics(
    val move: Position,
    val values: Map<String, Int>,
) {
    fun value(metricId: String): Int? = values[metricId]
}

/** Adding a metric here makes it available to calculation, switching, and explanation UI. */
object TheoryMetricRegistry {
    val definitions: List<TheoryMetricDefinition> = listOf(
        TheoryMetricDefinition(
            id = "openness",
            direction = TheoryMetricDirection.LOWER_IS_GENERALLY_BETTER,
            japaneseText = TheoryMetricText(
                displayName = "開放度",
                directionLabel = "↓ 小さいほど一般に良い",
                shortDescription = "この手で反転した石の周囲に残る空きの合計です。",
                detailedDescription =
                    "着手直後、反転した各石の8近傍にある空きマスを数えます。" +
                        "同じ空きマスが複数の反転石に接している場合は重複して数えます。",
                rangeDescription = "最小値：0",
                caution = "この指標だけで手の良し悪しが決まるわけではありません。",
            ),
            englishText = TheoryMetricText(
                displayName = "Openness",
                directionLabel = "↓ Lower is generally better",
                shortDescription = "The total open space around the discs flipped by this move.",
                detailedDescription =
                    "After the move, empty squares in the eight-neighborhood of every flipped disc are counted. " +
                        "An empty square touching multiple flipped discs is counted once for each disc.",
                rangeDescription = "Minimum: 0",
                caution = "This metric alone does not determine whether a move is good.",
            ),
            calculator = { context ->
                context.flipped.sumOf { flipped ->
                    flipped.neighbors().count { context.boardAfterMove[it] == Disc.EMPTY }
                }
            },
        ),
        TheoryMetricDefinition(
            id = "opponent_mobility",
            direction = TheoryMetricDirection.LOWER_IS_GENERALLY_BETTER,
            japaneseText = TheoryMetricText(
                displayName = "相手モビリティ",
                directionLabel = "↓ 小さいほど一般に良い",
                shortDescription = "この手を打った直後に、相手が打てる場所の数です。",
                detailedDescription =
                    "相手の選択肢を少なくすることは一般に有利と考えられます。0なら相手はパスします。",
                rangeDescription = "最小値：0",
                caution = "この指標だけで手の良し悪しが決まるわけではありません。",
            ),
            englishText = TheoryMetricText(
                displayName = "Opponent mobility",
                directionLabel = "↓ Lower is generally better",
                shortDescription = "The number of legal moves available to the opponent immediately afterward.",
                detailedDescription =
                    "Reducing the opponent's choices is generally considered useful. A value of 0 means the opponent passes.",
                rangeDescription = "Minimum: 0",
                caution = "This metric alone does not determine whether a move is good.",
            ),
            calculator = { context ->
                context.boardAfterMove.legalMoves(context.mover.opponent()).size
            },
        ),
        TheoryMetricDefinition(
            id = "frontier_discs",
            direction = TheoryMetricDirection.LOWER_IS_GENERALLY_BETTER,
            japaneseText = TheoryMetricText(
                displayName = "フロンティア石数",
                directionLabel = "↓ 小さいほど一般に良い",
                shortDescription = "着手後、自分の石のうち空きマスに接している石の枚数です。",
                detailedDescription =
                    "8近傍のどこかに空きマスがある、今打った側の石を数えます。" +
                        "1枚が複数の空きマスに接していても、その石は1枚として数えます。",
                rangeDescription = "最小値：0",
                caution = "この指標だけで手の良し悪しが決まるわけではありません。",
            ),
            englishText = TheoryMetricText(
                displayName = "Frontier discs",
                directionLabel = "↓ Lower is generally better",
                shortDescription = "The mover's discs that touch at least one empty square after the move.",
                detailedDescription =
                    "A mover disc is counted when any of its eight neighboring squares is empty. " +
                        "Each disc is counted once even when it touches several empty squares.",
                rangeDescription = "Minimum: 0",
                caution = "This metric alone does not determine whether a move is good.",
            ),
            calculator = { context ->
                context.boardAfterMove.positionsOf(context.mover).count { disc ->
                    disc.neighbors().any { context.boardAfterMove[it] == Disc.EMPTY }
                }
            },
        ),
        TheoryMetricDefinition(
            id = "potential_mobility",
            direction = TheoryMetricDirection.HIGHER_IS_GENERALLY_BETTER,
            japaneseText = TheoryMetricText(
                displayName = "潜在モビリティ",
                directionLabel = "↑ 大きいほど一般に良い",
                shortDescription = "着手後、相手石に接している空きマスの数です。",
                detailedDescription =
                    "今打った側から見て、相手石に8近傍で接する空きマスを数えます。" +
                        "同じ空きマスが複数の相手石に接していても1箇所として数えます。" +
                        "今すぐ合法手かどうかとは別の、将来の着手機会の広さを見る本アプリの定義です。",
                rangeDescription = "最小値：0",
                caution = "この指標だけで手の良し悪しが決まるわけではありません。",
            ),
            englishText = TheoryMetricText(
                displayName = "Potential mobility",
                directionLabel = "↑ Higher is generally better",
                shortDescription = "The empty squares adjacent to an opponent disc after the move.",
                detailedDescription =
                    "From the mover's perspective, each empty square touching an opponent disc in its eight-neighborhood is counted once, " +
                        "even if it touches several opponent discs. This is this app's definition of future move potential, independent of immediate legality.",
                rangeDescription = "Minimum: 0",
                caution = "This metric alone does not determine whether a move is good.",
            ),
            calculator = { context ->
                val opponent = context.mover.opponent()
                context.boardAfterMove.positionsOf(Disc.EMPTY).count { empty ->
                    empty.neighbors().any { context.boardAfterMove[it] == opponent }
                }
            },
        ),
    )

    val default: TheoryMetricDefinition get() = definitions.first()

    fun find(id: String): TheoryMetricDefinition? = definitions.firstOrNull { it.id == id }
}

object TheoryMetricEvaluator {
    fun evaluateAll(state: GameState): Map<Position, TheoryCandidateMetrics> =
        state.legalMoves.associateWith { move ->
            val played = state.play(move) as MoveOutcome.Played
            val context = TheoryCandidateContext(
                mover = state.currentPlayer,
                move = move,
                boardAfterMove = played.state.board,
                flipped = played.flipped,
            )
            TheoryCandidateMetrics(
                move = move,
                values = TheoryMetricRegistry.definitions.associate { metric ->
                    metric.id to metric.calculate(context)
                },
            )
        }
}

private fun Position.neighbors(): Sequence<Position> = sequence {
    for (rowDelta in -1..1) {
        for (columnDelta in -1..1) {
            if (rowDelta == 0 && columnDelta == 0) continue
            val neighborRow = row + rowDelta
            val neighborColumn = column + columnDelta
            if (neighborRow in 0 until Board.SIZE && neighborColumn in 0 until Board.SIZE) {
                yield(Position(neighborRow, neighborColumn))
            }
        }
    }
}
