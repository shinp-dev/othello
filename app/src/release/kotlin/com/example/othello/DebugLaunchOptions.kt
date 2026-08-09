package com.example.othello

import android.content.Intent

internal data class DebugLaunchOptions(
    val autoPlay: Boolean = false,
    val timeControlMillis: Long? = null,
)

@Suppress("UNUSED_PARAMETER")
internal fun debugLaunchOptions(intent: Intent): DebugLaunchOptions = DebugLaunchOptions()
