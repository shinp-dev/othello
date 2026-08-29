package com.example.othello

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.lifecycle.repeatOnLifecycle
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
import com.example.othello.records.FinishReason
import com.example.othello.records.GameRecord
import com.example.othello.review.ReviewInput
import com.example.othello.review.ReviewSession
import com.example.othello.review.PositionReviewRecord
import com.example.othello.review.PositionReviewSession
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchOptions = debugLaunchOptions(intent)
        setContent {
            OthelloTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(MaterialTheme.colorScheme.background),
                    )
                    OthelloApp(launchOptions.autoPlay, launchOptions.timeControlMillis, launchOptions.showDiagnostics)
                }
            }
        }
    }
}

private data class PositionReviewWorkspace(
    val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val session: PositionReviewSession,
)

@Composable
private fun OthelloApp(
    debugAutoPlay: Boolean,
    debugTimeControlMillis: Long?,
    showDiagnostics: Boolean,
    versionGateOwner: VersionGateViewModel = viewModel(),
) {
    VersionGate(versionGateOwner) {
        AuthenticatedRoot(
            debugAutoPlay = debugAutoPlay,
            debugTimeControlMillis = debugTimeControlMillis,
            showDiagnostics = showDiagnostics,
        )
    }
}

@Composable
private fun AuthenticatedRoot(
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
    val ratingAchievementStore = remember { RatingAchievementStore(context) }
    val analysisDataManager = remember { EdaxDataManager(context) }
    val edaxSettings = remember { EdaxSettingsStore(context) }
    val analysisEngine = remember { ProductionAnalysisEngine() }
    val aiMoveEngine = remember { ProductionAiMoveEngine() }
    val localRecordStore = application.localGameRecordStore
    val positionReviewStore = application.positionReviewStore
    val theorySessionStore = application.theorySessionStore
    val theorySessionPersistence = application.theorySessionPersistence.coordinator
    val theoryAnalysisCache = application.theoryAnalysisCache
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
    var positionReviewWorkspace by remember { mutableStateOf<PositionReviewWorkspace?>(null) }
    var commonSettingsBackDestination by remember { mutableStateOf(AppDestination.SETTINGS) }
    var reviewBackDestination by remember { mutableStateOf(AppDestination.STUDY) }
    var researchSettingsBackDestination by remember { mutableStateOf(AppDestination.SETTINGS) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
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
    LaunchedEffect(matchmakingState.status, matchmakingState.assignment, lifecycleOwner) {
        if (matchmakingState.status == MatchmakingStatus.WAITING) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    // V2 queue rows have a two-minute lease. Realtime delivers a match
                    // immediately; this low-frequency renewal is only loss recovery.
                    delay(75_000)
                    matchmaking.heartbeat()
                }
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
        if (p2pCoordinator?.controller?.viewState?.matchState?.status in setOf(
                com.example.othello.match.MatchStatus.PLAYING,
                com.example.othello.match.MatchStatus.MOVE_CONFIRMING,
                com.example.othello.match.MatchStatus.SYNCHRONIZING,
                com.example.othello.match.MatchStatus.RECONNECTING,
            )
        ) {
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
                        onPositionReview = { destination = AppDestination.POSITION_REVIEW_HOME },
                        onTheoryExploration = { destination = AppDestination.THEORY_EXPLORATION },
                        onOnlineRecords = { destination = AppDestination.ONLINE_RECORDS },
                        onOfflineRecords = { destination = AppDestination.OFFLINE_RECORDS },
                    )
                    destination == AppDestination.THEORY_EXPLORATION -> TheoryExplorationScreen(
                        sessionStore = theorySessionStore,
                        persistence = theorySessionPersistence,
                        analysisCache = theoryAnalysisCache,
                        dataManager = analysisDataManager,
                        settingsStore = edaxSettings,
                        engine = analysisEngine,
                        onBack = { destination = AppDestination.STUDY },
                        onOpenCommonSettings = {
                            commonSettingsBackDestination = AppDestination.THEORY_EXPLORATION
                            destination = AppDestination.COMMON_SETTINGS
                        },
                    )
                    destination == AppDestination.POSITION_REVIEW_HOME -> PositionReviewHomeScreen(
                        store = positionReviewStore,
                        onBack = { destination = AppDestination.STUDY },
                        onNew = {
                            positionReviewWorkspace = null
                            destination = AppDestination.POSITION_REVIEW_INPUT
                        },
                        onOpen = { record: PositionReviewRecord ->
                            positionReviewWorkspace = PositionReviewWorkspace(
                                id = record.id,
                                title = record.title,
                                createdAtEpochMillis = record.createdAtEpochMillis,
                                session = PositionReviewSession(record),
                            )
                            destination = AppDestination.POSITION_REVIEW
                        },
                    )
                    destination == AppDestination.POSITION_REVIEW_INPUT -> PositionReviewInputScreen(
                        onBack = { destination = AppDestination.POSITION_REVIEW_HOME },
                        onStart = { board, side, title ->
                            val now = System.currentTimeMillis()
                            positionReviewWorkspace = PositionReviewWorkspace(
                                id = UUID.randomUUID().toString(),
                                title = title,
                                createdAtEpochMillis = now,
                                session = PositionReviewSession(board, side),
                            )
                            destination = AppDestination.POSITION_REVIEW
                        },
                    )
                    destination == AppDestination.POSITION_REVIEW && positionReviewWorkspace != null -> {
                        val workspace = requireNotNull(positionReviewWorkspace)
                        PositionReviewScreen(
                            id = workspace.id,
                            initialTitle = workspace.title,
                            createdAtEpochMillis = workspace.createdAtEpochMillis,
                            session = workspace.session,
                            store = positionReviewStore,
                            dataManager = analysisDataManager,
                            settingsStore = edaxSettings,
                            engine = analysisEngine,
                            onBack = { destination = AppDestination.POSITION_REVIEW_HOME },
                            onOpenCommonSettings = {
                                commonSettingsBackDestination = AppDestination.POSITION_REVIEW
                                destination = AppDestination.COMMON_SETTINGS
                            },
                            onSaved = { savedTitle ->
                                positionReviewWorkspace = workspace.copy(title = savedTitle)
                            },
                        )
                    }
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
                        onSaveMemo = { memo ->
                            selectedReviewInput?.localRecordId?.let { localId ->
                                scope.launch {
                                    runCatching { localRecordStore.updateMemo(localId, memo) }
                                        .onFailure { /* The review screen remains usable; list reload reports store errors. */ }
                                }
                            }
                        },
                    )
                    destination == AppDestination.ACCOUNT -> AccountScreen(
                        userId = session.userId,
                        currentRatingRepository = component.currentRatingRepository,
                        ratingAchievementStore = ratingAchievementStore,
                        logoutInProgress = logoutInProgress,
                        logoutError = logoutError,
                        onBack = { destination = AppDestination.MORE },
                        onAccountDeletion = { destination = AppDestination.ACCOUNT_DELETION },
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
                    destination == AppDestination.ACCOUNT_DELETION -> AccountDeletionScreen(
                        component.accountDeletionRepository,
                        onBack = { destination = AppDestination.ACCOUNT },
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
                        onAiSettings = { destination = AppDestination.AI_MATCH_SETTINGS },
                        onCommonMatchSettings = { destination = AppDestination.MATCH_COMMON_SETTINGS },
                    )
                    destination == AppDestination.AI_MATCH_SETTINGS -> AiMatchSettingsScreen(
                        onBack = { destination = AppDestination.MATCH_SETTINGS },
                        edaxSettings = edaxSettings,
                    )
                    destination == AppDestination.MATCH_COMMON_SETTINGS -> MatchCommonSettingsScreen(
                        onBack = { destination = AppDestination.MATCH_SETTINGS },
                        audioSettings = audioSettings,
                    )
                    destination == AppDestination.REVIEW_SETTINGS -> ReviewSettingsScreen(
                        settingsStore = edaxSettings,
                        onBack = { destination = AppDestination.SETTINGS },
                    )
                    destination == AppDestination.COMMON_SETTINGS -> CommonSettingsScreen(
                        manager = analysisDataManager,
                        onDataChanged = {
                            analysisEngine.clearCache()
                            scope.launch { theoryAnalysisCache.clear() }
                        },
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
                        onAccount = { destination = AppDestination.ACCOUNT },
                        onResearchInfo = { destination = AppDestination.RESEARCH_INFO },
                        onAbout = { destination = AppDestination.ABOUT },
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
                        onPrivacy = { uriHandler.openUri("https://chanriva.shinp-studio.com/privacy") },
                        onLicenses = { destination = AppDestination.OSS_LICENSES },
                    )
                    destination == AppDestination.OSS_LICENSES -> OpenSourceLicensesScreen(
                        onBack = { destination = AppDestination.ABOUT },
                        onEdax = { destination = AppDestination.EDAX_LICENSE },
                        onOtherOss = { destination = AppDestination.OTHER_OSS_LICENSES },
                    )
                    destination == AppDestination.EDAX_LICENSE -> EdaxLicenseScreen(
                        onBack = { destination = AppDestination.OSS_LICENSES },
                    )
                    destination == AppDestination.OTHER_OSS_LICENSES -> OtherOssLicensesScreen(
                        onBack = { destination = AppDestination.OSS_LICENSES },
                    )
                    else -> PlayScreen(
                        state = matchmakingState,
                        failedLocalRecordSaves = localRecordSaveStates.values
                            .filter {
                                it.status == LocalRecordSaveStatus.FAILED ||
                                    it.status == LocalRecordSaveStatus.DISCARD_FAILED
                            },
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
            title = { Text(appString(R.string.leave_online_title)) },
            text = { Text(appString(R.string.leave_online_text)) },
            confirmButton = {
                ChanrivaDangerButton(onClick = ::leaveOnlineMatch) { Text(appString(R.string.end_match)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmOnlineLeave = false }) { Text(appString(R.string.continue_match)) }
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
            OutlinedButton(onClick = onBack) { Text(appString(R.string.back)) }
            Spacer(Modifier.weight(1f))
            Text(appString(R.string.online_match), style = MaterialTheme.typography.titleLarge)
        }
        Text(
            opponentRatingLabel(coordinator.opponentRating, context),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        diagnostics?.let {
            Text("matchId: ${it.matchId}", style = MaterialTheme.typography.labelSmall)
            Text("status ${viewState.matchState.status.name} / disc ${viewState.localDisc}", style = MaterialTheme.typography.labelSmall)
        }
        ScoreHeader(
            viewState.game,
            viewState.matchState.status.userLabel(context, viewState.terminalFinishReason),
        )
        Text(
            "${appString(R.string.black)} ${formatClock(viewState.blackRemainingMillis)} / ${appString(R.string.white)} ${formatClock(viewState.whiteRemainingMillis)}",
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        OnlineOthelloBoard(viewState, controller, scope)
        viewState.message
            .takeUnless { it == viewState.finishResult?.serverStatus }
            ?.let { message ->
                Text(
                    localizeUserMessage(context, message).orEmpty(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        diagnostics?.let {
            Text(
                "ICE ${it.iceState} / Peer ${it.peerConnectionState} / DC ${it.dataChannelState} / ack ${it.localStartAcked}/${it.bothStartAcked}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text("ply ${viewState.game.ply} / hash ${viewState.game.stateHash()}", style = MaterialTheme.typography.labelSmall)
        }
        viewState.finishResult?.let { result ->
            val before = result.ratingBefore
            val after = result.ratingAfter
            val delta = result.ratingDelta
            val current = result.currentRating
            val peak = result.peakRating
            if (before != null && after != null && delta != null) {
                Text(appString(R.string.rating_change, before, after, delta.withSign()))
                if (current != null && peak != null) {
                    Text(appString(R.string.current_peak, current, peak))
                }
            }
        }
        OutlinedButton(
            onClick = { confirmResign = true },
            enabled = viewState.matchState.status == com.example.othello.match.MatchStatus.PLAYING,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(appString(R.string.resign)) }
        val recoveryActionAvailable = viewState.matchState.status in setOf(
            com.example.othello.match.MatchStatus.PENDING_RESULT,
            com.example.othello.match.MatchStatus.RECONNECTING,
        ) || (viewState.error != null && viewState.matchState.status in setOf(
            com.example.othello.match.MatchStatus.P2P_CONNECTED,
            com.example.othello.match.MatchStatus.PLAYING,
            com.example.othello.match.MatchStatus.MOVE_CONFIRMING,
            com.example.othello.match.MatchStatus.SYNCHRONIZING,
            com.example.othello.match.MatchStatus.FINISHING,
        ))
        if (recoveryActionAvailable) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        coordinator.retryCurrentOperation()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    appString(
                        if (viewState.matchState.status in setOf(
                                com.example.othello.match.MatchStatus.FINISHING,
                                com.example.othello.match.MatchStatus.PENDING_RESULT,
                            )
                        ) R.string.retry_result else R.string.retry,
                    ),
                )
            }
        }
        localizeUserMessage(context, viewState.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            title = { Text(appString(R.string.resign_confirm_title)) },
            text = { Text(appString(R.string.online_resign_text)) },
            confirmButton = {
                ChanrivaDangerButton(onClick = {
                    confirmResign = false
                    scope.launch { controller.resign() }
                }) { Text(appString(R.string.resign_action)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmResign = false }) { Text(appString(R.string.continue_match)) }
            },
        )
    }
}

private fun com.example.othello.match.MatchStatus.userLabel(
    context: android.content.Context? = null,
    finishReason: FinishReason? = null,
): String = when (this) {
    com.example.othello.match.MatchStatus.P2P_CONNECTED -> context?.getString(R.string.match_status_connecting) ?: "接続確認中"
    com.example.othello.match.MatchStatus.PLAYING -> context?.getString(R.string.match_status_playing) ?: "対局中"
    com.example.othello.match.MatchStatus.MOVE_CONFIRMING -> context?.getString(R.string.match_status_move_confirming) ?: "着手確認待ち"
    com.example.othello.match.MatchStatus.SYNCHRONIZING -> context?.getString(R.string.match_status_synchronizing) ?: "対局を同期中"
    com.example.othello.match.MatchStatus.RECONNECTING -> context?.getString(R.string.match_status_reconnecting) ?: "再接続中"
    com.example.othello.match.MatchStatus.FINISHING -> context?.getString(R.string.match_status_sending) ?: "結果送信中"
    com.example.othello.match.MatchStatus.PENDING_RESULT -> context?.getString(R.string.match_status_waiting_result) ?: "相手の結果待ち"
    com.example.othello.match.MatchStatus.CONFIRMED -> context?.getString(R.string.match_status_confirmed) ?: "結果確定"
    com.example.othello.match.MatchStatus.DISPUTED -> context?.getString(R.string.match_status_disputed) ?: "結果不一致"
    com.example.othello.match.MatchStatus.FORFEIT -> when (finishReason) {
        FinishReason.RESIGNATION -> context?.getString(R.string.match_status_forfeit_resignation) ?: "投了により勝敗確定"
        FinishReason.TIMEOUT -> context?.getString(R.string.match_status_forfeit_timeout) ?: "時間切れにより勝敗確定"
        FinishReason.DISCONNECT -> context?.getString(R.string.match_status_forfeit_disconnect) ?: "切断により勝敗確定"
        else -> context?.getString(R.string.match_status_forfeit) ?: "勝敗確定"
    }
    com.example.othello.match.MatchStatus.EXPIRED -> context?.getString(R.string.match_status_expired) ?: "無効対局として終了"
    com.example.othello.match.MatchStatus.ABANDONED -> context?.getString(R.string.match_status_abandoned) ?: "対局をキャンセルしました"
    com.example.othello.match.MatchStatus.DISCONNECTED -> context?.getString(R.string.match_status_disconnected) ?: "切断"
    com.example.othello.match.MatchStatus.SIGNALING_FAILED -> context?.getString(R.string.error) ?: "エラー"
    com.example.othello.match.MatchStatus.FAILED -> context?.getString(R.string.error) ?: "エラー"
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
    CoordinateBoard { position, cellModifier ->
        val disc = viewState.game.board[position]
        val legal = canPlay && position in viewState.game.legalMoves
        Box(
            modifier = cellModifier
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

@Composable
private fun PlayScreen(
    state: com.example.othello.matchmaking.MatchmakingViewState,
    failedLocalRecordSaves: List<LocalRecordSaveState>,
    onOnlineStart: () -> Unit,
    onCancel: () -> Unit,
    onLocalHumanStart: () -> Unit,
    onLocalAiStart: () -> Unit,
    onRetryLocalRecordSave: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
        horizontalAlignment = Alignment.Start,
    ) {
        ChanrivaScreenHeader(appString(R.string.play))
        ChanrivaNavigationRow(
            title = appString(R.string.play_online),
            supportingText = appString(R.string.play_online_supporting),
            onClick = if (state.status !in setOf(MatchmakingStatus.WAITING, MatchmakingStatus.SIGNALING)) onOnlineStart else null,
            emphasized = true,
        )
        when (state.status) {
            MatchmakingStatus.WAITING -> {
                Text(appString(R.string.opponent_waiting))
                OutlinedButton(onClick = onCancel) { Text(appString(R.string.cancel)) }
            }
            MatchmakingStatus.SIGNALING -> Text(appString(R.string.opponent_found))
            MatchmakingStatus.FAILED -> Text(localizeUserMessage(context, state.error) ?: appString(R.string.matchmaking_failed), color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        ChanrivaNavigationRow(
            title = appString(R.string.play_against_ai),
            supportingText = appString(R.string.play_against_ai_supporting),
            onClick = onLocalAiStart,
            emphasized = true,
        )
        ChanrivaNavigationRow(
            title = appString(R.string.two_player_match),
            supportingText = appString(R.string.two_player_match_supporting),
            onClick = onLocalHumanStart,
        )
        if (failedLocalRecordSaves.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        appString(R.string.local_record_operation_failed, failedLocalRecordSaves.size),
                        color = MaterialTheme.colorScheme.error,
                    )
                    failedLocalRecordSaves.forEach { failed ->
                        OutlinedButton(
                            onClick = { onRetryLocalRecordSave(failed.localId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(appString(R.string.retry_record_operation)) }
                    }
                }
            }
        }
    }
}

internal fun opponentRatingLabel(rating: Int?, context: android.content.Context? = null): String =
    context?.getString(R.string.opponent_rating, rating ?: "---") ?: "相手　レート ${rating ?: "---"}"

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
            OutlinedButton(onClick = onBack) { Text(appString(R.string.back)) }
            Spacer(Modifier.weight(1f))
            Text(appString(R.string.local_match), style = MaterialTheme.typography.titleLarge)
        }
        ScoreHeader(viewState)
        OthelloBoard(viewState, controller)
        Text(localMatchStatusText(viewState.message), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        Button(onClick = controller::reset, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.new_match)) }
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
    val context = LocalContext.current
    val controller = remember(mode, humanDisc) { LocalMatchController(mode = mode, humanDisc = humanDisc) }
    val aiTurnController = remember(controller, engine) { LocalAiTurnController(controller, engine) }
    var viewState by remember { mutableStateOf(controller.viewState) }
    var aiConfiguration by remember(mode, humanDisc, dataManager, settingsStore) {
        mutableStateOf(
            if (mode == LocalMatchMode.AI) {
                dataManager.aiMatchConfiguration(settingsStore.aiMatchSettings())
            } else {
                null
            },
        )
    }
    var saveError by remember { mutableStateOf<String?>(null) }
    var confirmResign by remember { mutableStateOf(false) }
    var matchGeneration by remember(mode, humanDisc) { mutableStateOf(0) }
    val saveStates by persistence.saveStates.collectAsState()
    DisposableEffect(controller, persistence) {
        val closeable = controller.observe { next ->
            viewState = next
            next.completedRecord?.let(persistence::enqueue)
        }
        onDispose { closeable.close() }
    }
    LaunchedEffect(controller, matchGeneration, viewState.game, viewState.completedRecord, aiConfiguration) {
        if (mode == LocalMatchMode.AI && viewState.aiDisc == viewState.game.currentPlayer &&
            !viewState.aiThinking && viewState.completedRecord == null && viewState.game.status is com.example.othello.game.GameStatus.InProgress
        ) {
            try {
                aiTurnController.play(requireNotNull(aiConfiguration).moveSettings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                saveError = localizeUserMessage(context, failure.message) ?: context.getString(R.string.ai_match_unavailable)
            }
        }
    }
    DisposableEffect(aiTurnController) {
        onDispose { aiTurnController.cancel() }
    }
    fun undoMove() {
        if (!viewState.canUndo) return
        if (mode == LocalMatchMode.AI) aiTurnController.cancelForUndo()
        controller.undo()?.let { result ->
            result.invalidatedRecord?.let { persistence.discard(it.localId) }
            saveError = null
            confirmResign = false
        }
    }
    fun resetMatch() {
        if (mode == LocalMatchMode.AI) {
            aiTurnController.cancel()
            aiConfiguration = dataManager.aiMatchConfiguration(settingsStore.aiMatchSettings())
        }
        controller.reset()
        matchGeneration++
        saveError = null
        confirmResign = false
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text(appString(R.string.back)) }
            Spacer(Modifier.weight(1f))
            Text(appString(if (mode == LocalMatchMode.AI) R.string.play_against_ai else R.string.two_player_match), style = MaterialTheme.typography.titleLarge)
        }
        ScoreHeader(viewState.game)
        LocalOthelloBoard(viewState, controller)
        Text(localMatchStatusText(viewState.message), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (viewState.aiThinking) Text(appString(R.string.ai_thinking), modifier = Modifier.align(Alignment.CenterHorizontally))
        aiConfiguration?.let { AiMatchConditions(it, viewState.undoUsed) }
        localizeUserMessage(context, viewState.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        viewState.completedRecord?.let { record ->
            when (saveStates[record.localId]?.status) {
                LocalRecordSaveStatus.SAVED -> Text(appString(R.string.local_record_saved))
                LocalRecordSaveStatus.FAILED -> {
                    Text(
                        localizeUserMessage(context, saveStates[record.localId]?.errorMessage) ?: appString(R.string.local_record_save_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = { persistence.retry(record.localId) }) { Text(appString(R.string.retry_save)) }
                }
                LocalRecordSaveStatus.PENDING,
                LocalRecordSaveStatus.SAVING,
                null -> Text(appString(R.string.local_record_saving))
                LocalRecordSaveStatus.DISCARDING,
                LocalRecordSaveStatus.DISCARDED,
                LocalRecordSaveStatus.DISCARD_FAILED -> Unit
            }
        }
        OutlinedButton(
            onClick = ::undoMove,
            enabled = viewState.canUndo,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(appString(R.string.undo_move)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirmResign = true },
                enabled = viewState.finishReason == null && !viewState.aiThinking,
                modifier = Modifier.weight(1f),
            ) { Text(appString(R.string.resign)) }
            Button(onClick = ::resetMatch, modifier = Modifier.weight(1f)) { Text(appString(R.string.new_match)) }
        }
    }
    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            title = { Text(appString(R.string.resign_confirm_title)) },
            text = { Text(appString(R.string.local_resign_text)) },
            confirmButton = {
                ChanrivaDangerButton(onClick = { confirmResign = false; controller.resign(if (mode == LocalMatchMode.AI) humanDisc else viewState.game.currentPlayer) }) { Text(appString(R.string.resign_action)) }
            },
            dismissButton = { OutlinedButton(onClick = { confirmResign = false }) { Text(appString(R.string.continue_label)) } },
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
            OutlinedButton(onClick = onBack) { Text(appString(R.string.back)) }
            Spacer(Modifier.weight(1f))
            Text(appString(R.string.play_against_ai), style = MaterialTheme.typography.titleLarge)
        }
        Text(appString(R.string.user_color))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onDiscSelected(Disc.BLACK) }, modifier = Modifier.weight(1f)) { Text(if (selectedDisc == Disc.BLACK) appString(R.string.black_checked) else appString(R.string.black)) }
            OutlinedButton(onClick = { onDiscSelected(Disc.WHITE) }, modifier = Modifier.weight(1f)) { Text(if (selectedDisc == Disc.WHITE) appString(R.string.white_checked) else appString(R.string.white)) }
        }
        Text(appString(R.string.ai_level, aiSettings.level))
        if (!ready) {
            Text(
                when {
                    !status.nativeAvailable -> appString(R.string.edax_engine_unavailable)
                    status.evaluationData == null -> appString(R.string.analysis_data_not_set)
                    else -> appString(R.string.ai_match_unavailable)
                },
                color = MaterialTheme.colorScheme.error,
            )
            if (status.evaluationData == null) {
                ChanrivaNavigationRow(
                    title = appString(R.string.set_evaluation_data),
                    onClick = onOpenCommonSettings,
                )
            }
        }
        ChanrivaNavigationRow(
            title = appString(R.string.start_match),
            onClick = if (ready) onStart else null,
            emphasized = true,
        )
    }
}

