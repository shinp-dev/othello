package com.example.othello

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.othello.auth.UserSession
import com.example.othello.analysis.edax.EdaxDataManager
import com.example.othello.analysis.edax.ProductionAnalysisEngine
import com.example.othello.designsystem.OthelloTheme
import com.example.othello.game.Disc
import com.example.othello.game.Position
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchViewState
import com.example.othello.match.OnlineMatchController
import com.example.othello.match.OnlineMatchViewState
import com.example.othello.matchmaking.MatchmakingStatus
import com.example.othello.records.GameRecord
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchOptions = debugLaunchOptions(intent)
        setContent { OthelloTheme { OthelloApp(launchOptions.autoPlay, launchOptions.timeControlMillis) } }
    }
}

private enum class AppDestination {
    HOME,
    PROFILE,
    RECORDS,
    REVIEW,
    CREDENTIAL,
    ACCOUNT_DELETION,
    SETTINGS,
    ANALYSIS_SETTINGS,
    ABOUT,
    OSS_LICENSES,
}

@Composable
private fun OthelloApp(
    debugAutoPlay: Boolean,
    debugTimeControlMillis: Long?,
    sessionOwner: OnlineSessionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val analysisDataManager = remember { EdaxDataManager(context) }
    val analysisEngine = remember { ProductionAnalysisEngine() }
    var localMatch by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(AppDestination.HOME) }
    var selectedRecord by remember { mutableStateOf<GameRecord?>(null) }
    val scope = rememberCoroutineScope()
    val componentResult = sessionOwner.componentResult
    val component = sessionOwner.component
    var session by remember { mutableStateOf<UserSession?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    val matchmaking = sessionOwner.matchmaking
    var matchmakingState by remember { mutableStateOf(matchmaking?.state) }
    var p2pCoordinator by remember(sessionOwner) { mutableStateOf(sessionOwner.coordinator) }
    DisposableEffect(matchmaking) {
        val closeable = matchmaking?.observe { matchmakingState = it }
        onDispose { closeable?.close() }
    }
    LaunchedEffect(component) {
        runCatching { component?.authGateway?.currentSession() }
            .onSuccess { session = it }
            .onFailure { loginError = it.message ?: "セッション確認に失敗しました" }
    }
    LaunchedEffect(session) {
        if (session == null) {
            destination = AppDestination.HOME
            selectedRecord = null
        }
    }
    LaunchedEffect(matchmakingState?.status, matchmakingState?.assignment) {
        if (matchmakingState?.status == MatchmakingStatus.WAITING) {
            while (isActive) {
                matchmaking?.heartbeat()
                delay(10_000)
            }
        }
    }
    LaunchedEffect(matchmakingState?.assignment) {
        val assignment = matchmakingState?.assignment ?: return@LaunchedEffect
        val supabase = component ?: return@LaunchedEffect
        val session = supabase.authGateway.currentSession() ?: return@LaunchedEffect
        p2pCoordinator = sessionOwner.startCoordinator(
            session.userId,
            assignment,
            debugAutoPlay,
            debugTimeControlMillis,
        )
    }
    fun leaveOnlineMatch() {
        val leaving = p2pCoordinator
        p2pCoordinator = null
        matchmaking?.reset()
        scope.launch {
            if (sessionOwner.coordinator === leaving) sessionOwner.leaveCoordinator()
            else leaving?.leave()
        }
    }
    BackHandler(enabled = localMatch || p2pCoordinator != null || destination != AppDestination.HOME) {
        when {
            localMatch -> localMatch = false
            p2pCoordinator != null -> leaveOnlineMatch()
            destination == AppDestination.REVIEW -> destination = AppDestination.RECORDS
            destination == AppDestination.ANALYSIS_SETTINGS || destination == AppDestination.ABOUT -> destination = AppDestination.SETTINGS
            destination == AppDestination.OSS_LICENSES -> destination = AppDestination.ABOUT
            else -> destination = AppDestination.HOME
        }
    }
    Surface(Modifier.fillMaxSize()) {
        when {
            localMatch -> MatchScreen(onBack = { localMatch = false })
            p2pCoordinator != null -> OnlineMatchScreen(
                coordinator = requireNotNull(p2pCoordinator),
                scope = scope,
                onBack = ::leaveOnlineMatch,
            )
            destination == AppDestination.PROFILE && session != null && component != null -> ProfileScreen(
                requireNotNull(session).userId,
                component.profileRepository,
                onBack = { destination = AppDestination.HOME },
            )
            destination == AppDestination.RECORDS && session != null && component != null -> RecordsScreen(
                requireNotNull(session).userId,
                component.gameRecordRepository,
                onBack = { destination = AppDestination.HOME },
                onReview = { selectedRecord = it; destination = AppDestination.REVIEW },
            )
            destination == AppDestination.REVIEW && selectedRecord != null -> ReviewScreen(
                record = requireNotNull(selectedRecord),
                dataManager = analysisDataManager,
                engine = analysisEngine,
                onBack = { destination = AppDestination.RECORDS },
            )
            destination == AppDestination.CREDENTIAL && session != null && component != null -> CredentialScreen(
                component.credentialRepository(requireNotNull(session).userId),
                onBack = { destination = AppDestination.HOME },
            )
            destination == AppDestination.ACCOUNT_DELETION && component != null -> AccountDeletionScreen(
                component.accountDeletionRepository, onBack = { destination = AppDestination.HOME },
            )
            destination == AppDestination.SETTINGS -> SettingsScreen(
                onBack = { destination = AppDestination.HOME },
                onAnalysis = { destination = AppDestination.ANALYSIS_SETTINGS },
                onAbout = { destination = AppDestination.ABOUT },
            )
            destination == AppDestination.ANALYSIS_SETTINGS -> AnalysisSettingsScreen(
                manager = analysisDataManager,
                onDataChanged = analysisEngine::clearCache,
                onBack = { destination = AppDestination.SETTINGS },
            )
            destination == AppDestination.ABOUT -> AboutScreen(
                onBack = { destination = AppDestination.SETTINGS },
                onLicenses = { destination = AppDestination.OSS_LICENSES },
            )
            destination == AppDestination.OSS_LICENSES -> OpenSourceLicensesScreen(
                onBack = { destination = AppDestination.ABOUT },
            )
            else -> HomeScreen(
                state = matchmakingState,
                configurationError = componentResult.exceptionOrNull()?.message,
                session = session,
                loginError = loginError,
                onLogin = { email, password ->
                    val auth = component?.authGateway ?: return@HomeScreen
                    scope.launch {
                        runCatching { auth.signIn(email, password) }
                            .onSuccess { session = it; loginError = null }
                            .onFailure { loginError = it.message ?: "ログインに失敗しました" }
                    }
                },
                onSignOut = {
                    scope.launch {
                        runCatching { component?.authGateway?.signOut() }
                            .onSuccess { session = null; loginError = null }
                            .onFailure { loginError = it.message ?: "ログアウトに失敗しました" }
                    }
                },
                onOnlineStart = { if (session != null) matchmaking?.let { scope.launch { it.enqueue() } } },
                onCancel = { matchmaking?.let { scope.launch { it.cancel() } } },
                onLocalStart = { localMatch = true },
                onProfile = { destination = AppDestination.PROFILE },
                onRecords = { destination = AppDestination.RECORDS },
                onCredential = { destination = AppDestination.CREDENTIAL },
                onAccountDeletion = { destination = AppDestination.ACCOUNT_DELETION },
                onSettings = { destination = AppDestination.SETTINGS },
            )
        }
    }
}

