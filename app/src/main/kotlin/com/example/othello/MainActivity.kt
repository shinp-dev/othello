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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.othello.auth.UserSession
import com.example.othello.analysis.edax.EdaxDataManager
import com.example.othello.analysis.edax.EdaxSettingsStore
import com.example.othello.analysis.edax.ProductionAiMoveEngine
import com.example.othello.analysis.edax.ProductionAnalysisEngine
import com.example.othello.designsystem.OthelloTheme
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaDangerButton
import com.example.othello.designsystem.ChanrivaNavigationRow
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.game.Disc
import com.example.othello.game.Position
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchMode
import com.example.othello.match.LocalMatchViewState
import com.example.othello.match.OnlineMatchController
import com.example.othello.match.OnlineMatchViewState
import com.example.othello.match.TimeWarningTracker
import com.example.othello.matchmaking.MatchmakingStatus
import com.example.othello.profile.CurrentRatingRepository
import com.example.othello.records.GameRecord
import com.example.othello.review.ReviewInput
import com.example.othello.review.ReviewSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchOptions = debugLaunchOptions(intent)
        setContent {
            OthelloTheme {
                OthelloApp(launchOptions.autoPlay, launchOptions.timeControlMillis, launchOptions.showDiagnostics)
            }
        }
    }
}

@Composable
private fun OthelloApp(
    debugAutoPlay: Boolean,
    debugTimeControlMillis: Long?,
    showDiagnostics: Boolean,
    sessionOwner: OnlineSessionViewModel = viewModel(),
) {
    AuthGate(sessionOwner) { session ->
        AuthenticatedApp(
            debugAutoPlay = debugAutoPlay,
            debugTimeControlMillis = debugTimeControlMillis,
            showDiagnostics = showDiagnostics,
            sessionOwner = sessionOwner,
            session = session,
        )
    }
}

