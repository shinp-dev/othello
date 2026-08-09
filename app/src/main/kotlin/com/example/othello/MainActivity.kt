package com.example.othello

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.othello.auth.UserSession
import com.example.othello.data.supabase.SupabaseModule
import com.example.othello.designsystem.OthelloTheme
import com.example.othello.game.Disc
import com.example.othello.game.Position
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchViewState
import com.example.othello.match.OnlineMatchController
import com.example.othello.match.OnlineMatchViewState
import com.example.othello.matchmaking.MatchmakingController
import com.example.othello.matchmaking.MatchmakingStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OthelloTheme { OthelloApp() } }
    }
}

@Composable
private fun OthelloApp() {
    val context = LocalContext.current
    var localMatch by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val componentResult = remember { SupabaseModule.create(scope = scope) }
    val component = componentResult.getOrNull()
    val debugAutoPlay = BuildConfig.DEBUG && (context as? android.app.Activity)
        ?.intent?.getBooleanExtra("othello.e2e.autoplay", false) == true
    var session by remember { mutableStateOf<UserSession?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(component) { onDispose { component?.close() } }
    val matchmaking = remember(component) { component?.let { MatchmakingController(it.matchmakingRepository) } }
    var matchmakingState by remember { mutableStateOf(matchmaking?.state) }
    var p2pCoordinator by remember { mutableStateOf<WebRtcMatchCoordinator?>(null) }
    DisposableEffect(matchmaking) {
        val closeable = matchmaking?.observe { matchmakingState = it }
        onDispose { closeable?.close() }
    }
    LaunchedEffect(component) { session = component?.authGateway?.currentSession() }
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
        p2pCoordinator?.close()
        p2pCoordinator = WebRtcMatchCoordinator(
            context, session.userId, assignment,
            supabase.signalingDataSource,
            supabase.onlineMatchRepository, this, debugAutoPlay,
        ).also { it.start() }
    }
    DisposableEffect(Unit) { onDispose { p2pCoordinator?.close() } }
    Surface(Modifier.fillMaxSize()) {
        when {
            localMatch -> MatchScreen(onBack = { localMatch = false })
            p2pCoordinator != null -> OnlineMatchScreen(
                coordinator = requireNotNull(p2pCoordinator),
                scope = scope,
                onBack = {
                    p2pCoordinator?.close()
                    p2pCoordinator = null
                    matchmaking?.let { scope.launch { it.cancel() } }
                },
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
                onSignOut = { scope.launch { component?.authGateway?.signOut(); session = null } },
                onOnlineStart = { if (session != null) matchmaking?.let { scope.launch { it.enqueue() } } },
                onCancel = { matchmaking?.let { scope.launch { it.cancel() } } },
                onLocalStart = { localMatch = true },
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
    val diagnostics = coordinator.diagnostics()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("戻る") }
            Spacer(Modifier.weight(1f))
            Text("オンライン対局", style = MaterialTheme.typography.titleLarge)
        }
        Text("matchId: ${diagnostics.matchId}", style = MaterialTheme.typography.labelSmall)
        ScoreHeader(viewState.game, viewState.matchState.status.name)
        OnlineOthelloBoard(viewState, controller, scope)
        Text(viewState.message, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(
            "ICE ${diagnostics.iceState} / Peer ${diagnostics.peerConnectionState} / DC ${diagnostics.dataChannelState} / ack ${diagnostics.localStartAcked}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text("ply ${viewState.game.ply} / hash ${viewState.game.stateHash()}", style = MaterialTheme.typography.labelSmall)
        OutlinedButton(
            onClick = { scope.launch { controller.resign() } },
            enabled = viewState.matchState.status == com.example.othello.match.MatchStatus.PLAYING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("投了") }
        if (viewState.error != null) Text(viewState.error!!, color = MaterialTheme.colorScheme.error)
    }
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
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
        Button(onClick = onOnlineStart, enabled = session != null, modifier = Modifier.fillMaxWidth()) { Text("対局する") }
        OutlinedButton(onClick = onLocalStart, modifier = Modifier.fillMaxWidth()) { Text("ローカル対局") }
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
