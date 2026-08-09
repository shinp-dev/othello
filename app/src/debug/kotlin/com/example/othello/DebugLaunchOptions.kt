package com.example.othello

import android.content.Intent

internal data class DebugLaunchOptions(
    val autoPlay: Boolean = false,
    val timeControlMillis: Long? = null,
    val showDiagnostics: Boolean = true,
)

internal fun debugLaunchOptions(intent: Intent): DebugLaunchOptions = DebugLaunchOptions(
    autoPlay = intent.getBooleanExtra("othello.e2e.autoplay", false),
    timeControlMillis = intent.extras?.get("othello.e2e.timeControlMillis").let { raw ->
        when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }?.takeIf { it in 1_000L..60_000L },
)
