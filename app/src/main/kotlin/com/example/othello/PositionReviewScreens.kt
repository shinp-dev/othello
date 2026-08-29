package com.example.othello

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.edax.EdaxDataManager
import com.example.othello.analysis.edax.EdaxSettingsStore
import com.example.othello.analysis.edax.ProductionAnalysisEngine
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaDangerButton
import com.example.othello.designsystem.ChanrivaNavigationRow
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.game.Board
import com.example.othello.game.Disc
import com.example.othello.game.Position
import com.example.othello.review.POSITION_IMPORT_PROMPT
import com.example.othello.review.POSITION_IMPORT_PROMPT_ENGLISH
import com.example.othello.review.PositionBoardEditor
import com.example.othello.review.PositionReviewAnalysisCompletion
import com.example.othello.review.PositionReviewAnalysisCoordinator
import com.example.othello.review.PositionReviewAnalysisStart
import com.example.othello.review.PositionImportError
import com.example.othello.review.PositionImportParser
import com.example.othello.review.PositionImportResult
import com.example.othello.review.PositionReviewRecord
import com.example.othello.review.PositionReviewSession
import com.example.othello.review.PositionReviewStartDecision
import com.example.othello.review.PositionReviewStartValidator
import com.example.othello.review.PositionReviewStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PositionReviewHomeScreen(
    store: PositionReviewStore,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (PositionReviewRecord) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<PositionReviewRecord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<PositionReviewRecord?>(null) }

    LaunchedEffect(reload) {
        error = null
        runCatching { store.list() }
            .onSuccess { records = it }
            .onFailure { error = context.getString(R.string.position_review_list_load_failed) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        ChanrivaScreenHeader(appString(R.string.position_review), onBack, backLabel = appString(R.string.back))
        ChanrivaNavigationRow(
            title = appString(R.string.new_position_review),
            supportingText = appString(R.string.position_review_supporting),
            onClick = onNew,
            emphasized = true,
        )
        Text(appString(R.string.saved_position_reviews), style = MaterialTheme.typography.titleMedium)
        when {
            error != null -> Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
            records == null -> Text(appString(R.string.loading))
            records!!.isEmpty() -> Text(appString(R.string.no_saved_position_reviews))
            else -> records!!.forEach { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOf(
                                appString(R.string.black_count, record.initialBoard.count(Disc.BLACK)),
                                appString(R.string.white_count, record.initialBoard.count(Disc.WHITE)),
                                appString(if (record.initialSideToMove == Disc.BLACK) R.string.black_to_move else R.string.white_to_move),
                            ).joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpen(record) }, modifier = Modifier.weight(1f)) {
                                Text(appString(R.string.open_position_review))
                            }
                            OutlinedButton(onClick = { deleteTarget = record }, modifier = Modifier.weight(1f)) {
                                Text(appString(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(appString(R.string.delete_position_review_title)) },
            text = { Text(target.title) },
            confirmButton = {
                ChanrivaDangerButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        runCatching { store.delete(target.id) }
                            .onSuccess { reload++ }
                            .onFailure { error = context.getString(R.string.position_review_delete_failed) }
                    }
                }) { Text(appString(R.string.delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteTarget = null }) { Text(appString(R.string.cancel)) }
            },
        )
    }
}

