package com.example.othello

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.othello.analysis.api.StandardAiLevel

internal data class StandardOpponentUi(
    val level: StandardAiLevel,
    @StringRes val nameRes: Int,
    @DrawableRes val winDrawableRes: Int,
    @DrawableRes val loseDrawableRes: Int,
)

internal data class StandardOpponentPackUi(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val supportingTextRes: Int,
    val opponents: List<StandardOpponentUi>,
    val previewLevels: List<StandardAiLevel>,
) {
    fun opponent(level: StandardAiLevel): StandardOpponentUi =
        opponents.single { it.level == level }
}

internal object StandardOpponentPacks {
    val animal = StandardOpponentPackUi(
        id = "animal",
        titleRes = R.string.standard_ai_animal_pack_name,
        supportingTextRes = R.string.standard_ai_animal_pack_supporting,
        opponents = listOf(
            StandardOpponentUi(
                StandardAiLevel.LV1,
                R.string.standard_ai_opponent_chick,
                R.drawable.standard_ai_lv01_chick_win,
                R.drawable.standard_ai_lv01_chick_lose,
            ),
            StandardOpponentUi(
                StandardAiLevel.LV2,
                R.string.standard_ai_opponent_rabbit,
                R.drawable.standard_ai_lv02_rabbit_win,
                R.drawable.standard_ai_lv02_rabbit_lose,
            ),
            StandardOpponentUi(
                StandardAiLevel.LV3,
                R.string.standard_ai_opponent_koala,
                R.drawable.standard_ai_lv03_koala_win,
                R.drawable.standard_ai_lv03_koala_lose,
            ),
            StandardOpponentUi(
                StandardAiLevel.LV4,
                R.string.standard_ai_opponent_elephant,
                R.drawable.standard_ai_lv04_elephant_win,
                R.drawable.standard_ai_lv04_elephant_lose,
            ),
            StandardOpponentUi(
                StandardAiLevel.LV5,
                R.string.standard_ai_opponent_wild_chick,
                R.drawable.standard_ai_lv05_wild_chick_win,
                R.drawable.standard_ai_lv05_wild_chick_lose,
            ),
            StandardOpponentUi(
                StandardAiLevel.LV6,
                R.string.standard_ai_opponent_wild_rabbit,
                R.drawable.standard_ai_lv06_wild_rabbit_win,
                R.drawable.standard_ai_lv06_wild_rabbit_lose,
            ),
            StandardOpponentUi(
                StandardAiLevel.LV7,
                R.string.standard_ai_opponent_wild_koala,
                R.drawable.standard_ai_lv07_wild_koala_win,
                R.drawable.standard_ai_lv07_wild_koala_lose,
            ),
            StandardOpponentUi(
                StandardAiLevel.LV8,
                R.string.standard_ai_opponent_wild_elephant,
                R.drawable.standard_ai_lv08_wild_elephant_win,
                R.drawable.standard_ai_lv08_wild_elephant_lose,
            ),
        ),
        previewLevels = listOf(
            StandardAiLevel.LV1,
            StandardAiLevel.LV2,
            StandardAiLevel.LV7,
            StandardAiLevel.LV8,
        ),
    )
}

internal fun StandardAiLevel.standardOpponent(): StandardOpponentUi =
    StandardOpponentPacks.animal.opponent(this)

internal fun StandardAiLevel.opponentWinDrawable(): Int = standardOpponent().winDrawableRes

internal fun StandardAiLevel.opponentLoseDrawable(): Int = standardOpponent().loseDrawableRes
