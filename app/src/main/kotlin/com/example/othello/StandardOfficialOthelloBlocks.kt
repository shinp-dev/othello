package com.example.othello

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaSpacing

private val OfficialBlockGold = Color(0xFFC59A54)
private val OfficialBlockSurface = Color(0xFF10211E)

internal data class StandardOfficialOthelloBlock(
    @StringRes val nameRes: Int,
    val url: String,
)

internal val STANDARD_OFFICIAL_OTHELLO_BLOCKS = listOf(
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_hokkaido, "https://www.othello.gr.jp/hokkaido"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_tohoku, "https://www.othello.gr.jp/tohoku"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_kita_kantou, "https://www.othello.gr.jp/kita_kantou"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_higashi_kantou, "https://www.othello.gr.jp/higashi_kantou"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_ibaraki, "https://www.othello.gr.jp/ibaraki"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_tokyo, "https://www.othello.gr.jp/tokyo"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_kanagawa, "https://www.othello.gr.jp/kanagawa"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_niigata, "https://www.othello.gr.jp/niigata"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_tokai, "https://www.othello.gr.jp/tokai"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_chubu, "https://www.othello.gr.jp/chubu"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_hokuriku, "https://www.othello.gr.jp/hokuriku"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_kinki, "https://www.othello.gr.jp/hokuriku_kinki"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_hyogo, "https://www.othello.gr.jp/hyogo"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_chushikoku, "https://www.othello.gr.jp/chushikoku"),
    StandardOfficialOthelloBlock(R.string.standard_real_event_block_kyushu, "https://www.othello.gr.jp/kyushu"),
)

@Composable
internal fun StandardOfficialOthelloBlockLinks() {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
    ) {
        RealEventSectionHeader(
            title = appString(R.string.standard_real_event_official_title),
            supporting = appString(R.string.standard_real_event_official_supporting),
        )

        STANDARD_OFFICIAL_OTHELLO_BLOCKS.chunked(3).forEach { blocks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ChanrivaSpacing.control),
            ) {
                blocks.forEach { block ->
                    OutlinedButton(
                        onClick = { runCatching { uriHandler.openUri(block.url) } },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OfficialBlockGold.copy(alpha = 0.72f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = OfficialBlockSurface,
                            contentColor = ChanrivaColors.textPrimary,
                        ),
                    ) {
                        Text(
                            text = appString(block.nameRes),
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(3 - blocks.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
