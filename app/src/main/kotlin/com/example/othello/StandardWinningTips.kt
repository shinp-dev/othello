package com.example.othello

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position

internal enum class StandardWinningTipTier(@StringRes val titleRes: Int) {
    BASIC(R.string.standard_winning_tips_tier_basic),
    STEP_UP(R.string.standard_winning_tips_tier_step_up),
    EXPERT(R.string.standard_winning_tips_tier_expert),
}

internal data class StandardTipBoardExample(
    val moves: List<String>,
    val focusSquares: Set<String> = emptySet(),
    val warningSquares: Set<String> = emptySet(),
)

internal data class StandardWinningTip(
    val id: String,
    val tier: StandardWinningTipTier,
    @StringRes val titleRes: Int,
    @StringRes val leadRes: Int,
    @StringRes val bodyRes: Int,
    @StringRes val takeawayRes: Int,
    @StringRes val boardCaptionRes: Int,
    val boardExample: StandardTipBoardExample,
)

internal val standardWinningTips: List<StandardWinningTip> = listOf(
    StandardWinningTip(
        id = "take-less-early",
        tier = StandardWinningTipTier.BASIC,
        titleRes = R.string.standard_winning_tip_take_less_title,
        leadRes = R.string.standard_winning_tip_take_less_lead,
        bodyRes = R.string.standard_winning_tip_take_less_body,
        takeawayRes = R.string.standard_winning_tip_take_less_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_take_less_caption,
        boardExample = StandardTipBoardExample(
            moves = "d3 c3 b3 e3 f3 c5 f6 g2 b5".split(" "),
            focusSquares = setOf("c2"),
            warningSquares = setOf("a3"),
        ),
    ),
    StandardWinningTip(
        id = "limit-mobility",
        tier = StandardWinningTipTier.BASIC,
        titleRes = R.string.standard_winning_tip_mobility_title,
        leadRes = R.string.standard_winning_tip_mobility_lead,
        bodyRes = R.string.standard_winning_tip_mobility_body,
        takeawayRes = R.string.standard_winning_tip_mobility_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_mobility_caption,
        boardExample = StandardTipBoardExample(
            moves = "d3 c3 b3 e3 f3 c5 f6 g2".split(" "),
            focusSquares = setOf("f4"),
            warningSquares = setOf("d6"),
        ),
    ),
    StandardWinningTip(
        id = "corners",
        tier = StandardWinningTipTier.BASIC,
        titleRes = R.string.standard_winning_tip_corner_title,
        leadRes = R.string.standard_winning_tip_corner_lead,
        bodyRes = R.string.standard_winning_tip_corner_body,
        takeawayRes = R.string.standard_winning_tip_corner_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_corner_caption,
        boardExample = StandardTipBoardExample(
            moves = "c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6".split(" "),
            focusSquares = setOf("a8"),
        ),
    ),
    StandardWinningTip(
        id = "avoid-x-square",
        tier = StandardWinningTipTier.BASIC,
        titleRes = R.string.standard_winning_tip_x_square_title,
        leadRes = R.string.standard_winning_tip_x_square_lead,
        bodyRes = R.string.standard_winning_tip_x_square_body,
        takeawayRes = R.string.standard_winning_tip_x_square_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_x_square_caption,
        boardExample = StandardTipBoardExample(
            moves = "c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 e8 d1 a8 c7 a4 b8 c3".split(" "),
            focusSquares = setOf("a1"),
            warningSquares = setOf("b2"),
        ),
    ),
    StandardWinningTip(
        id = "do-not-rush-edge",
        tier = StandardWinningTipTier.STEP_UP,
        titleRes = R.string.standard_winning_tip_edge_title,
        leadRes = R.string.standard_winning_tip_edge_lead,
        bodyRes = R.string.standard_winning_tip_edge_body,
        takeawayRes = R.string.standard_winning_tip_edge_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_edge_caption,
        boardExample = StandardTipBoardExample(
            moves = "c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2".split(" "),
            focusSquares = setOf("e2"),
            warningSquares = setOf("f8"),
        ),
    ),
    StandardWinningTip(
        id = "count-at-end",
        tier = StandardWinningTipTier.STEP_UP,
        titleRes = R.string.standard_winning_tip_endgame_title,
        leadRes = R.string.standard_winning_tip_endgame_lead,
        bodyRes = R.string.standard_winning_tip_endgame_body,
        takeawayRes = R.string.standard_winning_tip_endgame_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_endgame_caption,
        boardExample = StandardTipBoardExample(
            moves = "c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 e8 d1 a8 c7 a4 b8 c3 f5 e1 b3 c1 b2 a2 d8 f8 a1 f6 g7 g4 b4 a3 g8 a6 g5 g6 a5 h7 h3 g3 g2 h6 f2 g1".split(" "),
            focusSquares = setOf("e2"),
        ),
    ),
    StandardWinningTip(
        id = "stable-discs",
        tier = StandardWinningTipTier.STEP_UP,
        titleRes = R.string.standard_winning_tip_stable_title,
        leadRes = R.string.standard_winning_tip_stable_lead,
        bodyRes = R.string.standard_winning_tip_stable_body,
        takeawayRes = R.string.standard_winning_tip_stable_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_stable_caption,
        boardExample = StandardTipBoardExample(
            moves = "c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 a8".split(" "),
            focusSquares = setOf("a8"),
        ),
    ),
    StandardWinningTip(
        id = "parity",
        tier = StandardWinningTipTier.EXPERT,
        titleRes = R.string.standard_winning_tip_parity_title,
        leadRes = R.string.standard_winning_tip_parity_lead,
        bodyRes = R.string.standard_winning_tip_parity_body,
        takeawayRes = R.string.standard_winning_tip_parity_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_parity_caption,
        boardExample = StandardTipBoardExample(
            moves = "c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 e8 d1 a8 c7 a4 b8 c3 f5 e1 b3 c1 b2 a2 d8 f8 a1 f6 g7 g4 b4 a3 g8 a6 g5 g6 a5 h7 h3 g3 g2 h6 f2 g1".split(" "),
            focusSquares = setOf("h1", "h2"),
        ),
    ),
    StandardWinningTip(
        id = "count-tempo",
        tier = StandardWinningTipTier.EXPERT,
        titleRes = R.string.standard_winning_tip_tempo_title,
        leadRes = R.string.standard_winning_tip_tempo_lead,
        bodyRes = R.string.standard_winning_tip_tempo_body,
        takeawayRes = R.string.standard_winning_tip_tempo_takeaway,
        boardCaptionRes = R.string.standard_winning_tip_tempo_caption,
        boardExample = StandardTipBoardExample(
            moves = "c4 c5 b6 d3 c2 a7 d6 e7 d7 e3 b5 d2 f7 b1 b7 c6 e6 e8 d1 a8 c7 a4 b8 c3 f5 e1 b3 c1 b2 a2 d8 f8 a1 f6 g7 g4 b4 a3 g8 a6 g5 g6 a5 h7 h3 g3 g2 h6 f2 g1".split(" "),
            focusSquares = setOf("f1", "e2", "f3"),
        ),
    ),
)