@Composable
private fun OnlineMatchScreen(
    coordinator: WebRtcMatchCoordinator,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
) {
    val controller = coordinator.controller
    var viewState by remember { mutableStateOf<OnlineMatchViewState>(controller.viewState) }
    DisposableEffect(controller) {
        val closeable = controller.observe { viewState = it }
        onDispose { closeable.close() }
    }
    LaunchedEffect(controller, viewState.matchState.status) {
        while (isActive && viewState.matchState.status == com.example.othello.match.MatchStatus.PLAYING) {
            controller.refreshClock()
            delay(250)
        }
    }
    val diagnostics = coordinator.diagnostics()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("戻る") }
            Spacer(Modifier.weight(1f))
            Text("オンライン対局", style = MaterialTheme.typography.titleLarge)
        }
        Text("matchId: ${diagnostics.matchId}", style = MaterialTheme.typography.labelSmall)
        Text("You: ${viewState.localDisc} / opponent: ${diagnostics.opponentId?.take(8) ?: "unknown"}", style = MaterialTheme.typography.labelSmall)
        ScoreHeader(viewState.game, viewState.matchState.status.name)
        Text(
            "黒 ${formatClock(viewState.blackRemainingMillis)} / 白 ${formatClock(viewState.whiteRemainingMillis)}",
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        OnlineOthelloBoard(viewState, controller, scope)
        Text(viewState.message, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(
            "ICE ${diagnostics.iceState} / Peer ${diagnostics.peerConnectionState} / DC ${diagnostics.dataChannelState} / ack ${diagnostics.localStartAcked}/${diagnostics.bothStartAcked}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text("ply ${viewState.game.ply} / hash ${viewState.game.stateHash()}", style = MaterialTheme.typography.labelSmall)
        viewState.finishResult?.takeIf { it.serverStatus == "CONFIRMED" }?.let { result ->
            val before = result.ratingBefore
            val after = result.ratingAfter
            val delta = result.ratingDelta
            val current = result.currentRating
            val peak = result.peakRating
            if (before != null && after != null && delta != null) {
                Text("Rating $before → $after (${delta.withSign()})")
            }
            if (current != null && peak != null) {
                Text("Current $current / Peak $peak")
            }
        }
        OutlinedButton(
            onClick = { scope.launch { controller.resign() } },
            enabled = viewState.matchState.status == com.example.othello.match.MatchStatus.PLAYING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("投了") }
        if (viewState.error != null) Text(viewState.error!!, color = MaterialTheme.colorScheme.error)
    }
}

private fun Int.withSign(): String = if (this > 0) "+$this" else toString()

private fun formatClock(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 999) / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun OnlineOthelloBoard(
    viewState: OnlineMatchViewState,
    controller: OnlineMatchController,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val canPlay = viewState.matchState.status == com.example.othello.match.MatchStatus.PLAYING &&
        viewState.game.currentPlayer == viewState.localDisc
    Column(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF0D6B47)).padding(3.dp)) {
        repeat(8) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(8) { column ->
                    val position = Position(row, column)
                    val disc = viewState.game.board[position]
                    val legal = canPlay && position in viewState.game.legalMoves
                    Box(
                        modifier = Modifier.weight(1f).border(0.5.dp, Color(0xFF72AA8D)).clickable(enabled = legal) {
                            scope.launch { controller.play(position) }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (disc != Disc.EMPTY) Box(Modifier.size(34.dp).background(if (disc == Disc.BLACK) Color(0xFF111514) else Color(0xFFF5F4ED), CircleShape))
                        else if (legal) Box(Modifier.size(10.dp).background(Color(0xFFB7E0C9), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: com.example.othello.matchmaking.MatchmakingViewState?,
    configurationError: String?,
    session: UserSession?,
    loginError: String?,
    onLogin: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onOnlineStart: () -> Unit,
    onCancel: () -> Unit,
    onLocalStart: () -> Unit,
    onProfile: () -> Unit,
    onRecords: () -> Unit,
    onCredential: () -> Unit,
    onAccountDeletion: () -> Unit,
    onSettings: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OTHELLO", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text("オンライン対局 MVP", style = MaterialTheme.typography.titleMedium)
        if (session == null) {
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Auth email") }, singleLine = true)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Button(onClick = { onLogin(email, password) }, enabled = email.isNotBlank() && password.isNotBlank()) { Text("ログイン") }
            if (loginError != null) Text(loginError, color = MaterialTheme.colorScheme.error)
        } else {
            Text("ログイン中: ${session.displayName}")
            OutlinedButton(onClick = onSignOut) { Text("ログアウト") }
        }
        Button(
            onClick = onOnlineStart,
            enabled = session != null && state?.status !in setOf(MatchmakingStatus.WAITING, MatchmakingStatus.SIGNALING),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("対局する") }
        OutlinedButton(onClick = onLocalStart, modifier = Modifier.fillMaxWidth()) { Text("ローカル対局") }
        if (session != null) {
            OutlinedButton(onClick = onProfile, modifier = Modifier.fillMaxWidth()) { Text("プロフィール") }
            OutlinedButton(onClick = onRecords, modifier = Modifier.fillMaxWidth()) { Text("棋譜・レビュー") }
            OutlinedButton(onClick = onCredential, modifier = Modifier.fillMaxWidth()) { Text("連盟段級位") }
            OutlinedButton(onClick = onAccountDeletion, modifier = Modifier.fillMaxWidth()) { Text("アカウントを削除") }
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("設定") }
        when (state?.status) {
            MatchmakingStatus.WAITING -> {
                Text("対戦相手を待っています")
                OutlinedButton(onClick = onCancel) { Text("キャンセル") }
            }
            MatchmakingStatus.SIGNALING -> Text("対戦相手が見つかりました。P2P接続を開始します")
            MatchmakingStatus.FAILED -> Text(state.error ?: "マッチングに失敗しました", color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        if (configurationError != null) Text(configurationError, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun MatchScreen(onBack: () -> Unit) {
    val controller = remember { LocalMatchController() }
    var viewState by remember { mutableStateOf<LocalMatchViewState>(controller.viewState) }
    DisposableEffect(controller) {
        val closeable = controller.observe { viewState = it }
        onDispose { closeable.close() }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("戻る") }
            Spacer(Modifier.weight(1f))
            Text("ローカル対局", style = MaterialTheme.typography.titleLarge)
        }
        ScoreHeader(viewState)
        OthelloBoard(viewState, controller)
        Text(viewState.message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        Button(onClick = controller::reset, modifier = Modifier.fillMaxWidth()) { Text("新しい対局") }
    }
}

@Composable
private fun ScoreHeader(viewState: LocalMatchViewState) = ScoreHeader(viewState.game, "ローカル")

@Composable
private fun ScoreHeader(game: com.example.othello.game.GameState, status: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("黒 ${game.board.count(Disc.BLACK)}")
            Text("白 ${game.board.count(Disc.WHITE)}")
            Text("手数 ${game.ply}")
            Text(status)
        }
    }
}

@Composable
private fun OthelloBoard(viewState: LocalMatchViewState, controller: LocalMatchController) {
    Column(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF0D6B47)).padding(3.dp)) {
        repeat(8) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(8) { column ->
                    val position = Position(row, column)
                    val disc = viewState.game.board[position]
                    val legal = position in viewState.game.legalMoves
                    Box(
                        modifier = Modifier.weight(1f).border(0.5.dp, Color(0xFF72AA8D)).clickable(enabled = legal) { controller.play(position) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (disc != Disc.EMPTY) Box(Modifier.size(34.dp).background(if (disc == Disc.BLACK) Color(0xFF111514) else Color(0xFFF5F4ED), CircleShape))
                        else if (legal) Box(Modifier.size(10.dp).background(Color(0xFFB7E0C9), CircleShape))
                    }
                }
            }
        }
    }
}
