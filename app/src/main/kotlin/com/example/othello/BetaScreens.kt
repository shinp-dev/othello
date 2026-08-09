package com.example.othello

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.EvaluationKind
import com.example.othello.analysis.edax.EdaxDataManager
import com.example.othello.analysis.edax.ProductionAnalysisEngine
import com.example.othello.credential.CredentialRepository
import com.example.othello.credential.FederationCredential
import com.example.othello.game.CanonicalMoves
import com.example.othello.game.Disc
import com.example.othello.game.GameState
import com.example.othello.game.Position
import com.example.othello.profile.AccountDeletionRepository
import com.example.othello.profile.Profile
import com.example.othello.profile.ProfileRepository
import com.example.othello.records.GameRecord
import com.example.othello.records.GameRecordRepository
import com.example.othello.records.MatchResult
import com.example.othello.review.ReviewSession
import com.example.othello.review.AnalysisRequestGuard
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProfileScreen(userId: String, repository: ProfileRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<Profile?>(null) }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(userId) {
        runCatching { repository.get(userId) }
            .onSuccess { profile = it; displayName = it.displayName }
            .onFailure { error = it.message ?: "プロフィールを取得できませんでした" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("プロフィール", onBack)
        profile?.let { current ->
            OutlinedTextField(displayName, { displayName = it.take(40) }, label = { Text("表示名") }, singleLine = true)
            Button(
                onClick = {
                    scope.launch {
                        runCatching { repository.updateDisplayName(userId, displayName.trim()) }
                            .onSuccess { profile = it; error = null }
                            .onFailure { error = it.message ?: "表示名を更新できませんでした" }
                    }
                },
                enabled = displayName.trim().isNotEmpty() && displayName.trim() != current.displayName,
            ) { Text("表示名を保存") }
            Text("Current Rating: ${current.currentRating}")
            Text("Peak Rating: ${current.peakRating}")
            Text("Stable Rating Band: ${current.stableRatingBand}")
            Text("連盟段級位: ${current.federationGrade ?: "未登録"}")
            Text("確認状態: ${current.federationVerificationStatus ?: "未申請"}")
            Text("公開プロフィールにメールアドレスや証明画像は表示されません", style = MaterialTheme.typography.bodySmall)
        } ?: Text("読み込み中…")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("棋譜", onBack)
        when {
            error != null -> Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
            records == null -> Text("読み込み中…")
            records!!.isEmpty() -> Text("保存された棋譜はありません")
            else -> records!!.forEach { record ->
                val localIsBlack = record.players.first() == userId
                val opponent = record.players.first { it != userId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${record.result.labelFor(localIsBlack)} / ${if (localIsBlack) "黒" else "白"}")
                        Text("相手: ${opponent.take(8)}…", style = MaterialTheme.typography.bodySmall)
                        Text("${record.finishReason} / ${formatDate(record.finishedAtEpochMillis)}")
                        Text("${record.moves.size} ply / ${CanonicalMoves.encode(record.moves)}", style = MaterialTheme.typography.bodySmall)
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
    engine: ProductionAnalysisEngine,
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
    val dataStatus = remember(revision, analysisRun) { dataManager.status() }
    val settings = remember(revision, analysisRun) { dataManager.analysisSettings() }
    val positionKey = state.stateHash()

    LaunchedEffect(
        positionKey,
        analysisRequested,
        analysisRun,
        dataStatus.enabled,
        dataStatus.evaluationData?.sha256,
        dataStatus.openingBook?.sha256,
        dataStatus.level,
    ) {
        engine.cancel()
        val requestToken = analysisGuard.begin(positionKey)
        analysisRunning = false
        analysisResult = null
        if (!analysisRequested) return@LaunchedEffect
        when {
            !dataStatus.enabled -> analysisMessage = "設定でEdax解析がOFFです"
            !dataStatus.nativeAvailable -> analysisMessage = "Edax native libraryを利用できません"
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader("Review", onBack)
        Text("${record.result} / ${record.finishReason} / ${formatDate(record.finishedAtEpochMillis)}")
        Text("ply ${review.cursor}/${review.mainLineLastPly}${if (review.isInVariation) " / variation" else ""}")
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
            Button(onClick = { review.beginVariation(); revision++ }, modifier = Modifier.fillMaxWidth()) { Text("この局面から変化を開始") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { review.cancelVariation(); revision++ }, modifier = Modifier.weight(1f)) { Text("変化を取消") }
                Button(onClick = { review.saveVariationAndReturn(); revision++ }, modifier = Modifier.weight(1f)) { Text("保存して実戦へ") }
            }
        }
        Button(
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
                val coordinate = "${('A'.code + evaluation.move.column).toChar()}${evaluation.move.row + 1}"
                Text("$coordinate  ${formatEvaluation(evaluation.score.value, evaluation.score.kind)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("実戦棋譜は不変です。保存済み変化: ${review.currentVariations.size}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CredentialScreen(repository: CredentialRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var credential by remember { mutableStateOf<FederationCredential?>(null) }
    var value by remember { mutableStateOf("") }
    var evidenceUri by remember { mutableStateOf<Uri?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { evidenceUri = it }
    LaunchedEffect(repository) {
        runCatching { repository.current() }
            .onSuccess { credential = it; if (it != null) value = it.value }
            .onFailure { message = it.message ?: "段級位情報を取得できませんでした" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("連盟段級位", onBack)
        Text("自己申告した段級位は、証明画像の審査完了まで未確認として表示されます")
        OutlinedTextField(value, { value = it.take(40) }, label = { Text("段級位") }, singleLine = true)
        Button(
            onClick = {
                busy = true
                scope.launch {
                    runCatching { repository.selfDeclare(value.trim()) }
                        .onSuccess { credential = it; message = "自己申告を保存しました" }
                        .onFailure { message = it.message ?: "自己申告を保存できませんでした" }
                    busy = false
                }
            },
            enabled = credential == null && value.trim().isNotEmpty() && !busy,
        ) { Text("自己申告を保存") }
        credential?.let { Text("状態: ${it.status}") }
        OutlinedButton(onClick = { picker.launch("image/*") }, enabled = credential != null && !busy) { Text("証明画像を選択") }
        Text(if (evidenceUri == null) "画像未選択" else "画像を選択済み（内容とファイル名は公開されません）")
        Button(
            onClick = {
                val selected = evidenceUri ?: return@Button
                val current = credential ?: return@Button
                busy = true
                scope.launch {
                    runCatching {
                        val mimeType = requireNotNull(context.contentResolver.getType(selected)) { "MIME typeを確認できません" }
                        require(mimeType in ALLOWED_EVIDENCE_MIME_TYPES) { "JPEG/PNG/WebPのみ選択できます" }
                        val bytes = requireNotNull(context.contentResolver.openInputStream(selected)).use {
                            it.readAtMost(MAX_EVIDENCE_BYTES + 1)
                        }
                        require(bytes.size <= MAX_EVIDENCE_BYTES) { "証明画像は5MB以下にしてください" }
                        val extension = when (mimeType) {
                            "image/jpeg" -> "jpg"
                            "image/png" -> "png"
                            else -> "webp"
                        }
                        val path = repository.uploadEvidence("evidence-${System.currentTimeMillis()}.$extension", mimeType, bytes)
                        repository.submitVerification(current, path)
                    }.onSuccess {
                        credential = it
                        evidenceUri = null
                        message = "審査申請を受け付けました"
                    }.onFailure { message = it.message ?: "審査申請に失敗しました" }
                    busy = false
                }
            },
            enabled = credential != null && evidenceUri != null && credential?.status?.name != "PENDING" && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "処理中…" else "画像をアップロードして審査申請") }
        message?.let { Text(it, color = if (it.contains("失敗") || it.contains("できません")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun AccountDeletionScreen(repository: AccountDeletionRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("アカウントを削除", onBack)
        Text("削除リクエスト後、信頼されたサーバー処理が認証情報・非公開プロフィール・証明画像を削除します。対戦相手の棋譜は匿名化して保持される場合があります。")
        Text("進行中の対局がある場合は受け付けません。処理完了までログインできる場合があります。", style = MaterialTheme.typography.bodySmall)
        when {
            requested -> Text("削除リクエストを受け付けました", color = MaterialTheme.colorScheme.primary)
            !confirmed -> OutlinedButton(onClick = { confirmed = true }) { Text("削除手続きへ進む") }
            else -> Button(onClick = {
                scope.launch {
                    runCatching { repository.requestDeletion() }
                        .onSuccess { requested = true; error = null }
                        .onFailure { error = it.message ?: "削除リクエストを送信できませんでした" }
                }
            }) { Text("削除リクエストを送信") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onBack) { Text("戻る") }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ReviewBoard(
    state: GameState,
    variationEnabled: Boolean,
    evaluations: Map<Position, com.example.othello.analysis.api.MoveEvaluation>,
    onMove: (Position) -> Unit,
) {
    Column(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF0D6B47)).padding(3.dp)) {
        repeat(8) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(8) { column ->
                    val position = Position(row, column)
                    val disc = state.board[position]
                    val legal = variationEnabled && position in state.legalMoves
                    Box(
                        Modifier.weight(1f).border(0.5.dp, Color(0xFF72AA8D)).clickable(enabled = legal) { onMove(position) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (disc != Disc.EMPTY) Box(Modifier.size(34.dp).background(if (disc == Disc.BLACK) Color(0xFF111514) else Color(0xFFF5F4ED), CircleShape))
                        else if (evaluations[position] != null) {
                            val score = requireNotNull(evaluations[position]).score
                            Text(formatEvaluation(score.value, score.kind), color = Color(0xFFFFE082), style = MaterialTheme.typography.labelSmall)
                        } else if (legal) Box(Modifier.size(10.dp).background(Color(0xFFB7E0C9), CircleShape))
                    }
                }
            }
        }
    }
}

private fun formatEvaluation(value: Int, kind: EvaluationKind): String {
    val signed = if (value > 0) "+$value" else value.toString()
    return when (kind) {
        EvaluationKind.EXACT -> "$signed exact"
        EvaluationKind.BOOK -> "$signed book"
        EvaluationKind.HEURISTIC -> "≈$signed"
    }
}

private fun InputStream.readAtMost(maximumBytes: Int): ByteArray {
    require(maximumBytes > 0)
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (output.size() < maximumBytes) {
        val count = read(buffer, 0, minOf(buffer.size, maximumBytes - output.size()))
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun MatchResult.labelFor(localIsBlack: Boolean): String {
    val localWon = (this == MatchResult.BLACK_WIN && localIsBlack) || (this == MatchResult.WHITE_WIN && !localIsBlack)
    return when {
        this == MatchResult.DRAW -> "引き分け"
        localWon -> "勝ち"
        else -> "負け"
    }
}

private fun formatDate(epochMillis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMillis))

private const val MAX_EVIDENCE_BYTES = 5 * 1024 * 1024
private val ALLOWED_EVIDENCE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