internal fun standardTipPosition(notation: String): Position {
    require(notation.length == 2) { "Tip coordinate must use a1-h8 notation: $notation" }
    val column = notation[0].lowercaseChar() - 'a'
    val row = notation[1].digitToIntOrNull()?.minus(1) ?: -1
    require(row in 0..7 && column in 0..7) { "Tip coordinate is outside a1-h8: $notation" }
    return Position(row, column)
}

internal fun standardTipStateFor(moves: List<String>): GameState {
    var state = GameState()
    moves.forEach { notation ->
        if (state.legalMoves.isEmpty()) {
            state = when (val pass = state.pass()) {
                is MoveOutcome.Passed -> pass.state
                else -> error("Tip fixture cannot pass before $notation: $pass")
            }
        }
        state = when (val outcome = state.play(standardTipPosition(notation))) {
            is MoveOutcome.Played -> outcome.state
            else -> error("Illegal tip fixture move $notation: $outcome")
        }
    }
    return state
}

@Composable
internal fun StandardWinningTipsRoute(onBack: () -> Unit) {
    var selectedTipId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedIndex = standardWinningTips.indexOfFirst { it.id == selectedTipId }

    BackHandler(enabled = selectedIndex >= 0) {
        selectedTipId = null
    }

    if (selectedIndex < 0) {
        StandardWinningTipsIndexScreen(
            onBack = onBack,
            onOpenTip = { selectedTipId = it.id },
        )
    } else {
        StandardWinningTipDetailScreen(
            tipIndex = selectedIndex,
            onBack = { selectedTipId = null },
            onPrevious = {
                selectedTipId = standardWinningTips.getOrNull(selectedIndex - 1)?.id
            },
            onNext = {
                selectedTipId = standardWinningTips.getOrNull(selectedIndex + 1)?.id
            },
        )
    }
}

