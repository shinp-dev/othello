package com.example.othello

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaDangerButton
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.analysis.edax.EdaxDataManager
import com.example.othello.analysis.edax.EdaxSettingsStore
import com.example.othello.analysis.edax.ProductionAnalysisEngine
import com.example.othello.game.CanonicalMoves
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.Position
import com.example.othello.profile.AccountDeletionRepository
import com.example.othello.records.GameRecord
import com.example.othello.records.GameRecordRepository
import com.example.othello.records.FinishReason
import com.example.othello.records.MatchResult
import com.example.othello.review.ReviewSession
import com.example.othello.review.AnalysisRequestGuard
import com.example.othello.review.ReviewInput
import com.example.othello.records.LocalGameRecord
import com.example.othello.records.LocalGameRecordReadResult
import com.example.othello.records.LocalGameRecordStore
import com.example.othello.records.LocalRecordType
import com.example.othello.records.toLocalCopy
import com.example.othello.research.ResearchMove
import com.example.othello.research.ResearchMoveKind
import com.example.othello.research.ResearchParticipationRepository
import com.example.othello.research.ResearchParticipationStatus
import com.example.othello.research.ResearchPositionRepository
import com.example.othello.research.ResearchPositionResult
import com.example.othello.research.ResearchUnavailableReason
import com.example.othello.research.researchPositionToken
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RecordsScreen(
    userId: String,
    repository: GameRecordRepository,
    onBack: () -> Unit,
    onReview: (GameRecord) -> Unit,
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<GameRecord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) {
        runCatching { repository.recent(userId, 50) }
            .onSuccess { records = it }
            .onFailure { error = it.message ?: context.getString(R.string.records_load_failed) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page), verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section)) {
        ScreenHeader(appString(R.string.records_title), onBack)
        when {
            error != null -> Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
            records == null -> Text(appString(R.string.loading))
            records!!.isEmpty() -> Text(appString(R.string.records_none))
            else -> records!!.forEach { record ->
                val localIsBlack = record.players.first() == userId
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${record.result.labelFor(localIsBlack, context)} / ${appString(if (localIsBlack) R.string.black else R.string.white)}")
                        Text("${record.finishReason.userLabel(context)} / ${formatDate(record.finishedAtEpochMillis)}")
                        Text("${appString(R.string.move_count, record.moves.size)} / ${CanonicalMoves.encode(record.moves)}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onReview(record) }) { Text(appString(R.string.open_record)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReviewScreen(
    record: GameRecord,
    dataManager: EdaxDataManager,
    settingsStore: EdaxSettingsStore,
    engine: ProductionAnalysisEngine,
    researchParticipationRepository: ResearchParticipationRepository,
    researchPositionRepository: ResearchPositionRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val analysisNotRun = appString(R.string.analysis_not_run)
    val engineUnavailable = appString(R.string.edax_engine_unavailable)
    val analysisDataNotSet = appString(R.string.analysis_data_not_set)
    val analyzing = appString(R.string.analyzing)
    val analysisCancelled = appString(R.string.analysis_cancelled)
    val review = remember(record.matchId) { ReviewSession(record) }
    val analysisGuard = remember(record.matchId) { AnalysisRequestGuard() }
    var revision by remember { mutableIntStateOf(0) }
    var analysisRun by remember { mutableIntStateOf(0) }
    var analysisRequested by remember { mutableStateOf(false) }
    var analysisRunning by remember { mutableStateOf(false) }
    var showEvaluations by remember { mutableStateOf(true) }
    var analysisResult by remember { mutableStateOf<AnalysisResult?>(null) }
    var analysisMessage by remember { mutableStateOf(analysisNotRun) }
    val state = remember(revision) { review.current }
    val dataStatus = remember(revision, analysisRun) { dataManager.commonDataStatus() }
    val reviewSettings = remember(revision, analysisRun) { settingsStore.reviewAnalysisSettings() }
    val settings = remember(revision, analysisRun) { dataManager.analysisSettings(reviewSettings) }
    val positionKey = state.stateHash()

    LaunchedEffect(
        positionKey,
        analysisRequested,
        analysisRun,
        dataStatus.evaluationData?.sha256,
        dataStatus.openingBook?.sha256,
        reviewSettings.level,
        reviewSettings.timePerCandidateMs,
    ) {
        engine.cancel()
        val requestToken = analysisGuard.begin(positionKey)
        analysisRunning = false
        analysisResult = null
        if (!analysisRequested) return@LaunchedEffect
        when {
            !dataStatus.nativeAvailable -> analysisMessage = engineUnavailable
            dataStatus.evaluationData == null -> analysisMessage = analysisDataNotSet
            else -> {
                analysisRunning = true
                analysisMessage = analyzing
                val requestedPosition = positionKey
                try {
                    val result = review.analyze(engine, settings)
                    if (analysisGuard.isCurrent(requestToken, review.current.stateHash()) && review.current.stateHash() == requestedPosition) {
                        analysisResult = result
                        analysisMessage = localizeUserMessage(context, result.message)
                            ?: context.getString(R.string.analysis_complete, result.evaluations.size)
                    }
                } catch (_: CancellationException) {
                    // A newer ply/variation or explicit cancel owns the UI now.
                } finally {
                    if (analysisGuard.isCurrent(requestToken, review.current.stateHash()) && review.current.stateHash() == requestedPosition) {
                        analysisRunning = false
                    }
                }
            }
        }
    }
    DisposableEffect(engine) {
        onDispose {
            analysisGuard.invalidate()
            engine.cancel()
        }
    }

    val visibleEvaluations = if (showEvaluations) analysisResult?.evaluations.orEmpty() else emptyList()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page), verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact)) {
        ScreenHeader(appString(R.string.review_title), onBack)
        Text("${record.result.userLabel(context)} / ${record.finishReason.userLabel(context)} / ${formatDate(record.finishedAtEpochMillis)}")
        Text(appString(R.string.ply_variation, review.cursor, review.mainLineLastPly, if (review.isInVariation) appString(R.string.variation_suffix) else ""))
        ReviewBoard(state, review.isInVariation, visibleEvaluations.associateBy { it.move }) { position ->
            if (review.playVariation(position)) revision++
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { review.seek(0); revision++ }, enabled = !review.isInVariation) { Text(appString(R.string.first)) }
            OutlinedButton(onClick = { review.previous(); revision++ }, enabled = !review.isInVariation) { Text(appString(R.string.previous)) }
            OutlinedButton(onClick = { review.next(); revision++ }, enabled = !review.isInVariation) { Text(appString(R.string.next)) }
            OutlinedButton(onClick = { review.seek(review.mainLineLastPly); revision++ }, enabled = !review.isInVariation) { Text(appString(R.string.last)) }
        }
        Slider(
            value = review.cursor.toFloat(),
            onValueChange = { review.seek(it.toInt()); revision++ },
            valueRange = 0f..review.mainLineLastPly.coerceAtLeast(1).toFloat(),
            steps = (review.mainLineLastPly - 1).coerceAtLeast(0),
            enabled = !review.isInVariation,
        )
        if (!review.isInVariation) {
            OutlinedButton(onClick = { review.beginVariation(); revision++ }, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.start_variation)) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { review.cancelVariation(); revision++ }, modifier = Modifier.weight(1f)) { Text(appString(R.string.cancel_variation)) }
                OutlinedButton(onClick = { review.saveVariationAndReturn(); revision++ }, modifier = Modifier.weight(1f)) { Text(appString(R.string.save_to_game)) }
            }
        }
        OutlinedButton(
            onClick = {
                if (analysisRunning) {
                    analysisRequested = false
                    engine.cancel()
                    analysisMessage = analysisCancelled
                } else {
                    analysisRequested = true
                    analysisRun++
                    showEvaluations = true
                }
            },
            enabled = dataStatus.nativeAvailable,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(appString(if (analysisRunning) R.string.cancel_analysis else R.string.analyze_all_legal_moves)) }
        if (analysisResult != null) {
            OutlinedButton(onClick = { showEvaluations = !showEvaluations }, modifier = Modifier.fillMaxWidth()) {
                Text(appString(if (showEvaluations) R.string.hide_evaluations else R.string.show_evaluations))
            }
        }
        Text(analysisMessage)
        if (analysisResult?.available == true) {
            Text(appString(R.string.evaluation_note), style = MaterialTheme.typography.bodySmall)
            visibleEvaluations.forEach { evaluation ->
                val coordinate = evaluation.move.coordinateLabel()
                Text("$coordinate  ${formatEvaluation(evaluation.score.value)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        ResearchReviewPanel(
            state = state,
            participationRepository = researchParticipationRepository,
            positionRepository = researchPositionRepository,
        )
            Text(appString(R.string.saved_variations, review.currentVariations.size), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResearchReviewPanel(
    state: GameState,
    participationRepository: ResearchParticipationRepository,
    positionRepository: ResearchPositionRepository,
) {
    val rootToken = remember(state.stateHash()) { state.researchPositionToken() }
    var token by remember(rootToken) { mutableStateOf(rootToken) }
    var history by remember(rootToken) { mutableStateOf<List<String>>(emptyList()) }
    var status by remember(rootToken) { mutableStateOf<ResearchParticipationStatus?>(null) }
    var result by remember(rootToken) { mutableStateOf<ResearchPositionResult?>(null) }
    var loading by remember(rootToken) { mutableStateOf(false) }

    LaunchedEffect(rootToken, token) {
        loading = true
        result = null
        status = runCatching { participationRepository.status() }.getOrNull()
        result = if (status?.canViewResearchData == true) {
            positionRepository.getPosition(token)
        } else {
            ResearchPositionResult.Unavailable(ResearchUnavailableReason.NOT_ELIGIBLE)
        }
        loading = false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(appString(R.string.research_data), style = MaterialTheme.typography.titleMedium)
            val currentStatus = status
            when {
                currentStatus == null -> Text(appString(R.string.research_checking_status))
                !currentStatus.canViewResearchData -> ResearchEligibilityMessage(currentStatus)
                loading -> Text(appString(R.string.research_loading))
                result is ResearchPositionResult.Available -> {
                    val available = (result as ResearchPositionResult.Available).position
                    Text(appString(R.string.research_choice_tendency, available.uniqueContributors))
                    available.moves.forEach { move ->
                        ResearchMoveRow(move) {
                            val child = move.childPositionToken ?: return@ResearchMoveRow
                            history = history + token
                            token = child
                        }
                    }
                    available.other?.let { move -> ResearchMoveRow(move, onExplore = null) }
                    if (history.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            token = history.last()
                            history = history.dropLast(1)
                        }) { Text(appString(R.string.parent_position)) }
                    }
                }
                result is ResearchPositionResult.Unavailable -> {
                    val unavailable = (result as ResearchPositionResult.Unavailable).reason
                    Text(
                        when (unavailable) {
                            ResearchUnavailableReason.INSUFFICIENT_SAMPLE -> appString(R.string.research_insufficient)
                            ResearchUnavailableReason.NO_PUBLISHED_GENERATION -> appString(R.string.research_preparing)
                            ResearchUnavailableReason.UNSUPPORTED_SEGMENT -> appString(R.string.research_unavailable)
                            else -> appString(R.string.research_unavailable)
                        },
                    )
                }
                result is ResearchPositionResult.Failed ->
                    Text(appString(R.string.research_failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ResearchEligibilityMessage(status: ResearchParticipationStatus) {
    when {
        status.reconsentRequired -> Text(appString(R.string.research_reconsent_required))
        !status.participationOn -> Text(appString(R.string.research_off_unavailable))
        !status.collectionEnabled -> Text(appString(R.string.research_not_ready))
        status.qualifyingGameCount < status.requiredGameCount -> {
            val remaining = (status.requiredGameCount - status.qualifyingGameCount).coerceAtLeast(0)
            Text(appString(R.string.research_remaining, status.windowDays, remaining))
        }
        else -> Text(appString(R.string.research_cannot_display))
    }
}

@Composable
private fun ResearchMoveRow(move: ResearchMove, onExplore: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (move.kind == ResearchMoveKind.OTHER) appString(R.string.other_move) else move.coordinate?.lowercase(Locale.ROOT) ?: appString(R.string.move_fallback),
                modifier = Modifier.weight(1f),
            )
            Text(formatResearchPercent(move.choiceRate))
            onExplore?.let {
                OutlinedButton(onClick = it, enabled = move.canExplore) { Text(appString(R.string.next)) }
            }
        }
        Text(
            appString(R.string.research_move_result, formatResearchPercent(move.winRate), formatResearchPercent(move.drawRate), formatResearchPercent(move.lossRate)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatResearchPercent(value: Double): String =
    String.format(Locale.ROOT, "%.1f%%", value.coerceIn(0.0, 1.0) * 100.0)

@Composable
internal fun AccountDeletionScreen(
    repository: AccountDeletionRepository,
    onBack: () -> Unit,
    onRequested: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(appString(R.string.account_delete_title), onBack)
        Text(appString(R.string.account_delete_description))
        Text(appString(R.string.account_delete_warning), style = MaterialTheme.typography.bodySmall)
        when {
            requested -> Text(appString(R.string.delete_request_received))
            !confirmed -> OutlinedButton(onClick = { confirmed = true }) { Text(appString(R.string.proceed_delete)) }
            else -> ChanrivaDangerButton(onClick = {
                scope.launch {
                    runCatching { repository.requestDeletion() }
                        .onSuccess { requested = true; error = null; onRequested() }
                        .onFailure { error = it.message ?: context.getString(R.string.delete_request_failed) }
                }
            }) { Text(appString(R.string.send_delete_request)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    ChanrivaScreenHeader(title, onBack, backLabel = appString(R.string.back))
}

@Composable
private fun ReviewBoard(
    state: GameState,
    variationEnabled: Boolean,
    evaluations: Map<Position, com.example.othello.analysis.api.MoveEvaluation>,
    onMove: (Position) -> Unit,
) {
    val context = LocalContext.current
    val bestScore = evaluations.maxOfOrNull { it.value.score.value }
    CoordinateBoard { position, cellModifier ->
        val disc = state.board[position]
        val legal = variationEnabled && position in state.legalMoves
        val evaluation = evaluations[position]
        val isBestMove = evaluation != null && evaluation.score.value == bestScore
        Box(
            cellModifier
                .border(
                    if (isBestMove) 1.dp else 0.dp,
                    if (isBestMove) ChanrivaColors.evaluation else androidx.compose.ui.graphics.Color.Transparent,
                )
                .semantics {
                    contentDescription = buildString {
                        append("${position.coordinateLabel()}、")
                        append(when (disc) {
                            Disc.BLACK -> context.getString(R.string.black_stone)
                            Disc.WHITE -> context.getString(R.string.white_stone)
                            Disc.EMPTY -> if (position in state.legalMoves) context.getString(R.string.legal_move) else context.getString(R.string.empty_square)
                        })
                        evaluation?.let { append(context.getString(R.string.evaluation_value, formatEvaluation(it.score.value))) }
                    }
                }
                .clickable(enabled = legal) { onMove(position) },
            contentAlignment = Alignment.Center,
        ) {
            if (disc != Disc.EMPTY) Box(
                Modifier
                    .size(34.dp)
                    .background(if (disc == Disc.BLACK) ChanrivaColors.blackDisc else ChanrivaColors.whiteDisc, CircleShape)
                    .border(1.dp, ChanrivaColors.discOutline, CircleShape),
            )
            else if (evaluation != null) {
                val score = evaluation.score
                Text(formatEvaluation(score.value), color = if (isBestMove) ChanrivaColors.evaluation else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            } else if (legal) Box(Modifier.size(10.dp).background(ChanrivaColors.legalMove, CircleShape))
        }
    }
}

private fun formatEvaluation(value: Int): String = if (value > 0) "+$value" else value.toString()

private fun MatchResult.labelFor(localIsBlack: Boolean, context: android.content.Context? = null): String {
    val localWon = (this == MatchResult.BLACK_WIN && localIsBlack) || (this == MatchResult.WHITE_WIN && !localIsBlack)
    return when {
        this == MatchResult.DRAW -> context?.getString(R.string.result_draw) ?: "引き分け"
        localWon -> context?.getString(R.string.result_win) ?: "勝ち"
        else -> context?.getString(R.string.result_loss) ?: "負け"
    }
}

private fun MatchResult.userLabel(context: android.content.Context? = null): String = when (this) {
    MatchResult.BLACK_WIN -> context?.getString(R.string.black_win) ?: "黒勝ち"
    MatchResult.WHITE_WIN -> context?.getString(R.string.white_win) ?: "白勝ち"
    MatchResult.DRAW -> context?.getString(R.string.result_draw) ?: "引き分け"
}

private fun FinishReason.userLabel(context: android.content.Context? = null): String = when (this) {
    FinishReason.NORMAL -> context?.getString(R.string.finish_normal) ?: "通常終局"
    FinishReason.RESIGNATION -> context?.getString(R.string.finish_resignation) ?: "投了"
    FinishReason.TIMEOUT -> context?.getString(R.string.finish_timeout) ?: "時間切れ"
    FinishReason.DISCONNECT -> context?.getString(R.string.finish_disconnect) ?: "切断"
    FinishReason.DISPUTED -> context?.getString(R.string.finish_disputed) ?: "結果不一致"
}

private fun formatDate(epochMillis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMillis))

@Composable
internal fun OnlineRecordsScreen(
    userId: String,
    repository: GameRecordRepository,
    localStore: LocalGameRecordStore,
    onBack: () -> Unit,
    onReview: (ReviewInput) -> Unit,
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<GameRecord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(userId, repository) {
        runCatching { repository.recent(userId, 50) }
            .onSuccess { records = it; error = null }
            .onFailure { error = it.message ?: context.getString(R.string.online_records_load_failed) }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
        ) {
            ScreenHeader(appString(R.string.online_records), onBack)
            Text(appString(R.string.online_records_note), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                records == null -> Text(appString(R.string.loading))
                records.orEmpty().isEmpty() -> Text(appString(R.string.online_records_none))
                else -> records.orEmpty().forEach { record ->
                    val localIsBlack = record.players.first() == userId
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${record.result.labelFor(localIsBlack, context)} / ${appString(if (localIsBlack) R.string.black else R.string.white)}")
                            Text("${record.finishReason.userLabel(context)} / ${formatDate(record.finishedAtEpochMillis)}")
                            Text("${appString(R.string.move_count, record.moves.size)} / ${CanonicalMoves.encode(record.moves)}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        onReview(
                                            ReviewInput(
                                                id = record.matchId,
                                                moves = record.moves,
                                                title = context.getString(R.string.online_records),
                                                result = record.result,
                                                finishReason = record.finishReason,
                                                finishedAtEpochMillis = record.finishedAtEpochMillis,
                                            ),
                                        )
                                    },
                                ) { Text(appString(R.string.open_record)) }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching { localStore.save(record.toLocalCopy(userId)) }
                                                .onSuccess { snackbar.showSnackbar(context.getString(R.string.saved_to_device)) }
                                                .onFailure { snackbar.showSnackbar(it.message ?: context.getString(R.string.save_to_device_failed)) }
                                        }
                                    },
                                ) { Text(appString(R.string.save_to_device)) }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun OfflineRecordsScreen(
    localStore: LocalGameRecordStore,
    onBack: () -> Unit,
    onReview: (ReviewInput) -> Unit,
) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<LocalGameRecord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var readWarning by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<LocalGameRecord?>(null) }
    var memoTarget by remember { mutableStateOf<LocalGameRecord?>(null) }
    var memoDraft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            runCatching { localStore.listResult(50) }
                .onSuccess { result: LocalGameRecordReadResult ->
                    records = result.records
                    readWarning = result.corruptLineCount.takeIf { it > 0 }?.let {
                        if (result.recoveryCompleted) {
                            context.getString(R.string.offline_records_warning_quarantine, it)
                        } else {
                            context.getString(R.string.offline_records_warning_recovery, it)
                        }
                    }
                    error = null
                }
                .onFailure { error = it.message ?: context.getString(R.string.offline_records_load_failed) }
        }
    }

    LaunchedEffect(localStore) { reload() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ScreenHeader(appString(R.string.offline_records), onBack)
        Text(appString(R.string.offline_records_note), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        readWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            records == null -> Text(appString(R.string.loading))
            records.orEmpty().isEmpty() -> Text(appString(R.string.offline_records_none))
            else -> records.orEmpty().forEach { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${record.type.displayLabel(context)} / ${record.result?.userLabel(context) ?: appString(R.string.research_record)}")
                        Text("${formatDate(record.createdAtEpochMillis)} / ${appString(R.string.move_count, record.moves.size)}")
                        Text(record.canonicalMoves, style = MaterialTheme.typography.bodySmall)
                        record.memo?.takeIf { it.isNotBlank() }?.let { memo ->
                            Text(memo.replace("\n", " "), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onReview(
                                        ReviewInput(
                                            id = record.localId,
                                            moves = record.moves,
                                            title = record.type.reviewTitle(context),
                                            result = record.result,
                                            finishReason = record.finishReason,
                                            finishedAtEpochMillis = record.createdAtEpochMillis,
                                            localRecordId = record.localId,
                                            localMemo = record.memo,
                                        ),
                                    )
                                },
                            ) { Text(appString(R.string.open_record)) }
                            TextButton(onClick = { memoTarget = record; memoDraft = record.memo.orEmpty() }) {
                                Text(appString(if (record.memo.isNullOrBlank()) R.string.add_memo else R.string.edit_memo))
                            }
                            OutlinedButton(onClick = { deleteTarget = record }) { Text(appString(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(appString(R.string.delete_offline_record_confirm)) },
            text = { Text(appString(R.string.irreversible)) },
            confirmButton = {
                ChanrivaDangerButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        runCatching { localStore.delete(target.localId) }
                            .onSuccess { reload() }
                            .onFailure { error = it.message ?: context.getString(R.string.offline_records_delete_failed) }
                    }
                }) { Text(appString(R.string.delete)) }
            },
            dismissButton = { OutlinedButton(onClick = { deleteTarget = null }) { Text(appString(R.string.cancel)) } },
        )
    }
    memoTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { memoTarget = null },
            title = { Text(appString(if (target.memo.isNullOrBlank()) R.string.add_memo else R.string.edit_memo)) },
            text = {
                TextField(
                    value = memoDraft,
                    onValueChange = { memoDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(appString(R.string.memo_about_record)) },
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                Button(onClick = {
                    memoTarget = null
                    scope.launch {
                        runCatching { localStore.updateMemo(target.localId, memoDraft) }
                            .onSuccess { reload() }
                            .onFailure { error = it.message ?: context.getString(R.string.memo_save_failed) }
                    }
                }) { Text(appString(R.string.save)) }
            },
            dismissButton = { OutlinedButton(onClick = { memoTarget = null }) { Text(appString(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun ReviewScreenV2(
    input: ReviewInput,
    review: ReviewSession,
    dataManager: EdaxDataManager,
    settingsStore: EdaxSettingsStore,
    engine: ProductionAnalysisEngine,
    localStore: LocalGameRecordStore,
    researchParticipationRepository: ResearchParticipationRepository?,
    researchPositionRepository: ResearchPositionRepository?,
    onBack: () -> Unit,
    onOpenCommonSettings: () -> Unit,
    onSaveMemo: ((String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val analysisNotStarted = appString(R.string.analysis_not_started)
    val edaxUnavailable = appString(R.string.edax_engine_unavailable)
    val analysisDataNotSet = appString(R.string.analysis_data_not_set)
    val analyzing = appString(R.string.analyzing)
    val analysisCancelled = appString(R.string.analysis_cancelled)
    val guard = remember(input.id) { AnalysisRequestGuard() }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var analysisRun by remember { mutableIntStateOf(0) }
    var requested by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var message by remember { mutableStateOf(analysisNotStarted) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var showMemoDialog by remember { mutableStateOf(false) }
    var memoDraft by remember { mutableStateOf(input.localMemo.orEmpty()) }
    var currentMemo by remember(input.id) { mutableStateOf(input.localMemo) }
    val state = remember(revision) { review.current }
    val status = remember(revision, analysisRun) { dataManager.commonDataStatus() }
    val reviewSettings = remember(revision, analysisRun) { settingsStore.reviewAnalysisSettings() }
    val settings = remember(revision, analysisRun) { dataManager.analysisSettings(reviewSettings) }
    val positionKey = state.stateHash()
    val analysisIssue = when {
        !status.nativeAvailable -> edaxUnavailable
        status.evaluationData == null -> analysisDataNotSet
        else -> null
    }

    LaunchedEffect(
        positionKey,
        requested,
        analysisRun,
        analysisIssue,
        reviewSettings.level,
        reviewSettings.timePerCandidateMs,
        status.evaluationData?.sha256,
        status.openingBook?.sha256,
    ) {
        engine.cancel()
        val token = guard.begin(positionKey)
        running = false
        result = null
        if (!requested) return@LaunchedEffect
        if (analysisIssue != null) {
            message = analysisIssue
            return@LaunchedEffect
        }
        running = true
        message = analyzing
        try {
            val analyzed = review.analyze(engine, settings)
            if (guard.isCurrent(token, review.current.stateHash())) {
                result = analyzed
                message = localizeUserMessage(context, analyzed.message)
                    ?: context.getString(R.string.legal_moves_analyzed, analyzed.evaluations.size)
            }
        } catch (_: CancellationException) {
        } finally {
            if (guard.isCurrent(token, review.current.stateHash())) running = false
        }
    }
    DisposableEffect(engine) { onDispose { guard.invalidate(); engine.cancel() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page), verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact)) {
        ScreenHeader(appString(R.string.review_title), onBack)
        Text(input.title)
        if (input.localRecordId != null && onSaveMemo != null) {
            currentMemo?.takeIf { it.isNotBlank() }?.let { memo ->
                Text(memo.replace("\n", " "), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            TextButton(onClick = { memoDraft = currentMemo.orEmpty(); showMemoDialog = true }) {
                Text(appString(if (currentMemo.isNullOrBlank()) R.string.add_memo else R.string.edit_memo))
            }
        }
        input.result?.let { resultValue ->
            Text(
                listOfNotNull(
                    resultValue.userLabel(context),
                    input.finishReason?.userLabel(context),
                    input.finishedAtEpochMillis?.let(::formatDate),
                ).joinToString(" / "),
            )
        }
        Text(appString(R.string.ply_variation, review.cursor, review.mainLineLastPly, if (review.isInVariation) appString(R.string.variation_suffix) else ""))
        ReviewBoard(state, review.isInVariation, result?.evaluations.orEmpty().associateBy { it.move }) { position ->
            if (review.playVariation(position)) revision++
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { review.seek(0); revision++ }, enabled = !review.isInVariation) { Text(appString(R.string.first)) }
            OutlinedButton(onClick = { review.previous(); revision++ }, enabled = !review.isInVariation) { Text(appString(R.string.previous)) }
            OutlinedButton(onClick = { review.next(); revision++ }, enabled = !review.isInVariation) { Text(appString(R.string.next)) }
            OutlinedButton(onClick = { review.seek(review.mainLineLastPly); revision++ }, enabled = !review.isInVariation) { Text(appString(R.string.last)) }
        }
        Slider(value = review.cursor.toFloat(), onValueChange = { review.seek(it.toInt()); revision++ }, valueRange = 0f..review.mainLineLastPly.coerceAtLeast(1).toFloat(), steps = (review.mainLineLastPly - 1).coerceAtLeast(0), enabled = !review.isInVariation)
        if (!review.isInVariation) {
            OutlinedButton(onClick = { review.beginVariation(); revision++ }, modifier = Modifier.fillMaxWidth()) { Text(appString(R.string.start_variation)) }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { review.cancelVariation(); revision++ }, modifier = Modifier.weight(1f)) { Text(appString(R.string.discard_variation)) }
                OutlinedButton(onClick = {
                    val completeLine = review.saveVariationAndReturn()
                    revision++
                    if (completeLine != null) scope.launch {
                        runCatching {
                            localStore.save(LocalGameRecord(java.util.UUID.randomUUID().toString(), completeLine, System.currentTimeMillis(), LocalRecordType.RESEARCH_LINE))
                        }.onSuccess { saveMessage = context.getString(R.string.research_record_saved) }.onFailure { saveMessage = it.message ?: context.getString(R.string.research_record_save_failed) }
                    }
                }, modifier = Modifier.weight(1f)) { Text(appString(R.string.save_variation_local)) }
            }
        }
        analysisIssue?.let { issue ->
            Text(issue, color = MaterialTheme.colorScheme.error)
            if (status.nativeAvailable || status.evaluationData == null) {
                OutlinedButton(onClick = onOpenCommonSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(appString(if (status.evaluationData == null) R.string.set_evaluation_data else R.string.open_analysis_settings))
                }
            }
        }
        OutlinedButton(
            onClick = { requested = !running; analysisRun++ },
            enabled = status.nativeAvailable && analysisIssue == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(appString(if (running) R.string.cancel_analysis else R.string.analyze_all_legal_moves)) }
        Text(message)
        saveMessage?.let { Text(it) }
        result?.evaluations.orEmpty().forEach { evaluation -> Text("${evaluation.move.coordinateLabel()} ${formatEvaluation(evaluation.score.value)}", style = MaterialTheme.typography.bodySmall) }
        if (researchParticipationRepository != null && researchPositionRepository != null) ResearchReviewPanel(state, researchParticipationRepository, researchPositionRepository)
    }
    if (showMemoDialog) {
        AlertDialog(
            onDismissRequest = { showMemoDialog = false },
            title = { Text(appString(if (currentMemo.isNullOrBlank()) R.string.add_memo else R.string.edit_memo)) },
            text = {
                TextField(value = memoDraft, onValueChange = { memoDraft = it }, minLines = 3, maxLines = 8)
            },
            confirmButton = {
                Button(onClick = {
                    showMemoDialog = false
                    currentMemo = memoDraft.trim().takeIf { it.isNotEmpty() }
                    onSaveMemo?.invoke(memoDraft)
                }) { Text(appString(R.string.save)) }
            },
            dismissButton = { OutlinedButton(onClick = { showMemoDialog = false }) { Text(appString(R.string.cancel)) } },
        )
    }
}

private fun LocalRecordType.displayLabel(context: android.content.Context? = null): String = when (this) {
    LocalRecordType.LOCAL_HUMAN -> context?.getString(R.string.human_record) ?: "対人"
    LocalRecordType.LOCAL_AI -> "AI"
    LocalRecordType.RESEARCH_LINE -> context?.getString(R.string.research_record) ?: "研究"
    LocalRecordType.ONLINE_SAVED -> context?.getString(R.string.online_records) ?: "オンライン対局"
}

private fun LocalRecordType.reviewTitle(context: android.content.Context? = null): String = when (this) {
    LocalRecordType.ONLINE_SAVED -> context?.getString(R.string.online_match_saved) ?: "オンライン対局（端末保存）"
    else -> context?.getString(R.string.offline_record) ?: "オフライン棋譜"
}
