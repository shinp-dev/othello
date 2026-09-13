package com.example.othello

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import kotlinx.coroutines.launch

/** Gates every Standard destination, including destinations restored after process death. */
@Composable
internal fun StandardBootstrapRoute(
    userId: String,
    onBack: () -> Unit,
    content: @Composable (StandardContentSnapshot, StandardAiPreparationState, () -> Unit) -> Unit,
) {
    val application = LocalContext.current.applicationContext as OthelloApplication
    // Retained across feature navigation, discarded when leaving Standard. Not saveable:
    // restored screens must acquire a fresh snapshot before they can consume local assets.
    val bootstrap = remember(application, userId) {
        StandardBootstrapController(
            prepareContent = { application.standardContent.prepareForEntry() },
            aiPreparation = application.standardAiPreparation,
        )
    }
    val state by bootstrap.state.collectAsState()
    val aiState by bootstrap.aiState.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(bootstrap) { bootstrap.prepare() }

    when (val current = state) {
        is StandardBootstrapState.Ready -> content(current.content, aiState) {
            scope.launch { bootstrap.retryAi() }
        }
        else -> {
            BackHandler(onBack = onBack)
            Surface(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(ChanrivaSpacing.page),
                    verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
                ) {
                    ChanrivaScreenHeader(
                        title = appString(R.string.standard_mode),
                        onBack = onBack,
                        backLabel = appString(R.string.back),
                    )
                    if (current is StandardBootstrapState.Failed) {
                        Text(
                            appString(R.string.standard_bootstrap_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { scope.launch { bootstrap.prepare() } }) {
                            Text(appString(R.string.retry))
                        }
                    } else {
                        CircularProgressIndicator()
                        Text(appString(R.string.standard_bootstrap_preparing))
                    }
                }
            }
        }
    }
}