@Composable
private fun StandardWinningTipsIndexScreen(
    onBack: () -> Unit,
    onOpenTip: (StandardWinningTip) -> Unit,
) {
    StandardTipsSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_winning_tips_title),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            text = appString(R.string.standard_winning_tips_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StandardWinningTipTier.entries.forEach { tier ->
            Text(
                text = appString(tier.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (tier == StandardWinningTipTier.EXPERT) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = appString(R.string.standard_winning_tips_expert_supporting),
                        modifier = Modifier.padding(ChanrivaSpacing.control),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            standardWinningTips.filter { it.tier == tier }.forEach { tip ->
                Card(
                    onClick = { onOpenTip(tip) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ChanrivaColors.surfaceElevated),
                ) {
                    Column(
                        modifier = Modifier.padding(ChanrivaSpacing.section),
                        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
                    ) {
                        if (tier == StandardWinningTipTier.EXPERT) {
                            StandardTipTierBadge(tier)
                        }
                        Text(
                            text = appString(tip.titleRes),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = appString(tip.leadRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StandardWinningTipDetailScreen(
    tipIndex: Int,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val tip = standardWinningTips[tipIndex]
    StandardTipsSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_winning_tips_title),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            text = appString(R.string.standard_winning_tips_progress, tipIndex + 1, standardWinningTips.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StandardTipTierBadge(tip.tier)
        Text(
            text = appString(tip.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = appString(tip.leadRes),
            style = MaterialTheme.typography.titleMedium,
        )
        StandardTipBoard(tip.boardExample)
        Text(
            text = appString(tip.boardCaptionRes),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = appString(tip.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(ChanrivaSpacing.section),
                verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
            ) {
                Text(
                    text = appString(R.string.standard_winning_tips_takeaway_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = appString(tip.takeawayRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
        ) {
            OutlinedButton(
                onClick = onPrevious,
                enabled = tipIndex > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(appString(R.string.standard_winning_tips_previous))
            }
            Button(
                onClick = onNext,
                enabled = tipIndex < standardWinningTips.lastIndex,
                modifier = Modifier.weight(1f),
            ) {
                Text(appString(R.string.standard_winning_tips_next))
            }
        }
    }
}

@Composable
private fun StandardTipTierBadge(tier: StandardWinningTipTier) {
    val containerColor = when (tier) {
        StandardWinningTipTier.BASIC -> MaterialTheme.colorScheme.surfaceVariant
        StandardWinningTipTier.STEP_UP -> MaterialTheme.colorScheme.tertiaryContainer
        StandardWinningTipTier.EXPERT -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when (tier) {
        StandardWinningTipTier.BASIC -> MaterialTheme.colorScheme.onSurfaceVariant
        StandardWinningTipTier.STEP_UP -> MaterialTheme.colorScheme.onTertiaryContainer
        StandardWinningTipTier.EXPERT -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = appString(tier.titleRes),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
private fun StandardTipBoard(example: StandardTipBoardExample) {
    val state = remember(example.moves) { standardTipStateFor(example.moves) }
    val focus = remember(example.focusSquares) { example.focusSquares.map(::standardTipPosition).toSet() }
    val warnings = remember(example.warningSquares) { example.warningSquares.map(::standardTipPosition).toSet() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 240.dp)
            .aspectRatio(1f)
            .background(ChanrivaColors.board)
            .padding(3.dp),
    ) {
        repeat(8) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(8) { column ->
                    val position = Position(row, column)
                    val disc = state.board[position]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, ChanrivaColors.boardGrid),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (position in focus) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                            )
                        }
                        if (disc != Disc.EMPTY) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(4.dp)
                                    .background(
                                        if (disc == Disc.BLACK) ChanrivaColors.blackDisc else ChanrivaColors.whiteDisc,
                                        CircleShape,
                                    )
                                    .border(1.dp, ChanrivaColors.discOutline, CircleShape),
                            )
                        }
                        if (position in warnings) {
                            Text(
                                text = "×",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandardTipsSurface(content: @Composable Column.() -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
            content = content,
        )
    }
}
