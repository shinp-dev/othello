package com.example.othello

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun appString(@StringRes id: Int, vararg formatArgs: Any): String =
    stringResource(id, *formatArgs)