@Composable
private fun LocalOthelloBoard(viewState: LocalMatchViewState, controller: LocalMatchController) {
    val canPlay = !viewState.aiThinking && viewState.finishReason == null &&
        viewState.game.status is com.example.othello.game.GameStatus.InProgress &&
        (viewState.mode == LocalMatchMode.HUMAN || viewState.game.currentPlayer == viewState.humanDisc)
    CoordinateBoard { position, cellModifier ->
        val disc = viewState.game.board[position]
        val legal = canPlay && position in viewState.game.legalMoves
        Box(
            cellModifier.clickable(enabled = legal) { controller.play(position) },
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

@Composable
private fun ScoreHeader(viewState: LocalMatchViewState) = ScoreHeader(viewState.game)

@Composable
private fun ScoreHeader(game: com.example.othello.game.GameState, status: String? = null) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(ChanrivaSpacing.card)) {
            Text(appString(R.string.black_count, game.board.count(Disc.BLACK)), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(appString(R.string.white_count, game.board.count(Disc.WHITE)), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(appString(R.string.ply_count, game.ply), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(status.orEmpty(), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun OthelloBoard(viewState: LocalMatchViewState, controller: LocalMatchController) {
    CoordinateBoard { position, cellModifier ->
        val disc = viewState.game.board[position]
        val legal = position in viewState.game.legalMoves
        Box(
            modifier = cellModifier
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

private fun Position.accessibilityLabel(disc: Disc, legal: Boolean): String {
    val coordinate = coordinateLabel()
    val state = when (disc) {
        Disc.BLACK -> "黒石"
        Disc.WHITE -> "白石"
        Disc.EMPTY -> if (legal) "合法手" else "空き"
    }
    return "$coordinate、$state"
}
