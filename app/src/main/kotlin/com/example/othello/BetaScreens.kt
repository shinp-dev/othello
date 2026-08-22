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
    var records by remember { mutableStateOf<List<GameRecord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) {
        runCatching { repository.recent(userId, 50) }
            .onSuccess { records = it }
            .onFailure { error = it.message ?: "棋譜を取得できませんでした" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page), verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section)) {
        ScreenHeader("棋譜", onBack)
        when {
            error != null -> Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
            records == null -> Text("読み込み中…")
            records!!.isEmpty() -> Text("保存された棋譜はありません")
            else -> records!!.forEach { record ->
                val localIsBlack = record.players.first() == userId
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${record.result.labelFor(localIsBlack)} / ${if (localIsBlack) "黒" else "白"}")
                        Text("${record.finishReason.userLabel()} / ${formatDate(record.finishedAtEpochMillis)}")
                        Text("${record.moves.size}手 / ${CanonicalMoves.encode(record.moves)}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onReview(record) }) { Text("棋譜を開く") }
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
    val review = remember(record.matchId) { ReviewSession(record) }
    val analysisGuard = remember(record.matchId) { AnalysisRequestGuard() }
    var revision by remember { mutableIntStateOf(0) }
    var analysisRun by remember { mutableIntStateOf(0) }
    var analysisRequested by remember { mutableStateOf(false) }
    var analysisRunning by remember { mutableStateOf(false) }
    var showEvaluations by remember { mutableStateOf(true) }
    var analysisResult by remember { mutableStateOf<AnalysisResult?>(null) }
    var analysisMessage by remember { mutableStateOf("解析未実行") }
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
            !dataStatus.nativeAvailable -> analysisMessage = "Edax解析エンジンを利用できません"
            dataStatus.evaluationData == null -> analysisMessage = "解析用評価データが設定されていません"
            else -> {
                analysisRunning = true
                analysisMessage = "解析中…"
                val requestedPosition = positionKey
                try {
                    val result = review.analyze(engine, settings)
                    if (analysisGuard.isCurrent(requestToken, review.current.stateHash()) && review.current.stateHash() == requestedPosition) {
                        analysisResult = result
                        analysisMessage = result.message ?: "全${result.evaluations.size}合法手を解析しました"
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
        ScreenHeader("棋譜レビュー", onBack)
        Text("${record.result.userLabel()} / ${record.finishReason.userLabel()} / ${formatDate(record.finishedAtEpochMillis)}")
        Text("手数 ${review.cursor}/${review.mainLineLastPly}${if (review.isInVariation) " / 変化手順" else ""}")
        ReviewBoard(state, review.isInVariation, visibleEvaluations.associateBy { it.move }) { position ->
            if (review.playVariation(position)) revision++
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { review.seek(0); revision++ }, enabled = !review.isInVariation) { Text("最初") }
            OutlinedButton(onClick = { review.previous(); revision++ }, enabled = !review.isInVariation) { Text("前") }
            OutlinedButton(onClick = { review.next(); revision++ }, enabled = !review.isInVariation) { Text("次") }
            OutlinedButton(onClick = { review.seek(review.mainLineLastPly); revision++ }, enabled = !review.isInVariation) { Text("最後") }
        }
        Slider(
            value = review.cursor.toFloat(),
            onValueChange = { review.seek(it.toInt()); revision++ },
            valueRange = 0f..review.mainLineLastPly.coerceAtLeast(1).toFloat(),
            steps = (review.mainLineLastPly - 1).coerceAtLeast(0),
            enabled = !review.isInVariation,
        )
        if (!review.isInVariation) {
            OutlinedButton(onClick = { review.beginVariation(); revision++ }, modifier = Modifier.fillMaxWidth()) { Text("この局面から変化を開始") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { review.cancelVariation(); revision++ }, modifier = Modifier.weight(1f)) { Text("変化を取消") }
                OutlinedButton(onClick = { review.saveVariationAndReturn(); revision++ }, modifier = Modifier.weight(1f)) { Text("保存して実戦へ") }
            }
        }
        OutlinedButton(
            onClick = {
                if (analysisRunning) {
                    analysisRequested = false
                    engine.cancel()
                    analysisMessage = "解析をキャンセルしました"
                } else {
                    analysisRequested = true
                    analysisRun++
                    showEvaluations = true
                }
            },
            enabled = dataStatus.nativeAvailable,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (analysisRunning) "解析をキャンセル" else "全合法手を解析") }
        if (analysisResult != null) {
            OutlinedButton(onClick = { showEvaluations = !showEvaluations }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showEvaluations) "評価を隠す" else "評価を表示")
            }
        }
        Text(analysisMessage)
        if (analysisResult?.available == true) {
            Text("値は現在手番から見た予測終局石差です。exactは完全読み、bookはインポートしたBook値、その他は深さ依存の推定値です。", style = MaterialTheme.typography.bodySmall)
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
        Text("実戦棋譜は不変です。保存済み変化: ${review.currentVariations.size}", style = MaterialTheme.typography.bodySmall)
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
            Text("研究データ", style = MaterialTheme.typography.titleMedium)
            val currentStatus = status
            when {
                currentStatus == null -> Text("研究データの利用状態を確認中…")
                !currentStatus.canViewResearchData -> ResearchEligibilityMessage(currentStatus)
                loading -> Text("研究データを読み込み中…")
                result is ResearchPositionResult.Available -> {
                    val available = (result as ResearchPositionResult.Available).position
                    Text("参加プレイヤーの選択傾向（${available.uniqueContributors}人以上）")
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
                        }) { Text("親の局面へ戻る") }
                    }
                }
                result is ResearchPositionResult.Unavailable -> {
                    val unavailable = (result as ResearchPositionResult.Unavailable).reason
                    Text(
                        when (unavailable) {
                            ResearchUnavailableReason.INSUFFICIENT_SAMPLE -> "この局面には十分な研究データがまだありません"
                            ResearchUnavailableReason.NO_PUBLISHED_GENERATION -> "研究データは現在準備中です"
                            ResearchUnavailableReason.UNSUPPORTED_SEGMENT -> "この研究データは利用できません"
                            else -> "研究データを利用できません"
                        },
                    )
                }
                result is ResearchPositionResult.Failed ->
                    Text("研究データを取得できませんでした", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ResearchEligibilityMessage(status: ResearchParticipationStatus) {
    when {
        status.reconsentRequired -> Text("研究参加の再同意が必要です。設定から内容を確認してください")
        !status.participationOn -> Text("研究参加がOFFのため、研究データを表示できません")
        !status.collectionEnabled -> Text("研究データは現在準備中です")
        status.qualifyingGameCount < status.requiredGameCount -> {
            val remaining = (status.requiredGameCount - status.qualifyingGameCount).coerceAtLeast(0)
            Text("研究データを見るには、直近${status.windowDays}日であと${remaining}局の提供が必要です")
        }
        else -> Text("研究データを表示できません")
    }
}

@Composable
private fun ResearchMoveRow(move: ResearchMove, onExplore: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (move.kind == ResearchMoveKind.OTHER) "その他" else move.coordinate?.lowercase(Locale.ROOT) ?: "手",
                modifier = Modifier.weight(1f),
            )
            Text(formatResearchPercent(move.choiceRate))
            onExplore?.let {
                OutlinedButton(onClick = it, enabled = move.canExplore) { Text("次へ") }
            }
        }
        Text(
            "勝 ${formatResearchPercent(move.winRate)} / 引分 ${formatResearchPercent(move.drawRate)} / 負 ${formatResearchPercent(move.lossRate)}",
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
    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("アカウントを削除", onBack)
        Text("削除リクエスト後、信頼されたサーバー処理が認証情報・アカウント管理用の内部データ・レーティングを削除します。対戦相手の棋譜は匿名化して保持される場合があります。")
        Text("進行中の対局がある場合は受け付けません。受付後は安全のためログアウトし、処理完了まで再ログインできません。", style = MaterialTheme.typography.bodySmall)
        when {
            requested -> Text("削除リクエストを受け付けました")
            !confirmed -> OutlinedButton(onClick = { confirmed = true }) { Text("削除手続きへ進む") }
            else -> ChanrivaDangerButton(onClick = {
                scope.launch {
                    runCatching { repository.requestDeletion() }
                        .onSuccess { requested = true; error = null; onRequested() }
                        .onFailure { error = it.message ?: "削除リクエストを送信できませんでした" }
                }
            }) { Text("削除リクエストを送信") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    ChanrivaScreenHeader(title, onBack)
}

@Composable
private fun ReviewBoard(
    state: GameState,
    variationEnabled: Boolean,
    evaluations: Map<Position, com.example.othello.analysis.api.MoveEvaluation>,
    onMove: (Position) -> Unit,
) {
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
                            Disc.BLACK -> "黒石"
                            Disc.WHITE -> "白石"
                            Disc.EMPTY -> if (position in state.legalMoves) "合法手" else "空き"
                        })
                        evaluation?.let { append("、評価 ${formatEvaluation(it.score.value)}") }
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

private fun MatchResult.labelFor(localIsBlack: Boolean): String {
    val localWon = (this == MatchResult.BLACK_WIN && localIsBlack) || (this == MatchResult.WHITE_WIN && !localIsBlack)
    return when {
        this == MatchResult.DRAW -> "引き分け"
        localWon -> "勝ち"
        else -> "負け"
    }
}

private fun MatchResult.userLabel(): String = when (this) {
    MatchResult.BLACK_WIN -> "黒勝ち"
    MatchResult.WHITE_WIN -> "白勝ち"
    MatchResult.DRAW -> "引き分け"
}

private fun FinishReason.userLabel(): String = when (this) {
    FinishReason.NORMAL -> "通常終局"
    FinishReason.RESIGNATION -> "投了"
    FinishReason.TIMEOUT -> "時間切れ"
    FinishReason.DISCONNECT -> "切断"
    FinishReason.DISPUTED -> "結果不一致"
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
    var records by remember { mutableStateOf<List<GameRecord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(userId, repository) {
        runCatching { repository.recent(userId, 50) }
            .onSuccess { records = it; error = null }
            .onFailure { error = it.message ?: "オンライン棋譜を読み込めませんでした" }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
            verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
        ) {
            ScreenHeader("オンライン棋譜", onBack)
            Text("この画面では最新50局を表示します", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                records == null -> Text("読み込み中…")
                records.orEmpty().isEmpty() -> Text("オンライン棋譜はありません")
                else -> records.orEmpty().forEach { record ->
                    val localIsBlack = record.players.first() == userId
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${record.result.labelFor(localIsBlack)} / ${if (localIsBlack) "黒" else "白"}")
                            Text("${record.finishReason.userLabel()} / ${formatDate(record.finishedAtEpochMillis)}")
                            Text("${record.moves.size}手 / ${CanonicalMoves.encode(record.moves)}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        onReview(
                                            ReviewInput(
                                                id = record.matchId,
                                                moves = record.moves,
                                                title = "オンライン棋譜",
                                                result = record.result,
                                                finishReason = record.finishReason,
                                                finishedAtEpochMillis = record.finishedAtEpochMillis,
                                            ),
                                        )
                                    },
                                ) { Text("棋譜を開く") }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching { localStore.save(record.toLocalCopy(userId)) }
                                                .onSuccess { snackbar.showSnackbar("端末に保存しました") }
                                                .onFailure { snackbar.showSnackbar(it.message ?: "端末に保存できませんでした") }
                                        }
                                    },
                                ) { Text("端末に保存") }
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
    var records by remember { mutableStateOf<List<LocalGameRecord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var readWarning by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<LocalGameRecord?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            runCatching { localStore.listResult(50) }
                .onSuccess { result: LocalGameRecordReadResult ->
                    records = result.records
                    readWarning = result.corruptLineCount.takeIf { it > 0 }?.let {
                        if (result.recoveryCompleted) {
                            "一部のオフライン棋譜を読み込めませんでした（${it}件を隔離しました）"
                        } else {
                            "一部のオフライン棋譜を読み込めませんでした（${it}件の復旧待ち）"
                        }
                    }
                    error = null
                }
                .onFailure { error = it.message ?: "オフライン棋譜を読み込めませんでした" }
        }
    }

    LaunchedEffect(localStore) { reload() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ScreenHeader("オフライン棋譜", onBack)
        Text("この端末に保存した棋譜です", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        readWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            records == null -> Text("読み込み中…")
            records.orEmpty().isEmpty() -> Text("オフライン棋譜はありません")
            else -> records.orEmpty().forEach { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${record.type.displayLabel()} / ${record.result?.userLabel() ?: "研究棋譜"}")
                        Text("${formatDate(record.createdAtEpochMillis)} / ${record.moves.size}手")
                        Text(record.canonicalMoves, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    onReview(
                                        ReviewInput(
                                            id = record.localId,
                                            moves = record.moves,
                                            title = record.type.reviewTitle(),
                                            result = record.result,
                                            finishReason = record.finishReason,
                                            finishedAtEpochMillis = record.createdAtEpochMillis,
                                        ),
                                    )
                                },
                            ) { Text("棋譜を開く") }
                            OutlinedButton(onClick = { deleteTarget = record }) { Text("削除") }
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("オフライン棋譜を削除しますか？") },
            text = { Text("この操作は取り消せません。") },
            confirmButton = {
                ChanrivaDangerButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        runCatching { localStore.delete(target.localId) }
                            .onSuccess { reload() }
                            .onFailure { error = it.message ?: "オフライン棋譜を削除できませんでした" }
                    }
                }) { Text("削除する") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteTarget = null }) { Text("キャンセル") } },
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
) {
    val guard = remember(input.id) { AnalysisRequestGuard() }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var analysisRun by remember { mutableIntStateOf(0) }
    var requested by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var message by remember { mutableStateOf("解析は開始されていません") }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val state = remember(revision) { review.current }
    val status = remember(revision, analysisRun) { dataManager.commonDataStatus() }
    val reviewSettings = remember(revision, analysisRun) { settingsStore.reviewAnalysisSettings() }
    val settings = remember(revision, analysisRun) { dataManager.analysisSettings(reviewSettings) }
    val positionKey = state.stateHash()
    val analysisIssue = when {
        !status.nativeAvailable -> "Edaxを利用できません"
        status.evaluationData == null -> "評価データを設定してください"
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
        message = "解析中…"
        try {
            val analyzed = review.analyze(engine, settings)
            if (guard.isCurrent(token, review.current.stateHash())) {
                result = analyzed
                message = analyzed.message ?: "合法手 ${analyzed.evaluations.size} 件を解析しました"
            }
        } catch (_: CancellationException) {
        } finally {
            if (guard.isCurrent(token, review.current.stateHash())) running = false
        }
    }
    DisposableEffect(engine) { onDispose { guard.invalidate(); engine.cancel() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page), verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact)) {
        ScreenHeader("棋譜レビュー", onBack)
        Text(input.title)
        input.result?.let { resultValue ->
            Text(
                listOfNotNull(
                    resultValue.userLabel(),
                    input.finishReason?.userLabel(),
                    input.finishedAtEpochMillis?.let(::formatDate),
                ).joinToString(" / "),
            )
        }
        Text("手数 ${review.cursor}/${review.mainLineLastPly}${if (review.isInVariation) " / 変化" else ""}")
        ReviewBoard(state, review.isInVariation, result?.evaluations.orEmpty().associateBy { it.move }) { position ->
            if (review.playVariation(position)) revision++
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { review.seek(0); revision++ }, enabled = !review.isInVariation) { Text("最初") }
            OutlinedButton(onClick = { review.previous(); revision++ }, enabled = !review.isInVariation) { Text("前") }
            OutlinedButton(onClick = { review.next(); revision++ }, enabled = !review.isInVariation) { Text("次") }
            OutlinedButton(onClick = { review.seek(review.mainLineLastPly); revision++ }, enabled = !review.isInVariation) { Text("最後") }
        }
        Slider(value = review.cursor.toFloat(), onValueChange = { review.seek(it.toInt()); revision++ }, valueRange = 0f..review.mainLineLastPly.coerceAtLeast(1).toFloat(), steps = (review.mainLineLastPly - 1).coerceAtLeast(0), enabled = !review.isInVariation)
        if (!review.isInVariation) {
            OutlinedButton(onClick = { review.beginVariation(); revision++ }, modifier = Modifier.fillMaxWidth()) { Text("この局面から変化を開始") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { review.cancelVariation(); revision++ }, modifier = Modifier.weight(1f)) { Text("変化を破棄") }
                OutlinedButton(onClick = {
                    val completeLine = review.saveVariationAndReturn()
                    revision++
                    if (completeLine != null) scope.launch {
                        runCatching {
                            localStore.save(LocalGameRecord(java.util.UUID.randomUUID().toString(), completeLine, System.currentTimeMillis(), LocalRecordType.RESEARCH_LINE))
                        }.onSuccess { saveMessage = "研究棋譜をローカルに保存しました" }.onFailure { saveMessage = it.message ?: "研究棋譜を保存できませんでした" }
                    }
                }, modifier = Modifier.weight(1f)) { Text("変化をローカル棋譜に保存") }
            }
        }
        analysisIssue?.let { issue ->
            Text(issue, color = MaterialTheme.colorScheme.error)
            if (status.nativeAvailable || status.evaluationData == null) {
                OutlinedButton(onClick = onOpenCommonSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(if (status.evaluationData == null) "評価データを設定する" else "解析設定を開く")
                }
            }
        }
        OutlinedButton(
            onClick = { requested = !running; analysisRun++ },
            enabled = status.nativeAvailable && analysisIssue == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "解析をキャンセル" else "全合法手を解析") }
        Text(message)
        saveMessage?.let { Text(it) }
        result?.evaluations.orEmpty().forEach { evaluation -> Text("${evaluation.move.coordinateLabel()} ${formatEvaluation(evaluation.score.value)}", style = MaterialTheme.typography.bodySmall) }
        if (researchParticipationRepository != null && researchPositionRepository != null) ResearchReviewPanel(state, researchParticipationRepository, researchPositionRepository)
    }
}

private fun LocalRecordType.displayLabel(): String = when (this) {
    LocalRecordType.LOCAL_HUMAN -> "対人"
    LocalRecordType.LOCAL_AI -> "AI"
    LocalRecordType.RESEARCH_LINE -> "研究"
    LocalRecordType.ONLINE_SAVED -> "オンライン対局"
}

private fun LocalRecordType.reviewTitle(): String = when (this) {
    LocalRecordType.ONLINE_SAVED -> "オンライン対局（端末保存）"
    else -> "オフライン棋譜"
}