@Composable
internal fun PositionReviewInputScreen(
    onBack: () -> Unit,
    onStart: (Board, Disc, String) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val defaultTitle = remember { defaultPositionReviewTitle(context) }
    val llmPrompt = if (configuration.locales[0].language == "ja") {
        POSITION_IMPORT_PROMPT
    } else {
        POSITION_IMPORT_PROMPT_ENGLISH
    }
    var jsonText by remember { mutableStateOf("") }
    var board by remember { mutableStateOf<Board?>(null) }
    var selectedSide by remember { mutableStateOf<Disc?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var passConfirmation by remember { mutableStateOf(false) }

    fun startOrConfirmPass() {
        val currentBoard = board ?: return
        val side = selectedSide ?: return
        when (PositionReviewStartValidator.evaluate(currentBoard, side)) {
            is PositionReviewStartDecision.Ready -> onStart(currentBoard, side, defaultTitle)
            is PositionReviewStartDecision.RequiresPass -> passConfirmation = true
            PositionReviewStartDecision.Finished -> error = context.getString(R.string.position_cannot_start_finished)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ChanrivaScreenHeader(appString(R.string.new_position_review), onBack, backLabel = appString(R.string.back))
        Text(appString(R.string.llm_prompt_help))
        OutlinedButton(
            onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Chanriva position prompt", llmPrompt))
                message = context.getString(R.string.llm_prompt_copied)
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(appString(R.string.copy_llm_prompt)) }
        OutlinedTextField(
            value = jsonText,
            onValueChange = { jsonText = it },
            label = { Text(appString(R.string.position_json)) },
            minLines = 6,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                when (val parsed = PositionImportParser.parse(jsonText)) {
                    is PositionImportResult.Success -> {
                        board = parsed.position.board
                        selectedSide = null
                        error = null
                        message = context.getString(R.string.position_loaded)
                    }
                    is PositionImportResult.Failure -> {
                        error = positionImportErrorMessage(context, parsed.error)
                        message = null
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(appString(R.string.load_position_json)) }
        message?.let { Text(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        board?.let { currentBoard ->
            Text(appString(R.string.position_edit_help), style = MaterialTheme.typography.bodySmall)
            EditablePositionBoard(currentBoard) { position ->
                board = PositionBoardEditor.cycle(currentBoard, position)
                error = null
            }
            Text(
                "${appString(R.string.black_count, currentBoard.count(Disc.BLACK))} / " +
                    appString(R.string.white_count, currentBoard.count(Disc.WHITE)),
            )
            Text(appString(R.string.side_to_move), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedSide == Disc.BLACK) {
                    Button(onClick = { selectedSide = Disc.BLACK; error = null }, modifier = Modifier.weight(1f)) {
                        Text(appString(R.string.black_checked))
                    }
                } else {
                    OutlinedButton(onClick = { selectedSide = Disc.BLACK; error = null }, modifier = Modifier.weight(1f)) {
                        Text(appString(R.string.black_to_move))
                    }
                }
                if (selectedSide == Disc.WHITE) {
                    Button(onClick = { selectedSide = Disc.WHITE; error = null }, modifier = Modifier.weight(1f)) {
                        Text(appString(R.string.white_checked))
                    }
                } else {
                    OutlinedButton(onClick = { selectedSide = Disc.WHITE; error = null }, modifier = Modifier.weight(1f)) {
                        Text(appString(R.string.white_to_move))
                    }
                }
            }
            Button(
                onClick = ::startOrConfirmPass,
                enabled = selectedSide != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(appString(R.string.start_position_review)) }
        }
    }

    if (passConfirmation) {
        val side = requireNotNull(selectedSide)
        AlertDialog(
            onDismissRequest = { passConfirmation = false },
            title = { Text(appString(R.string.position_no_legal_moves_title)) },
            text = {
                Text(
                    appString(
                        R.string.position_no_legal_moves_message,
                        appString(if (side == Disc.BLACK) R.string.black else R.string.white),
                        appString(if (side.opponent() == Disc.BLACK) R.string.black_to_move else R.string.white_to_move),
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    passConfirmation = false
                    onStart(requireNotNull(board), side.opponent(), defaultTitle)
                }) { Text(appString(R.string.start_position_review)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { passConfirmation = false }) { Text(appString(R.string.cancel)) }
            },
        )
    }
}

@Composable
internal fun PositionReviewScreen(
    id: String,
    initialTitle: String,
    createdAtEpochMillis: Long,
    session: PositionReviewSession,
    store: PositionReviewStore,
    dataManager: EdaxDataManager,
    settingsStore: EdaxSettingsStore,
    engine: ProductionAnalysisEngine,
    onBack: () -> Unit,
    onOpenCommonSettings: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analysisCoordinator = remember(id) { PositionReviewAnalysisCoordinator() }
    var title by remember(id) { mutableStateOf(initialTitle) }
    var revision by remember { mutableIntStateOf(0) }
    var retryGeneration by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var message by remember { mutableStateOf(context.getString(R.string.analysis_not_started)) }
    var analysisFailed by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val state = remember(revision) { session.current }
    val dataStatus = remember(revision, retryGeneration) { dataManager.commonDataStatus() }
    val reviewSettings = remember(revision, retryGeneration) { settingsStore.reviewAnalysisSettings() }
    val settings = remember(revision, retryGeneration) { dataManager.analysisSettings(reviewSettings) }
    val positionKey = state.stateHash()
    val analysisIssue = when {
        !dataStatus.nativeAvailable -> context.getString(R.string.edax_engine_unavailable)
        dataStatus.evaluationData == null -> context.getString(R.string.analysis_data_not_set)
        else -> null
    }

    LaunchedEffect(
        positionKey,
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
        analysisFailed = false
        val start = analysisCoordinator.begin(state, settings)
        if (start == PositionReviewAnalysisStart.NoLegalMoves) {
            message = context.getString(R.string.legal_moves_analyzed, 0)
            return@LaunchedEffect
        }
        if (analysisIssue != null) {
            message = analysisIssue
            return@LaunchedEffect
        }

        when (start) {
            is PositionReviewAnalysisStart.Cached -> {
                result = start.result
                message = localizeUserMessage(context, start.result.message)
                    ?: context.getString(R.string.legal_moves_analyzed, start.result.evaluations.size)
            }
            is PositionReviewAnalysisStart.Analyze -> {
                val request = start.request
                running = true
                message = context.getString(R.string.analyzing)
                try {
                    val analyzed = request.execute(engine)
                    val currentSettings = dataManager.analysisSettings(settingsStore.reviewAnalysisSettings())
                    when (analysisCoordinator.complete(request, session.current, currentSettings, analyzed)) {
                        PositionReviewAnalysisCompletion.ACCEPTED -> {
                            result = analyzed
                            message = localizeUserMessage(context, analyzed.message)
                                ?: context.getString(R.string.legal_moves_analyzed, analyzed.evaluations.size)
                        }
                        PositionReviewAnalysisCompletion.FAILED -> {
                            analysisFailed = true
                            message = localizeUserMessage(context, analyzed.message)
                                ?: context.getString(R.string.unknown_reason)
                        }
                        PositionReviewAnalysisCompletion.STALE -> Unit
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
                    if (analysisCoordinator.isCurrent(request, session.current, currentSettings)) running = false
                }
            }
            PositionReviewAnalysisStart.NoLegalMoves -> Unit
        }
    }
    DisposableEffect(engine, analysisCoordinator) {
        onDispose {
            analysisCoordinator.clear()
            engine.cancel()
        }
    }

    fun invalidateAnalysis() {
        analysisCoordinator.invalidate()
        engine.cancel()
        running = false
        result = null
        analysisFailed = false
        message = context.getString(R.string.analysis_not_started)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ChanrivaScreenHeader(appString(R.string.position_review), onBack, backLabel = appString(R.string.back))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(80) },
            label = { Text(appString(R.string.position_review_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${appString(R.string.position_review_progress, session.cursor, session.lastIndex)} / " +
                appString(if (state.currentPlayer == Disc.BLACK) R.string.black_to_move else R.string.white_to_move),
        )
        ReviewBoard(state, true, result?.evaluations.orEmpty().associateBy { it.move }) { position ->
            if (session.play(position)) {
                invalidateAnalysis()
                revision++
            }
        }
        OutlinedButton(
            onClick = { session.reset(); invalidateAnalysis(); revision++ },
            enabled = session.canUndo,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(appString(R.string.return_to_initial_position)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { session.previous(); invalidateAnalysis(); revision++ },
                enabled = session.canUndo,
                modifier = Modifier.weight(1f),
            ) { Text(appString(R.string.previous)) }
            OutlinedButton(
                onClick = { session.next(); invalidateAnalysis(); revision++ },
                enabled = session.canRedo,
                modifier = Modifier.weight(1f),
            ) { Text(appString(R.string.next)) }
        }
        analysisIssue?.let { issue ->
            Text(issue, color = MaterialTheme.colorScheme.error)
            if (dataStatus.evaluationData == null) {
                OutlinedButton(onClick = onOpenCommonSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(appString(R.string.open_analysis_settings))
                }
            }
        }
        if (analysisIssue == null) Text(message)
        if (analysisFailed && analysisIssue == null && state.legalMoves.isNotEmpty()) {
            OutlinedButton(
                onClick = { invalidateAnalysis(); retryGeneration++ },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(appString(R.string.retry)) }
        }
        Button(
            onClick = {
                val normalizedTitle = title.trim().ifEmpty { context.getString(R.string.position_review) }
                scope.launch {
                    runCatching {
                        store.save(
                            session.toRecord(
                                id = id,
                                title = normalizedTitle,
                                createdAtEpochMillis = createdAtEpochMillis,
                                updatedAtEpochMillis = System.currentTimeMillis(),
                            ),
                        )
                    }.onSuccess {
                        title = normalizedTitle
                        onSaved(normalizedTitle)
                        saveMessage = context.getString(R.string.position_review_saved)
                    }.onFailure {
                        saveMessage = context.getString(R.string.position_review_save_failed)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(appString(R.string.save_position_review)) }
        saveMessage?.let { Text(it) }
    }
}

@Composable
private fun EditablePositionBoard(board: Board, onCycle: (Position) -> Unit) {
    val context = LocalContext.current
    CoordinateBoard { position, cellModifier ->
        val disc = board[position]
        Box(
            cellModifier
                .semantics {
                    contentDescription = "${position.coordinateLabel()}、" + when (disc) {
                        Disc.BLACK -> context.getString(R.string.black_stone)
                        Disc.WHITE -> context.getString(R.string.white_stone)
                        Disc.EMPTY -> context.getString(R.string.empty_square)
                    }
                }
                .clickable { onCycle(position) },
            contentAlignment = Alignment.Center,
        ) {
            if (disc != Disc.EMPTY) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(if (disc == Disc.BLACK) ChanrivaColors.blackDisc else ChanrivaColors.whiteDisc, CircleShape)
                        .border(1.dp, ChanrivaColors.discOutline, CircleShape),
                )
            }
        }
    }
}

private fun positionImportErrorMessage(context: android.content.Context, error: PositionImportError): String =
    context.getString(
        when (error) {
        PositionImportError.EMPTY_INPUT -> R.string.position_import_empty
        PositionImportError.INVALID_JSON -> R.string.position_import_invalid_json
        PositionImportError.INVALID_FORMAT -> R.string.position_import_invalid_format
        PositionImportError.INVALID_COORDINATE -> R.string.position_import_invalid_coordinate
        PositionImportError.DUPLICATE_BLACK -> R.string.position_import_duplicate_black
        PositionImportError.DUPLICATE_WHITE -> R.string.position_import_duplicate_white
        PositionImportError.OVERLAPPING_COORDINATE -> R.string.position_import_overlap
        PositionImportError.INVALID_COUNT -> R.string.position_import_invalid_count
        PositionImportError.BLACK_COUNT_MISMATCH -> R.string.position_import_black_count_mismatch
        PositionImportError.WHITE_COUNT_MISMATCH -> R.string.position_import_white_count_mismatch
        PositionImportError.TOO_MANY_DISCS -> R.string.position_import_too_many_discs
        },
    )

private fun defaultPositionReviewTitle(context: android.content.Context): String = context.getString(
    R.string.position_review_default_title,
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.now()),
)