@Composable
private fun AuthenticatedApp(
    debugAutoPlay: Boolean,
    debugTimeControlMillis: Long?,
    showDiagnostics: Boolean,
    sessionOwner: OnlineSessionViewModel,
    session: UserSession,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val application = context.applicationContext as OthelloApplication
    val audioSettings = remember { AudioSettingsStore(context) }
    val analysisDataManager = remember { EdaxDataManager(context) }
    val edaxSettings = remember { EdaxSettingsStore(context) }
    val analysisEngine = remember { ProductionAnalysisEngine() }
    val aiMoveEngine = remember { ProductionAiMoveEngine() }
    val localRecordStore = application.localGameRecordStore
    val localRecordPersistence = application.localGameRecordPersistence.coordinator
    val localRecordSaveStates by localRecordPersistence.saveStates.collectAsState()
    var localMatch by remember { mutableStateOf(false) }
    var localMatchMode by remember { mutableStateOf(LocalMatchMode.HUMAN) }
    var localHumanDisc by remember { mutableStateOf(Disc.BLACK) }
    var destination by remember { mutableStateOf(AppDestination.PLAY) }
    var selectedReviewInput by remember { mutableStateOf<ReviewInput?>(null) }
    val selectedReviewSession = remember(selectedReviewInput?.id) {
        selectedReviewInput?.let(::ReviewSession)
    }
    var commonSettingsBackDestination by remember { mutableStateOf(AppDestination.SETTINGS) }
    var reviewBackDestination by remember { mutableStateOf(AppDestination.STUDY) }
    var researchSettingsBackDestination by remember { mutableStateOf(AppDestination.SETTINGS) }
    val scope = rememberCoroutineScope()
    val component = requireNotNull(sessionOwner.component) { "Authenticated app requires Supabase component" }
    val matchmaking = requireNotNull(sessionOwner.matchmaking) { "Authenticated app requires matchmaking controller" }
    var matchmakingState by remember { mutableStateOf(matchmaking.state) }
    var p2pCoordinator by remember(sessionOwner) { mutableStateOf(sessionOwner.coordinator) }
    var confirmOnlineLeave by remember { mutableStateOf(false) }
    var logoutError by remember { mutableStateOf<String?>(null) }
    var logoutInProgress by remember { mutableStateOf(false) }
    DisposableEffect(matchmaking) {
        val closeable = matchmaking.observe { matchmakingState = it }
        onDispose { closeable.close() }
    }
    DisposableEffect(matchmakingState.status, matchmaking) {
        val closeable = if (matchmakingState.status == MatchmakingStatus.WAITING) {
            matchmaking.subscribeToMatchNotifications(
                onMatchAvailable = { scope.launch { matchmaking.claimNotifiedMatch() } },
            )
        } else null
        onDispose { closeable?.close() }
    }
    LaunchedEffect(matchmakingState.status, matchmakingState.assignment) {
        if (matchmakingState.status == MatchmakingStatus.WAITING) {
            while (isActive) {
                delay(10_000)
                matchmaking.heartbeat()
            }
        }
    }
    LaunchedEffect(matchmakingState.assignment) {
        val assignment = matchmakingState.assignment ?: return@LaunchedEffect
        p2pCoordinator = sessionOwner.startCoordinator(
            session.userId,
            assignment,
            debugAutoPlay,
            debugTimeControlMillis,
        )
    }
    fun leaveOnlineMatch() {
        confirmOnlineLeave = false
        val leaving = p2pCoordinator
        p2pCoordinator = null
        matchmaking.reset()
        scope.launch {
            if (sessionOwner.coordinator === leaving) sessionOwner.leaveCoordinator()
            else leaving?.leave()
        }
    }
    fun requestOnlineLeave() {
        if (p2pCoordinator?.controller?.viewState?.matchState?.status == com.example.othello.match.MatchStatus.PLAYING) {
            confirmOnlineLeave = true
        } else {
            leaveOnlineMatch()
        }
    }
    val parentDestination = backDestination(
        destination,
        reviewBackDestination,
        commonSettingsBackDestination,
        researchSettingsBackDestination,
    )
    BackHandler(enabled = localMatch || p2pCoordinator != null || parentDestination != null) {
        when {
            localMatch -> { localMatch = false; destination = AppDestination.PLAY }
            p2pCoordinator != null -> requestOnlineLeave()
            parentDestination != null -> destination = parentDestination
        }
    }
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        val showBottomNavigation = !localMatch && p2pCoordinator == null && destination.isTopLevel()
        Scaffold(
            bottomBar = {
                if (showBottomNavigation) {
                    ChanrivaBottomNavigation(selected = destination, onSelect = { destination = it })
                }
            },
        ) { contentPadding ->
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                when {
                    localMatch -> LocalMatchScreen(
                        mode = localMatchMode,
                        humanDisc = localHumanDisc,
                        dataManager = analysisDataManager,
                        settingsStore = edaxSettings,
                        engine = aiMoveEngine,
                        persistence = localRecordPersistence,
                        onBack = { localMatch = false; destination = AppDestination.PLAY },
                    )
                    p2pCoordinator != null -> OnlineMatchScreen(
                        coordinator = requireNotNull(p2pCoordinator),
                        scope = scope,
                        showDiagnostics = showDiagnostics,
                        onBack = ::requestOnlineLeave,
                    )
                    destination == AppDestination.STUDY -> StudyScreen(
                        onOnlineRecords = { destination = AppDestination.ONLINE_RECORDS },
                        onOfflineRecords = { destination = AppDestination.OFFLINE_RECORDS },
                    )
                    destination == AppDestination.ONLINE_RECORDS -> OnlineRecordsScreen(
                        userId = session.userId,
                        repository = component.gameRecordRepository,
                        localStore = localRecordStore,
                        onBack = { destination = AppDestination.STUDY },
                        onReview = {
                            selectedReviewInput = it
                            reviewBackDestination = AppDestination.ONLINE_RECORDS
                            destination = AppDestination.REVIEW
                        },
                    )
                    destination == AppDestination.OFFLINE_RECORDS -> OfflineRecordsScreen(
                        localStore = localRecordStore,
                        onBack = { destination = AppDestination.STUDY },
                        onReview = {
                            selectedReviewInput = it
                            reviewBackDestination = AppDestination.OFFLINE_RECORDS
                            destination = AppDestination.REVIEW
                        },
                    )
                    destination == AppDestination.REVIEW && selectedReviewInput != null && selectedReviewSession != null -> ReviewScreenV2(
                        input = requireNotNull(selectedReviewInput),
                        review = selectedReviewSession,
                        dataManager = analysisDataManager,
                        settingsStore = edaxSettings,
                        engine = analysisEngine,
                        localStore = localRecordStore,
                        researchParticipationRepository = component.researchParticipationRepository,
                        researchPositionRepository = component.researchPositionRepository,
                        onBack = { destination = reviewBackDestination },
                        onOpenCommonSettings = {
                            commonSettingsBackDestination = AppDestination.REVIEW
                            destination = AppDestination.COMMON_SETTINGS
                        },
                    )
                    destination == AppDestination.ACCOUNT_DELETION -> AccountDeletionScreen(
                        component.accountDeletionRepository,
                        onBack = { destination = AppDestination.MORE },
                        onRequested = {
                            scope.launch {
                                sessionOwner.finishAccountDeletionSession()
                            }
                        },
                    )
                    destination == AppDestination.SETTINGS -> SettingsScreen(
                        onMatchSettings = { destination = AppDestination.MATCH_SETTINGS },
                        onReviewSettings = { destination = AppDestination.REVIEW_SETTINGS },
                        onCommonSettings = {
                            commonSettingsBackDestination = AppDestination.SETTINGS
                            destination = AppDestination.COMMON_SETTINGS
                        },
                        onResearch = {
                            researchSettingsBackDestination = AppDestination.SETTINGS
                            destination = AppDestination.RESEARCH_SETTINGS
                        },
                    )
                    destination == AppDestination.MATCH_SETTINGS -> MatchSettingsScreen(
                        onBack = { destination = AppDestination.SETTINGS },
                        audioSettings = audioSettings,
                        edaxSettings = edaxSettings,
                    )
                    destination == AppDestination.REVIEW_SETTINGS -> ReviewSettingsScreen(
                        settingsStore = edaxSettings,
                        onBack = { destination = AppDestination.SETTINGS },
                    )
                    destination == AppDestination.COMMON_SETTINGS -> CommonSettingsScreen(
                        manager = analysisDataManager,
                        onDataChanged = analysisEngine::clearCache,
                        onBack = { destination = commonSettingsBackDestination },
                    )
                    destination == AppDestination.LOCAL_AI_SETUP -> LocalAiSetupScreen(
                        dataManager = analysisDataManager,
                        settingsStore = edaxSettings,
                        selectedDisc = localHumanDisc,
                        onDiscSelected = { localHumanDisc = it },
                        onBack = { destination = AppDestination.PLAY },
                        onOpenCommonSettings = {
                            commonSettingsBackDestination = AppDestination.LOCAL_AI_SETUP
                            destination = AppDestination.COMMON_SETTINGS
                        },
                        onStart = { localMatchMode = LocalMatchMode.AI; localMatch = true; destination = AppDestination.PLAY },
                    )
                    destination == AppDestination.RESEARCH_SETTINGS -> ResearchSettingsScreen(
                        repository = component.researchParticipationRepository,
                        onBack = { destination = researchSettingsBackDestination },
                    )
                    destination == AppDestination.MORE -> MoreScreen(
                        onResearchInfo = { destination = AppDestination.RESEARCH_INFO },
                        onPrivacy = { uriHandler.openUri("https://chanriva.shinp-studio.com/privacy") },
                        onAccountDeletion = { destination = AppDestination.ACCOUNT_DELETION },
                        onAbout = { destination = AppDestination.ABOUT },
                        logoutInProgress = logoutInProgress,
                        logoutError = logoutError,
                        onLogout = {
                            scope.launch {
                                logoutInProgress = true
                                logoutError = null
                                sessionOwner.signOut()
                                    .onFailure { logoutError = authErrorMessage(AuthOperation.SIGN_OUT, it) }
                                logoutInProgress = false
                            }
                        },
                    )
                    destination == AppDestination.RESEARCH_INFO -> ResearchInfoScreen(
                        onBack = { destination = AppDestination.MORE },
                        onResearchSettings = {
                            researchSettingsBackDestination = AppDestination.RESEARCH_INFO
                            destination = AppDestination.RESEARCH_SETTINGS
                        },
                    )
                    destination == AppDestination.ABOUT -> AboutScreen(
                        onBack = { destination = AppDestination.MORE },
                        onLicenses = { destination = AppDestination.OSS_LICENSES },
                    )
                    destination == AppDestination.OSS_LICENSES -> OpenSourceLicensesScreen(
                        onBack = { destination = AppDestination.ABOUT },
                    )
                    else -> PlayScreen(
                        state = matchmakingState,
                        session = session,
                        currentRatingRepository = component.currentRatingRepository,
                        failedLocalRecordSaves = localRecordSaveStates.values
                            .filter { it.status == LocalRecordSaveStatus.FAILED },
                        onOnlineStart = { scope.launch { matchmaking.enqueue() } },
                        onCancel = { scope.launch { matchmaking.cancel() } },
                        onLocalHumanStart = {
                            localMatchMode = LocalMatchMode.HUMAN
                            localHumanDisc = Disc.BLACK
                            localMatch = true
                        },
                        onLocalAiStart = { destination = AppDestination.LOCAL_AI_SETUP },
                        onRetryLocalRecordSave = localRecordPersistence::retry,
                    )
                }
            }
        }
    }
    if (confirmOnlineLeave) {
        AlertDialog(
            onDismissRequest = { confirmOnlineLeave = false },
            title = { Text("オンライン対局を終了しますか？") },
            text = { Text("対局中に戻ると切断負けとして結果を送信します。") },
            confirmButton = {
                ChanrivaDangerButton(onClick = ::leaveOnlineMatch) { Text("終了する") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmOnlineLeave = false }) { Text("対局を続ける") }
            },
        )
    }
}

