package com.example.othello

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.edax.EdaxDataManager
import com.example.othello.analysis.edax.EdaxSettingsStore
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.Position
import com.example.othello.theory.TheoryAnalysisCache
import com.example.othello.theory.TheoryAnalysisCacheKey
import com.example.othello.theory.TheoryAnalysisCompletion
import com.example.othello.theory.TheoryAnalysisCoordinator
import com.example.othello.theory.TheoryAnalysisStart
import com.example.othello.theory.TheoryCandidateMetrics
import com.example.othello.theory.TheoryExplorationSession
import com.example.othello.theory.TheoryMetricDefinition
import com.example.othello.theory.TheoryMetricEvaluator
import com.example.othello.theory.TheoryMetricRegistry
import com.example.othello.theory.TheorySessionPersistenceCoordinator
import com.example.othello.theory.TheorySessionSaveStatus
import com.example.othello.theory.TheorySessionStore
import java.util.concurrent.CancellationException

@Composable
internal fun TheoryExplorationScreen(
    sessionStore: TheorySessionStore,
    persistence: TheorySessionPersistenceCoordinator,
    analysisCache: TheoryAnalysisCache,
    dataManager: EdaxDataManager,
    settingsStore: EdaxSettingsStore,
    engine: AnalysisEngine,
    onBack: () -> Unit,
    onOpenCommonSettings: () -> Unit,
) {
    var session by remember(sessionStore) { mutableStateOf<TheoryExplorationSession?>(null) }

    LaunchedEffect(sessionStore, persistence) {
        val restored = runCatching { sessionStore.load() }
            .getOrNull()
            ?.let(TheoryExplorationSession::restore)
        val active = restored ?: TheoryExplorationSession.fresh()
        session = active
        persistence.enqueue(active.snapshot())
    }

    val active = session
    if (active == null) {
        Column(
            Modifier.fillMaxSize().padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
        ) {
            ChanrivaScreenHeader(
                appString(R.string.theory_exploration),
                onBack,
                backLabel = appString(R.string.back),
            )
            Text(appString(R.string.theory_session_loading))
        }
        return
    }

    TheoryExplorationContent(
        session = active,
        persistence = persistence,
        analysisCache = analysisCache,
        dataManager = dataManager,
        settingsStore = settingsStore,
        engine = engine,
        onBack = onBack,
        onOpenCommonSettings = onOpenCommonSettings,
    )
}

