package com.example.othello

import androidx.compose.runtime.Composable
import com.example.othello.game.Disc
import com.example.othello.match.LocalMatchStatusMessage
import com.example.othello.records.MatchResult

internal data class LocalMatchStatusTextSpec(
    val messageResource: Int,
    val argumentResource: Int? = null,
)

internal fun LocalMatchStatusMessage.textSpec(): LocalMatchStatusTextSpec = when (this) {
    is LocalMatchStatusMessage.Turn -> LocalMatchStatusTextSpec(
        messageResource = if (forcedPass) R.string.local_match_forced_pass else R.string.local_match_to_move,
        argumentResource = disc.resourceId(),
    )
    is LocalMatchStatusMessage.Resigned -> LocalMatchStatusTextSpec(
        messageResource = R.string.local_match_resigned,
        argumentResource = disc.resourceId(),
    )
    is LocalMatchStatusMessage.GameResult -> when (result) {
        MatchResult.BLACK_WIN -> LocalMatchStatusTextSpec(R.string.local_match_wins, R.string.black)
        MatchResult.WHITE_WIN -> LocalMatchStatusTextSpec(R.string.local_match_wins, R.string.white)
        MatchResult.DRAW -> LocalMatchStatusTextSpec(R.string.result_draw)
    }
    LocalMatchStatusMessage.AiCannotMove -> LocalMatchStatusTextSpec(R.string.local_match_ai_cannot_move)
}

@Composable
internal fun localMatchStatusText(message: LocalMatchStatusMessage): String {
    val spec = message.textSpec()
    return spec.argumentResource?.let { appString(spec.messageResource, appString(it)) }
        ?: appString(spec.messageResource)
}

private fun Disc.resourceId(): Int = when (this) {
    Disc.BLACK -> R.string.black
    Disc.WHITE -> R.string.white
    Disc.EMPTY -> error("Empty disc cannot have a local-match status label")
}