@Composable
private fun OnlineMatchScreen(
    coordinator: WebRtcMatchCoordinator,
    scope: kotlinx.coroutines.CoroutineScope,
    showDiagnostics: Boolean,
    onBack: () -> Unit,
) {
    val controller = coordinator.controller
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioSettings = remember { AudioSettingsStore(context) }
    val audioController = remember { MatchAudioController(context) }
    val warningTracker = remember(controller) { TimeWarningTracker() }
    var warningMatchActive by remember(controller) { mutableStateOf(false) }
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(audioController) {
        onDispose { audioController.close() }
    }
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
    val localRemainingMillis = when (viewState.localDisc) {
        com.example.othello.game.Disc.BLACK -> viewState.blackRemainingMillis
        com.example.othello.game.Disc.WHITE -> viewState.whiteRemainingMillis
        com.example.othello.game.Disc.EMPTY -> 0L
    }
    LaunchedEffect(
        controller,
        viewState.matchState.status,
        localRemainingMillis,
        audioSettings.timeWarningEnabled,
    ) {
        if (viewState.matchState.status != com.example.othello.match.MatchStatus.PLAYING) {
            warningTracker.reset(localRemainingMillis)
            warningMatchActive = false
            audioController.stopAll()
            return@LaunchedEffect
        }
        if (!warningMatchActive) {
            warningTracker.reset(localRemainingMillis)
            warningMatchActive = true
            return@LaunchedEffect
        }
        val warnings = warningTracker.onRemainingChanged(localRemainingMillis)
        if (audioSettings.timeWarningEnabled) audioController.playTimeWarnings(warnings)
    }
    LaunchedEffect(
        viewState.matchState.status,
        audioSettings.focusSoundEnabled,
        audioSettings.focusSoundVolume,
        isResumed,
    ) {
        audioController.setPinkNoisePlaying(
            playing = viewState.matchState.status == com.example.othello.match.MatchStatus.PLAYING &&
                audioSettings.focusSoundEnabled && isResumed,
            volume = audioSettings.focusSoundVolume,
        )
    }
    var confirmResign by remember { mutableStateOf(false) }
    val diagnostics = if (showDiagnostics) coordinator.diagnostics() else null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("戻る") }
            Spacer(Modifier.weight(1f))
            Text("オンライン対局", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            opponentRatingLabel(coordinator.opponentRating),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        diagnostics?.let {
            Text("matchId: ${it.matchId}", style = MaterialTheme.typography.labelSmall)
            Text("status ${viewState.matchState.status.name} / disc ${viewState.localDisc}", style = MaterialTheme.typography.labelSmall)
        }
        ScoreHeader(viewState.game, viewState.matchState.status.userLabel())
        Text(
            "黒 ${formatClock(viewState.blackRemainingMillis)} / 白 ${formatClock(viewState.whiteRemainingMillis)}",
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        OnlineOthelloBoard(viewState, controller, scope)
        Text(viewState.message, modifier = Modifier.align(Alignment.CenterHorizontally))
        diagnostics?.let {
            Text(
                "ICE ${it.iceState} / Peer ${it.peerConnectionState} / DC ${it.dataChannelState} / ack ${it.localStartAcked}/${it.bothStartAcked}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text("ply ${viewState.game.ply} / hash ${viewState.game.stateHash()}", style = MaterialTheme.typography.labelSmall)
        }
        viewState.finishResult?.takeIf { it.serverStatus == "CONFIRMED" }?.let { result ->
            val before = result.ratingBefore
            val after = result.ratingAfter
            val delta = result.ratingDelta
            val current = result.currentRating
            val peak = result.peakRating
            if (before != null && after != null && delta != null) {
                Text("レーティング $before → $after (${delta.withSign()})")
            }
            if (current != null && peak != null) {
                Text("現在 $current / 最高 $peak")
            }
        }
        OutlinedButton(
            onClick = { confirmResign = true },
            enabled = viewState.matchState.status == com.example.othello.match.MatchStatus.PLAYING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("投了") }
        if (viewState.error != null) Text(viewState.error!!, color = MaterialTheme.colorScheme.error)
    }
    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            title = { Text("投了しますか？") },
            text = { Text("投了すると、この対局は負けとして確定処理へ進みます。") },
            confirmButton = {
                ChanrivaDangerButton(onClick = {
                    confirmResign = false
                    scope.launch { controller.resign() }
                }) { Text("投了する") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmResign = false }) { Text("対局を続ける") }
            },
        )
    }
}