@Composable
private fun TheoryExplorationContent(
    session: TheoryExplorationSession,
    persistence: TheorySessionPersistenceCoordinator,
    analysisCache: TheoryAnalysisCache,
    dataManager: EdaxDataManager,
    settingsStore: EdaxSettingsStore,
    engine: AnalysisEngine,
    onBack: () -> Unit,
    onOpenCommonSettings: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val languageTag = configuration.locales[0].toLanguageTag()
    val analysisCoordinator = remember(session, analysisCache) { TheoryAnalysisCoordinator(analysisCache) }
    val saveState by persistence.state.collectAsState()
    var revision by remember(session) { mutableIntStateOf(0) }
    var retryGeneration by remember(session) { mutableIntStateOf(0) }
    var running by remember(session) { mutableStateOf(false) }
    var result by remember(session) { mutableStateOf<AnalysisResult?>(null) }
    var message by remember(session) { mutableStateOf("") }
    var analysisFailed by remember(session) { mutableStateOf(false) }
    var showContinuationPicker by remember(session) { mutableStateOf(false) }
    val state = remember(session, revision) { session.current }
    val positionIdentity = TheoryAnalysisCacheKey.positionIdentity(state)
    val metricValues = remember(positionIdentity) { TheoryMetricEvaluator.evaluateAll(state) }
    val selectedMetric = requireNotNull(TheoryMetricRegistry.find(session.selectedMetricId))
    val selectedMetricText = selectedMetric.text(languageTag)
    val dataStatus = remember(positionIdentity, retryGeneration) { dataManager.commonDataStatus() }
    val reviewSettings = remember(positionIdentity, retryGeneration) { settingsStore.reviewAnalysisSettings() }
    val settings = remember(positionIdentity, retryGeneration) { dataManager.analysisSettings(reviewSettings) }
    val analysisIssue = when {
        state.legalMoves.isEmpty() -> null
        !dataStatus.nativeAvailable -> context.getString(R.string.edax_engine_unavailable)
        dataStatus.evaluationData == null -> context.getString(R.string.analysis_data_not_set)
        else -> null
    }

    LaunchedEffect(
        positionIdentity,
        retryGeneration,
        analysisIssue,
        reviewSettings.level,
        reviewSettings.timePerCandidateMs,
        dataStatus.evaluationData?.sha256,
        dataStatus.openingBook?.sha256,
    ) {
        engine.cancel()
        running = false
        result = null
        message = ""
        analysisFailed = false

        when (val start = analysisCoordinator.begin(state, settings)) {
            is TheoryAnalysisStart.Cached -> {
                result = start.result
                message = context.getString(R.string.theory_cache_hit)
            }
            is TheoryAnalysisStart.Analyze -> {
                if (analysisIssue != null) {
                    message = analysisIssue
                    return@LaunchedEffect
                }
                val request = start.request
                running = true
                message = context.getString(R.string.analyzing)
                try {
                    val analyzed = request.execute(engine)
                    val currentSettings = dataManager.analysisSettings(settingsStore.reviewAnalysisSettings())
                    when (analysisCoordinator.complete(request, session.current, currentSettings, analyzed)) {
                        TheoryAnalysisCompletion.ACCEPTED -> {
                            result = analyzed
                            message = localizeUserMessage(context, analyzed.message)
                                ?: context.getString(R.string.legal_moves_analyzed, analyzed.evaluations.size)
                        }
                        TheoryAnalysisCompletion.FAILED -> {
                            analysisFailed = true
                            message = localizeUserMessage(context, analyzed.message)
                                ?: context.getString(R.string.unknown_reason)
                        }
                        TheoryAnalysisCompletion.STALE -> Unit
                    }
                } catch (_: CancellationException) {
                } catch (failure: Throwable) {
                    val currentSettings = dataManager.analysisSettings(settingsStore.reviewAnalysisSettings())
                    if (analysisCoordinator.isCurrent(request, session.current, currentSettings)) {
                        analysisFailed = true
                        message = localizeUserMessage(context, failure.message)
                            ?: context.getString(R.string.unknown_reason)
                    }
                } finally {
                    val currentSettings = dataManager.analysisSettings(settingsStore.reviewAnalysisSettings())
                    if (analysisCoordinator.isCurrent(request, session.current, currentSettings)) {
                        running = false
                    }
                }
            }
            TheoryAnalysisStart.NoLegalMoves -> {
                message = context.getString(R.string.theory_terminal)
            }
            TheoryAnalysisStart.Stale -> Unit
        }
    }

    DisposableEffect(engine, analysisCoordinator) {
        onDispose {
            analysisCoordinator.invalidate()
            engine.cancel()
        }
    }

    fun invalidateAnalysis() {
        analysisCoordinator.invalidate()
        engine.cancel()
        running = false
        result = null
        message = ""
        analysisFailed = false
    }

    fun mutatePosition(change: () -> Boolean) {
        if (change()) {
            invalidateAnalysis()
            revision++
            persistence.enqueue(session.snapshot())
        }
    }

    fun selectMetric(metric: TheoryMetricDefinition) {
        if (session.selectMetric(metric.id)) {
            revision++
            persistence.enqueue(session.snapshot())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ChanrivaScreenHeader(
            appString(R.string.theory_exploration),
            onBack,
            backLabel = appString(R.string.back),
        )
        Text(
            appString(if (state.currentPlayer == Disc.BLACK) R.string.black_to_move else R.string.white_to_move) +
                " / " + appString(R.string.theory_variation_count, session.continuations.size),
        )
        Text(
            appString(R.string.theory_board_legend, selectedMetricText.displayName),
            style = MaterialTheme.typography.bodySmall,
        )
        TheoryExplorationBoard(
            state = state,
            metrics = metricValues,
            selectedMetric = selectedMetric,
            evaluations = result?.evaluations.orEmpty().associateBy { it.move },
            running = running,
            onMove = { move -> mutatePosition { session.play(move) } },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { mutatePosition(session::goBack) },
                enabled = session.canGoBack,
                modifier = Modifier.weight(1f),
            ) { Text(appString(R.string.previous)) }
            OutlinedButton(
                onClick = {
                    if (session.continuations.size == 1) {
                        mutatePosition(session::goForward)
                    } else {
                        showContinuationPicker = true
                    }
                },
                enabled = session.continuations.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(appString(R.string.next)) }
        }

        val metrics = TheoryMetricRegistry.definitions
        val selectedIndex = metrics.indexOfFirst { it.id == selectedMetric.id }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { selectMetric(metrics[(selectedIndex - 1 + metrics.size) % metrics.size]) },
                modifier = Modifier.semantics {
                    contentDescription = context.getString(R.string.theory_previous_metric)
                },
            ) { Text("‹") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(selectedMetricText.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    selectedMetricText.directionLabel,
                    color = ChanrivaColors.accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedButton(
                onClick = { selectMetric(metrics[(selectedIndex + 1) % metrics.size]) },
                modifier = Modifier.semantics {
                    contentDescription = context.getString(R.string.theory_next_metric)
                },
            ) { Text("›") }
        }

        val visibleIssue = analysisIssue.takeIf { result == null }
        if (visibleIssue != null) {
            Text(visibleIssue, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onOpenCommonSettings, modifier = Modifier.fillMaxWidth()) {
                Text(
                    appString(
                        if (dataStatus.evaluationData == null) R.string.set_evaluation_data
                        else R.string.open_analysis_settings,
                    ),
                )
            }
        } else if (message.isNotBlank()) {
            Text(message)
        }
        if (analysisFailed && visibleIssue == null && state.legalMoves.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    invalidateAnalysis()
                    retryGeneration++
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(appString(R.string.retry)) }
        }
        if (saveState.status == TheorySessionSaveStatus.FAILED) {
            Text(appString(R.string.theory_session_save_failed), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { persistence.retry() }, modifier = Modifier.fillMaxWidth()) {
                Text(appString(R.string.retry_save))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(selectedMetricText.displayName, style = MaterialTheme.typography.titleMedium)
                Text(selectedMetricText.directionLabel, color = ChanrivaColors.accent)
                Text(selectedMetricText.shortDescription)
                Text(selectedMetricText.detailedDescription, style = MaterialTheme.typography.bodySmall)
                Text(selectedMetricText.rangeDescription, style = MaterialTheme.typography.bodySmall)
                Text(selectedMetricText.caution, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showContinuationPicker) {
        AlertDialog(
            onDismissRequest = { showContinuationPicker = false },
            title = { Text(appString(R.string.theory_choose_continuation)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    session.continuations.forEach { continuation ->
                        OutlinedButton(
                            onClick = {
                                showContinuationPicker = false
                                mutatePosition { session.selectContinuation(continuation.nodeId) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                appString(
                                    R.string.theory_continuation_move,
                                    continuation.move.coordinateLabel(),
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showContinuationPicker = false }) {
                    Text(appString(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TheoryExplorationBoard(
    state: GameState,
    metrics: Map<Position, TheoryCandidateMetrics>,
    selectedMetric: TheoryMetricDefinition,
    evaluations: Map<Position, com.example.othello.analysis.api.MoveEvaluation>,
    running: Boolean,
    onMove: (Position) -> Unit,
) {
    val context = LocalContext.current
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val metricName = selectedMetric.text(languageTag).displayName
    val legalMoves = state.legalMoves
    val bestScore = evaluations.maxOfOrNull { it.value.score.value }
    val cellTextStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp)

    CoordinateBoard { position, cellModifier ->
        val disc = state.board[position]
        val legal = position in legalMoves
        val evaluation = evaluations[position]
        val metricValue = metrics[position]?.value(selectedMetric.id)
        val isBestMove = evaluation != null && evaluation.score.value == bestScore
        val edaxText = when {
            evaluation != null -> formatTheoryEvaluation(evaluation.score.value)
            running -> "…"
            else -> "—"
        }
        val metricText = metricValue?.let(selectedMetric::format) ?: "—"
        val accessibleEdax = when {
            evaluation != null -> edaxText
            running -> context.getString(R.string.theory_value_pending)
            else -> context.getString(R.string.theory_value_unavailable)
        }
        Box(
            cellModifier
                .border(
                    if (isBestMove) 1.dp else 0.dp,
                    if (isBestMove) ChanrivaColors.evaluation else androidx.compose.ui.graphics.Color.Transparent,
                )
                .semantics {
                    contentDescription = if (legal) {
                        context.getString(
                            R.string.theory_candidate_description,
                            position.coordinateLabel(),
                            accessibleEdax,
                            metricName,
                            metricText,
                        )
                    } else {
                        "${position.coordinateLabel()}、" + when (disc) {
                            Disc.BLACK -> context.getString(R.string.black_stone)
                            Disc.WHITE -> context.getString(R.string.white_stone)
                            Disc.EMPTY -> context.getString(R.string.empty_square)
                        }
                    }
                }
                .clickable(enabled = legal) { onMove(position) },
            contentAlignment = Alignment.Center,
        ) {
            if (disc != Disc.EMPTY) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(
                            if (disc == Disc.BLACK) ChanrivaColors.blackDisc else ChanrivaColors.whiteDisc,
                            CircleShape,
                        )
                        .border(1.dp, ChanrivaColors.discOutline, CircleShape),
                )
            } else if (legal) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = edaxText,
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isBestMove) ChanrivaColors.evaluation else MaterialTheme.colorScheme.onSurface,
                        style = cellTextStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = metricText,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = cellTextStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private fun formatTheoryEvaluation(value: Int): String = if (value > 0) "+$value" else value.toString()
