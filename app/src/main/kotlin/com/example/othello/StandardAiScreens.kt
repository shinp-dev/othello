package com.example.othello

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.othello.analysis.api.StandardAiEngine
import com.example.othello.analysis.api.StandardAiLevel
import com.example.othello.analysis.api.StandardEvaluationAsset
import com.example.othello.analysis.api.StandardEvaluationPreparationPhase
import com.example.othello.analysis.api.standardCampaignAiConfig
import com.example.othello.analysis.edax.ProductionStandardCandidateProvider
import com.example.othello.analysis.edax.StandardEvaluationDataManager
import com.example.othello.designsystem.ChanrivaDangerButton
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.game.Disc
import com.example.othello.game.GameStatus
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchMode
import com.example.othello.records.LocalGameRecord
import com.example.othello.records.MatchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun StandardAiRoute(
    userId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as OthelloApplication
    val scope = rememberCoroutineScope()
    val dataManager = remember { StandardEvaluationDataManager(context) }
    val preparation = remember(dataManager) { StandardAiPreparationController(dataManager) }
    val preparationState by preparation.state.collectAsState()
    val progressStore = remember { StandardAiProgressStore(context) }
    var progress by remember(userId) { mutableStateOf(progressStore.progress(userId)) }
    var selectedLevelValue by rememberSaveable(userId) {
        mutableStateOf(progress.nextChallenge().value)
    }
    var activeLevelValue by rememberSaveable(userId) { mutableStateOf<Int?>(null) }
    val activeLevel = activeLevelValue?.let(StandardAiLevel::fromValue)

    LaunchedEffect(preparation) {
        if (preparation.state.value is StandardAiPreparationState.NotPrepared) preparation.prepare()
    }

    when (val state = preparationState) {
        StandardAiPreparationState.NotPrepared -> StandardAiPreparingScreen(
            phase = StandardEvaluationPreparationPhase.DOWNLOADING,
            onBack = onBack,
        )
        is StandardAiPreparationState.Preparing -> StandardAiPreparingScreen(
            phase = state.phase,
            onBack = onBack,
        )
        is StandardAiPreparationState.Failed -> StandardAiPreparationFailedScreen(
            onBack = onBack,
            onRetry = { scope.launch { preparation.prepare() } },
        )
        is StandardAiPreparationState.Ready -> if (activeLevel == null) {
            val selectedLevel = StandardAiLevel.fromValue(selectedLevelValue)
                .takeIf(progress::isUnlocked) ?: progress.nextChallenge()
            StandardAiLevelSelectionContent(
                progress = progress,
                selectedLevel = selectedLevel,
                onLevelSelected = { selectedLevelValue = it.value },
                onStart = { activeLevelValue = selectedLevel.value },
                onBack = onBack,
            )
        } else {
            StandardAiMatchScreen(
                userId = userId,
                level = activeLevel,
                evaluationData = state.asset,
                persistence = application.localGameRecordPersistence.coordinator,
                progressStore = progressStore,
                onProgressChanged = { progress = it },
                onReturnToSelection = { selectedLevel ->
                    selectedLevelValue = selectedLevel.value
                    activeLevelValue = null
                },
            )
        }
    }
}

@Composable
private fun StandardAiPreparingScreen(
    phase: StandardEvaluationPreparationPhase,
    onBack: () -> Unit,
) {
    StandardAiSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_ai_match),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            text = appString(R.string.standard_ai_preparing),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = appString(
                when (phase) {
                    StandardEvaluationPreparationPhase.DOWNLOADING -> R.string.standard_ai_preparing_download
                    StandardEvaluationPreparationPhase.EXTRACTING -> R.string.standard_ai_preparing_extract
                    StandardEvaluationPreparationPhase.VALIDATING -> R.string.standard_ai_preparing_validate
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StandardAiPreparationFailedScreen(
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    StandardAiSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_ai_match),
            onBack = onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            text = appString(R.string.standard_ai_preparation_failed),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(appString(R.string.retry))
        }
    }
}

@Composable
internal fun StandardAiLevelSelectionContent(
    progress: StandardAiProgress,
    selectedLevel: StandardAiLevel,
    onLevelSelected: (StandardAiLevel) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(ChanrivaSpacing.page),
        ) {
            ChanrivaScreenHeader(
                title = appString(R.string.standard_ai_match),
                onBack = onBack,
                backLabel = appString(R.string.back),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StandardOpponentSelectionPanel(
                    progress = progress,
                    selectedLevel = selectedLevel,
                    onLevelSelected = onLevelSelected,
                    onStart = onStart,
                )
            }
        }
    }
}

