package com.example.othello

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal fun opponentText(text: OpponentText): String = text.resolve(LocalConfiguration.current.locales[0].language)
