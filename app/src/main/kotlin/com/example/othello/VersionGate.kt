package com.example.othello

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface VersionGateState {
    data object Checking : VersionGateState
    data object Supported : VersionGateState
    data class Unsupported(
        val currentVersionCode: Int,
        val minimumSupportedVersionCode: Int,
    ) : VersionGateState
    data object Error : VersionGateState
}

internal class VersionGateController(
    private val currentVersionCode: Int,
    private val fetcher: AppConfigFetcher,
) {
    private val mutableState = MutableStateFlow<VersionGateState>(VersionGateState.Checking)
    val state: StateFlow<VersionGateState> = mutableState.asStateFlow()

    suspend fun check() {
        mutableState.value = VersionGateState.Checking
        try {
            val minimum = fetcher.fetchMinimumSupportedVersionCode()
            mutableState.value = if (currentVersionCode >= minimum) {
                VersionGateState.Supported
            } else {
                VersionGateState.Unsupported(currentVersionCode, minimum)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = VersionGateState.Error
        }
    }
}

internal class VersionGateViewModel : ViewModel() {
    private val controller = VersionGateController(
        currentVersionCode = BuildConfig.VERSION_CODE,
        fetcher = CloudflareAppConfigFetcher(UrlConnectionAppConfigHttpTransport()),
    )
    val state: StateFlow<VersionGateState> = controller.state
    private var checkJob: Job? = null

    init {
        retry()
    }

    fun retry() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch { controller.check() }
    }
}

@Composable
internal fun VersionGate(
    owner: VersionGateViewModel,
    supportedContent: @Composable () -> Unit,
) {
    val state by owner.state.collectAsStateWithLifecycle()
    when (state) {
        VersionGateState.Checking -> VersionGateMessageScreen(showProgress = true)
        VersionGateState.Supported -> supportedContent()
        is VersionGateState.Unsupported -> VersionGateUnsupportedScreen()
        VersionGateState.Error -> VersionGateMessageScreen(
            message = appString(R.string.version_server_error),
            onRetry = owner::retry,
        )
    }
}

@Composable
private fun VersionGateUnsupportedScreen() {
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        setImageResource(R.mipmap.ic_launcher)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = context.getString(R.string.app_icon_description)
                    }
                },
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = appString(R.string.update_required),
                style = MaterialTheme.typography.headlineSmall,
                color = ChanrivaColors.accent,
                textAlign = TextAlign.Center,
            )
            Text(
                text = appString(R.string.unsupported_version),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun VersionGateMessageScreen(
    message: String? = null,
    showProgress: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showProgress) CircularProgressIndicator()
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            onRetry?.let {
                Button(
                    onClick = it,
                    modifier = Modifier.padding(top = ChanrivaSpacing.section),
                ) {
                    Text(appString(R.string.retry))
                }
            }
        }
    }
}