@Composable
private fun StandardAiMatchScreen(
    userId: String,
    level: StandardAiLevel,
    evaluationData: StandardEvaluationAsset,
    persistence: LocalGameRecordPersistenceCoordinator,
    progressStore: StandardAiProgressStore,
    onProgressChanged: (StandardAiProgress) -> Unit,
    onReturnToSelection: (StandardAiLevel) -> Unit,
) {
    val context = LocalContext.current
    val introStore = remember { StandardAiIntroStore(context) }
    val controller = remember(level) { LocalMatchController(LocalMatchMode.AI, Disc.BLACK) }
    val engine = remember(level) { StandardAiEngine(ProductionStandardCandidateProvider()) }
    val presentationEngine = rememberStandardPresentationEngine(level)
    val presentationState by presentationEngine.state.collectAsState()
    val rewardTracker = remember(level) { StandardMatchRewardTracker() }
    val coordinator = remember(controller, engine, level, evaluationData, presentationEngine, rewardTracker) {
        StandardLocalAiCoordinator(
            match = controller,
            engine = engine,
            config = standardCampaignAiConfig(level),
            evaluationData = evaluationData,
            onDecisionReady = { tension, opponentBestScore ->
                rewardTracker.recordDecision(tension, opponentBestScore)
                presentationEngine.accept(StandardPresentationEvent.DecisionReady(tension))
            },
        )
    }
    var viewState by remember { mutableStateOf(controller.viewState) }
    var observedMoveCount by remember(controller) { mutableStateOf(viewState.moves.size) }
    var matchGeneration by remember { mutableStateOf(0) }
    var confirmResign by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var handledRecordId by remember { mutableStateOf<String?>(null) }
    var showIntro by remember(userId, level) { mutableStateOf(!introStore.hasSeen(userId, level)) }
    var resultPresentation by remember(level) { mutableStateOf<StandardAiResultPresentation?>(null) }
    val saveStates by persistence.saveStates.collectAsState()

    DisposableEffect(controller, persistence) {
        val closeable = controller.observe { next ->
            viewState = next
            next.completedRecord?.let(persistence::enqueue)
        }
        onDispose { closeable.close() }
    }
    DisposableEffect(coordinator, presentationEngine) {
        onDispose {
            coordinator.cancel()
            presentationEngine.accept(StandardPresentationEvent.Reset)
        }
    }
    LaunchedEffect(showIntro) {
        if (showIntro) presentationEngine.accept(StandardPresentationEvent.OpponentAppeared)
    }
    LaunchedEffect(viewState.moves.size) {
        val currentMoveCount = viewState.moves.size
        if (currentMoveCount > observedMoveCount) {
            repeat(currentMoveCount - observedMoveCount) {
                presentationEngine.accept(StandardPresentationEvent.MovePlaced)
            }
        }
        observedMoveCount = currentMoveCount
    }
    LaunchedEffect(controller, matchGeneration, viewState.game, viewState.completedRecord) {
        if (viewState.aiDisc == viewState.game.currentPlayer && !viewState.aiThinking &&
            viewState.completedRecord == null && viewState.game.status is GameStatus.InProgress
        ) {
            try {
                coordinator.play()
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }
    LaunchedEffect(viewState.completedRecord?.localId) {
        val record = viewState.completedRecord ?: return@LaunchedEffect
        if (handledRecordId == record.localId) return@LaunchedEffect
        handledRecordId = record.localId

        val outcome = record.humanOutcome()
        val before = progressStore.progress(userId)
        val wasCleared = before.isCleared(level)
        val updated = progressStore.recordResult(
            userId = userId,
            level = level,
            humanWon = outcome == StandardAiHumanOutcome.WIN,
            undoUsed = viewState.undoUsed,
        )
        val firstClear = outcome == StandardAiHumanOutcome.WIN &&
            !viewState.undoUsed &&
            !wasCleared &&
            updated.isCleared(level)
        val unlockedLevel = if (firstClear) {
            level.next()?.takeIf { !before.isUnlocked(it) && updated.isUnlocked(it) }
        } else {
            null
        }
        val conquered = firstClear && level == StandardAiLevel.LV8 && updated.conquered
        val winReward = rewardTracker.classify(
            humanWon = outcome == StandardAiHumanOutcome.WIN,
            undoUsed = viewState.undoUsed,
        )
        val wildStageAwakened = firstClear && unlockedLevel == StandardAiLevel.LV5
        onProgressChanged(updated)
        resultPresentation = StandardAiResultPresentation(
            outcome = outcome,
            firstClear = firstClear,
            unlockedLevel = unlockedLevel,
            conquered = conquered,
            winReward = winReward,
            wildStageAwakened = wildStageAwakened,
        )
        presentationEngine.accept(
            StandardPresentationEvent.MatchFinished(
                outcome = outcome,
                firstClear = firstClear,
                conquered = conquered,
                winReward = winReward,
                wildStageAwakened = wildStageAwakened,
            ),
        )
    }

    fun dismissIntro() {
        introStore.markSeen(userId, level)
        showIntro = false
    }
    fun undoMove() {
        if (!viewState.canUndo || viewState.completedRecord != null) return
        coordinator.cancelForUndo()
        controller.undo()?.let { result ->
            result.invalidatedRecord?.let { persistence.discard(it.localId) }
            confirmResign = false
        }
    }
    fun resetMatch() {
        coordinator.cancel()
        presentationEngine.accept(StandardPresentationEvent.Reset)
        rewardTracker.reset()
        controller.reset()
        handledRecordId = null
        resultPresentation = null
        matchGeneration++
        confirmResign = false
    }
    fun requestExit() {
        if (viewState.completedRecord == null) {
            confirmExit = true
        } else {
            onReturnToSelection(level)
        }
    }

    BackHandler(onBack = ::requestExit)

    StandardAiSurface {
        ChanrivaScreenHeader(
            title = appString(R.string.standard_ai_level, level.value),
            onBack = ::requestExit,
            backLabel = appString(R.string.back),
        )
        ScoreHeader(viewState.game)
        StandardBoardEffectHost(presentationState) {
            StandardPassAwareBoard(viewState, controller)
        }
        Text(
            localMatchStatusText(viewState.message),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (viewState.aiThinking) {
            Text(appString(R.string.ai_thinking), modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        localizeUserMessage(context, viewState.error)?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (viewState.completedRecord == null) {
            OutlinedButton(
                onClick = ::undoMove,
                enabled = viewState.canUndo,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(appString(R.string.undo_move)) }
            OutlinedButton(
                onClick = { confirmResign = true },
                enabled = !viewState.aiThinking,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(appString(R.string.resign)) }
        }
    }
    if (showIntro) {
        StandardAiIntroDialog(level = level, onStart = ::dismissIntro)
    }
    resultPresentation?.let { presentation ->
        StandardAiResultDialog(
            level = level,
            presentation = presentation,
            onRetry = ::resetMatch,
            onChooseOpponent = onReturnToSelection,
            saveFailed = viewState.completedRecord?.let { record ->
                saveStates[record.localId]?.status == LocalRecordSaveStatus.FAILED
            } == true,
            onRetrySave = {
                viewState.completedRecord?.let { persistence.retry(it.localId) }
            },
        )
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(appString(R.string.standard_ai_exit_title)) },
            text = { Text(appString(R.string.standard_ai_exit_message)) },
            confirmButton = {
                ChanrivaDangerButton(
                    onClick = {
                        confirmExit = false
                        coordinator.cancel()
                        onReturnToSelection(level)
                    },
                ) { Text(appString(R.string.standard_ai_exit_action)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmExit = false }) {
                    Text(appString(R.string.continue_label))
                }
            },
        )
    }
    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            title = { Text(appString(R.string.resign_confirm_title)) },
            text = { Text(appString(R.string.local_resign_text)) },
            confirmButton = {
                ChanrivaDangerButton(
                    onClick = { confirmResign = false; controller.resign(Disc.BLACK) },
                ) { Text(appString(R.string.resign_action)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmResign = false }) {
                    Text(appString(R.string.continue_label))
                }
            },
        )
    }
}


private fun LocalGameRecord.humanOutcome(): StandardAiHumanOutcome {
    val completedResult = requireNotNull(result) { "completed Standard AI record requires a result" }
    val humanDisc = requireNotNull(playerDisc) { "completed Standard AI record requires playerDisc" }
    return when (completedResult) {
        MatchResult.DRAW -> StandardAiHumanOutcome.DRAW
        MatchResult.BLACK_WIN -> if (humanDisc == Disc.BLACK) StandardAiHumanOutcome.WIN else StandardAiHumanOutcome.LOSS
        MatchResult.WHITE_WIN -> if (humanDisc == Disc.WHITE) StandardAiHumanOutcome.WIN else StandardAiHumanOutcome.LOSS
    }
}

@Composable
private fun StandardAiSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
            content = content,
        )
    }
}