private fun com.example.othello.match.MatchStatus.userLabel(): String = when (this) {
    com.example.othello.match.MatchStatus.P2P_CONNECTED -> "接続確認中"
    com.example.othello.match.MatchStatus.PLAYING -> "対局中"
    com.example.othello.match.MatchStatus.FINISHING -> "結果送信中"
    com.example.othello.match.MatchStatus.PENDING_RESULT -> "相手の結果待ち"
    com.example.othello.match.MatchStatus.CONFIRMED -> "結果確定"
    com.example.othello.match.MatchStatus.DISPUTED -> "結果不一致"
    com.example.othello.match.MatchStatus.DISCONNECTED -> "切断"
    com.example.othello.match.MatchStatus.FAILED -> "エラー"
    else -> name
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
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(ChanrivaColors.board).padding(3.dp)) {
        Column(Modifier.fillMaxSize()) {
            repeat(8) { row ->
                Row(Modifier.fillMaxWidth().weight(1f, fill = true)) {
                    repeat(8) { column ->
                    val position = Position(row, column)
                    val disc = viewState.game.board[position]
                    val legal = canPlay && position in viewState.game.legalMoves
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f, fill = true)
                            .border(0.5.dp, ChanrivaColors.boardGrid)
                            .semantics { contentDescription = position.accessibilityLabel(disc, legal) }
                            .clickable(enabled = legal) { scope.launch { controller.play(position) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (disc != Disc.EMPTY) Box(
                            Modifier
                                .size(34.dp)
                                .background(if (disc == Disc.BLACK) ChanrivaColors.blackDisc else ChanrivaColors.whiteDisc, CircleShape)
                                .border(1.dp, ChanrivaColors.discOutline, CircleShape),
                        )
                        else if (legal) Box(Modifier.size(10.dp).background(ChanrivaColors.legalMove, CircleShape))
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayScreen(
    state: com.example.othello.matchmaking.MatchmakingViewState,
    session: UserSession,
    currentRatingRepository: CurrentRatingRepository,
    failedLocalRecordSaves: List<LocalRecordSaveState>,
    onOnlineStart: () -> Unit,
    onCancel: () -> Unit,
    onLocalHumanStart: () -> Unit,
    onLocalAiStart: () -> Unit,
    onRetryLocalRecordSave: (String) -> Unit,
) {
    var currentRating by remember(session.userId) { mutableStateOf<Int?>(null) }
    var currentRatingLoading by remember(session.userId) { mutableStateOf(false) }
    LaunchedEffect(session.userId, currentRatingRepository) {
        currentRatingLoading = true
        currentRating = runCatching { currentRatingRepository.getCurrentRating() }.getOrNull()
        currentRatingLoading = false
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
        horizontalAlignment = Alignment.Start,
    ) {
        ChanrivaScreenHeader("対局")
        Text("ちゃんりば", style = MaterialTheme.typography.headlineSmall, color = ChanrivaColors.accent)
        Text("ちゃんと残る、ちゃんと振り返れるリバーシ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "現在のレート ${when {
                currentRatingLoading -> "取得中…"
                currentRating != null -> currentRating.toString()
                else -> "---"
            }}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("オンライン対局", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = onOnlineStart,
            enabled = state.status !in setOf(MatchmakingStatus.WAITING, MatchmakingStatus.SIGNALING),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("対局する") }
        when (state.status) {
            MatchmakingStatus.WAITING -> {
                Text("対戦相手を待っています")
                OutlinedButton(onClick = onCancel) { Text("キャンセル") }
            }
            MatchmakingStatus.SIGNALING -> Text("対戦相手が見つかりました。P2P接続を開始します")
            MatchmakingStatus.FAILED -> Text(state.error ?: "マッチングに失敗しました", color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        Text("端末で対局", style = MaterialTheme.typography.titleMedium)
        ChanrivaNavigationRow("ふたりで対局", onLocalHumanStart)
        ChanrivaNavigationRow("AIと対局", onLocalAiStart)
        if (failedLocalRecordSaves.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "ローカル棋譜の保存に失敗しました（${failedLocalRecordSaves.size}件）",
                        color = MaterialTheme.colorScheme.error,
                    )
                    failedLocalRecordSaves.forEach { failed ->
                        OutlinedButton(
                            onClick = { onRetryLocalRecordSave(failed.localId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("保存を再試行") }
                    }
                }
            }
        }
    }
}

internal fun opponentRatingLabel(rating: Int?): String = "相手　レート ${rating ?: "---"}"

@Composable
private fun MatchScreen(onBack: () -> Unit) {
    val controller = remember { LocalMatchController() }
    var viewState by remember { mutableStateOf<LocalMatchViewState>(controller.viewState) }
    DisposableEffect(controller) {
        val closeable = controller.observe { viewState = it }
        onDispose { closeable.close() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
private fun LocalMatchScreen(
    mode: LocalMatchMode,
    humanDisc: Disc,
    dataManager: EdaxDataManager,
    settingsStore: EdaxSettingsStore,
    engine: ProductionAiMoveEngine,
    persistence: LocalGameRecordPersistenceCoordinator,
    onBack: () -> Unit,
) {
    val controller = remember(mode, humanDisc) { LocalMatchController(mode = mode, humanDisc = humanDisc) }
    val aiTurnController = remember(controller, engine) { LocalAiTurnController(controller, engine) }
    var viewState by remember { mutableStateOf(controller.viewState) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var confirmResign by remember { mutableStateOf(false) }
    val saveStates by persistence.saveStates.collectAsState()
    DisposableEffect(controller, persistence) {
        val closeable = controller.observe { next ->
            viewState = next
            next.completedRecord?.let(persistence::enqueue)
        }
        onDispose { closeable.close() }
    }
    LaunchedEffect(controller, viewState.game.ply, viewState.game.currentPlayer, viewState.completedRecord) {
        if (mode == LocalMatchMode.AI && viewState.aiDisc == viewState.game.currentPlayer &&
            !viewState.aiThinking && viewState.completedRecord == null && viewState.game.status is com.example.othello.game.GameStatus.InProgress
        ) {
            runCatching {
                aiTurnController.play(dataManager.aiMoveSettings(settingsStore.aiMatchSettings()))
            }
                .onFailure { if (it !is CancellationException) saveError = it.message ?: "AI move failed" }
        }
    }
    DisposableEffect(engine) {
        onDispose { engine.cancel() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("戻る") }
            Spacer(Modifier.weight(1f))
            Text(if (mode == LocalMatchMode.AI) "AIと対局" else "ふたりで対局", style = MaterialTheme.typography.titleLarge)
        }
        ScoreHeader(viewState.game, if (mode == LocalMatchMode.AI) "AI対局" else "ローカル")
        LocalOthelloBoard(viewState, controller)
        Text(viewState.message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (viewState.aiThinking) Text("AI思考中…", modifier = Modifier.align(Alignment.CenterHorizontally))
        viewState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        viewState.completedRecord?.let { record ->
            when (saveStates[record.localId]?.status) {
                LocalRecordSaveStatus.SAVED -> Text("ローカル棋譜を保存しました")
                LocalRecordSaveStatus.FAILED -> {
                    Text(
                        saveStates[record.localId]?.errorMessage ?: "ローカル棋譜を保存できませんでした",
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = { persistence.retry(record.localId) }) { Text("保存を再試行") }
                }
                LocalRecordSaveStatus.PENDING,
                LocalRecordSaveStatus.SAVING,
                null -> Text("ローカル棋譜を保存中…")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirmResign = true },
                enabled = viewState.finishReason == null && !viewState.aiThinking,
                modifier = Modifier.weight(1f),
            ) { Text("投了") }
            Button(onClick = controller::reset, modifier = Modifier.weight(1f)) { Text("新しい対局") }
        }
    }
    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            title = { Text("投了しますか？") },
            text = { Text("この対局を投了としてローカル棋譜に保存します。") },
            confirmButton = {
                ChanrivaDangerButton(onClick = { confirmResign = false; controller.resign(if (mode == LocalMatchMode.AI) humanDisc else viewState.game.currentPlayer) }) { Text("投了する") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmResign = false }) { Text("続ける") } },
        )
    }
}

@Composable
private fun LocalAiSetupScreen(
    dataManager: EdaxDataManager,
    settingsStore: EdaxSettingsStore,
    selectedDisc: Disc,
    onDiscSelected: (Disc) -> Unit,
    onBack: () -> Unit,
    onOpenCommonSettings: () -> Unit,
    onStart: () -> Unit,
) {
    val status = remember { dataManager.commonDataStatus() }
    val aiSettings = remember { settingsStore.aiMatchSettings() }
    val ready = status.nativeAvailable && status.evaluationData != null
    Column(Modifier.fillMaxSize().padding(ChanrivaSpacing.page), verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("戻る") }
            Spacer(Modifier.weight(1f))
            Text("AIと対局", style = MaterialTheme.typography.titleLarge)
        }
        Text("ユーザーの色")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onDiscSelected(Disc.BLACK) }, modifier = Modifier.weight(1f)) { Text(if (selectedDisc == Disc.BLACK) "✓ 黒" else "黒") }
            OutlinedButton(onClick = { onDiscSelected(Disc.WHITE) }, modifier = Modifier.weight(1f)) { Text(if (selectedDisc == Disc.WHITE) "✓ 白" else "白") }
        }
        Text("AI対局レベル: Level ${aiSettings.level}")
        if (!ready) {
            Text(
                when {
                    !status.nativeAvailable -> "Edaxを利用できません"
                    status.evaluationData == null -> "評価データを設定してください"
                    else -> "AI対局を開始できません"
                },
                color = MaterialTheme.colorScheme.error,
            )
            if (status.evaluationData == null) {
                OutlinedButton(onClick = onOpenCommonSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("評価データを設定する")
                }
            }
        }
        Button(onClick = onStart, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("対局開始") }
    }
}

@Composable
private fun LocalOthelloBoard(viewState: LocalMatchViewState, controller: LocalMatchController) {
    val canPlay = !viewState.aiThinking && viewState.finishReason == null &&
        viewState.game.status is com.example.othello.game.GameStatus.InProgress &&
        (viewState.mode == LocalMatchMode.HUMAN || viewState.game.currentPlayer == viewState.humanDisc)
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(ChanrivaColors.board).padding(3.dp)) {
        Column(Modifier.fillMaxSize()) {
            repeat(8) { row ->
                Row(Modifier.fillMaxWidth().weight(1f, fill = true)) {
                    repeat(8) { column ->
                    val position = Position(row, column)
                    val disc = viewState.game.board[position]
                    val legal = canPlay && position in viewState.game.legalMoves
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .weight(1f, fill = true)
                            .border(0.5.dp, ChanrivaColors.boardGrid)
                            .clickable(enabled = legal) { controller.play(position) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (disc != Disc.EMPTY) Box(
                            Modifier
                                .size(34.dp)
                                .background(if (disc == Disc.BLACK) ChanrivaColors.blackDisc else ChanrivaColors.whiteDisc, CircleShape)
                                .border(1.dp, ChanrivaColors.discOutline, CircleShape),
                        )
                        else if (legal) Box(Modifier.size(10.dp).background(ChanrivaColors.legalMove, CircleShape))
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreHeader(viewState: LocalMatchViewState) = ScoreHeader(viewState.game, "ローカル")

@Composable
private fun ScoreHeader(game: com.example.othello.game.GameState, status: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(ChanrivaSpacing.card), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("黒 ${game.board.count(Disc.BLACK)}")
            Text("白 ${game.board.count(Disc.WHITE)}")
            Text("手数 ${game.ply}")
            Text(status)
        }
    }
}

@Composable
private fun OthelloBoard(viewState: LocalMatchViewState, controller: LocalMatchController) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(ChanrivaColors.board).padding(3.dp)) {
        Column(Modifier.fillMaxSize()) {
            repeat(8) { row ->
                Row(Modifier.fillMaxWidth().weight(1f, fill = true)) {
                    repeat(8) { column ->
                    val position = Position(row, column)
                    val disc = viewState.game.board[position]
                    val legal = position in viewState.game.legalMoves
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f, fill = true)
                            .border(0.5.dp, ChanrivaColors.boardGrid)
                            .semantics { contentDescription = position.accessibilityLabel(disc, legal) }
                            .clickable(enabled = legal) { controller.play(position) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (disc != Disc.EMPTY) Box(
                            Modifier
                                .size(34.dp)
                                .background(if (disc == Disc.BLACK) ChanrivaColors.blackDisc else ChanrivaColors.whiteDisc, CircleShape)
                                .border(1.dp, ChanrivaColors.discOutline, CircleShape),
                        )
                        else if (legal) Box(Modifier.size(10.dp).background(ChanrivaColors.legalMove, CircleShape))
                    }
                    }
                }
            }
        }
    }
}

private fun Position.accessibilityLabel(disc: Disc, legal: Boolean): String {
    val coordinate = "${('A'.code + column).toChar()}${row + 1}"
    val state = when (disc) {
        Disc.BLACK -> "黒石"
        Disc.WHITE -> "白石"
        Disc.EMPTY -> if (legal) "合法手" else "空き"
    }
    return "$coordinate、$state"
}
