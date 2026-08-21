package com.example.othello

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun BuildIdentityText() {
    val build = if (BuildConfig.CHANRIVA_GIT_SHA == "unknown") {
        "unknown"
    } else {
        BuildConfig.CHANRIVA_GIT_SHA + if (BuildConfig.CHANRIVA_GIT_DIRTY) "-dirty" else ""
    }
    Text(
        text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Build $build",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}
