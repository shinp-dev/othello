package com.example.othello

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.othello.analysis.edax.EdaxDataManager
import com.example.othello.analysis.edax.EdaxReleaseConstants
import com.example.othello.analysis.edax.ImportedAnalysisFile
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onMatchSettings: () -> Unit,
    onAnalysis: () -> Unit,
    onResearch: (() -> Unit)?,
    onAbout: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        SettingsHeader("設定", onBack)
        SettingsNavigationRow("対局時設定", onMatchSettings)
        SettingsNavigationRow("AI解析設定", onAnalysis)
        SettingsNavigationRow("研究参加", onResearch)
        if (onResearch == null) {
            Text("研究参加の設定にはログインが必要です", style = MaterialTheme.typography.bodySmall)
        }
        SettingsNavigationRow("このアプリについて", onAbout)
        BuildIdentityText()
    }
}

@Composable
internal fun MatchSettingsScreen(
    onBack: () -> Unit,
    audioSettings: AudioSettingsStore,
) {
    var timeWarningEnabled by remember { mutableStateOf(audioSettings.timeWarningEnabled) }
    var focusSoundEnabled by remember { mutableStateOf(audioSettings.focusSoundEnabled) }
    var focusSoundVolume by remember { mutableStateOf(audioSettings.focusSoundVolume) }
    var audioPreview by remember { mutableStateOf<AudioPreview?>(null) }
    var audioPreviewError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioController = remember { MatchAudioController(context) }

    LaunchedEffect(audioPreview) {
        val preview = audioPreview ?: return@LaunchedEffect
        kotlinx.coroutines.delay(preview.durationMillis)
        audioController.stopPreview()
        audioPreview = null
    }
    DisposableEffect(lifecycleOwner, audioController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                audioController.stopPreview()
                audioPreview = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            audioController.close()
        }
    }

    fun toggleAudioPreview(preview: AudioPreview) {
        audioPreviewError = false
        if (audioPreview == preview) {
            audioController.stopPreview()
            audioPreview = null
        } else if (audioController.startPreview(preview, focusSoundVolume)) {
            audioPreview = preview
        } else {
            audioPreview = null
            audioPreviewError = true
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        SettingsHeader("対局時設定", onBack)
        Text("対局中の音", style = MaterialTheme.typography.titleMedium)
        SettingSwitchRow(
            title = "時間警告音",
            checked = timeWarningEnabled,
            onCheckedChange = {
                timeWarningEnabled = it
                audioSettings.timeWarningEnabled = it
            },
        )
        SettingSwitchRow(
            title = "集中サウンド（ピンクノイズ）",
            checked = focusSoundEnabled,
            onCheckedChange = {
                focusSoundEnabled = it
                audioSettings.focusSoundEnabled = it
            },
        )
        Text("集中サウンド音量", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = focusSoundVolume,
            onValueChange = {
                focusSoundVolume = it
                audioSettings.focusSoundVolume = it
            },
            enabled = focusSoundEnabled,
            valueRange = 0f..0.5f,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "音量 ${kotlin.math.round(focusSoundVolume * 100).toInt()}%（端末の音量設定を使用）",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("試聴", style = MaterialTheme.typography.bodyMedium)
        AudioPreviewButton(
            preview = AudioPreview.PINK_NOISE,
            activePreview = audioPreview,
            onClick = ::toggleAudioPreview,
        )
        AudioPreviewButton(
            preview = AudioPreview.ONE_MINUTE_WARNING,
            activePreview = audioPreview,
            onClick = ::toggleAudioPreview,
        )
        AudioPreviewButton(
            preview = AudioPreview.THIRTY_SECONDS_WARNING,
            activePreview = audioPreview,
            onClick = ::toggleAudioPreview,
        )
        if (audioPreviewError) {
            Text("音を再生できませんでした", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SettingsNavigationRow(title: String, onClick: (() -> Unit)?) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text("›", style = MaterialTheme.typography.titleLarge, color = ChanrivaColors.accent)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AudioPreviewButton(
    preview: AudioPreview,
    activePreview: AudioPreview?,
    onClick: (AudioPreview) -> Unit,
) {
    val label = when (preview) {
        AudioPreview.PINK_NOISE -> "ピンクノイズを試聴"
        AudioPreview.ONE_MINUTE_WARNING -> "60秒警告音を試聴"
        AudioPreview.THIRTY_SECONDS_WARNING -> "30秒警告音を試聴"
    }
    OutlinedButton(onClick = { onClick(preview) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (activePreview == preview) "$label（停止）" else label)
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun AnalysisSettingsScreen(
    manager: EdaxDataManager,
    onDataChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember(manager) { mutableStateOf(manager.status()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf("検証・コピー中…") }
    val uriHandler = LocalUriHandler.current

    fun refresh() { status = manager.status(); onDataChanged() }
    fun importEvaluation(uri: android.net.Uri) {
        busy = true
        busyMessage = "検証・コピー中…"
        scope.launch {
            runCatching { manager.importEvaluationData(uri) }
                .onSuccess { message = "評価データをインポートしました"; messageIsError = false; refresh() }
                .onFailure {
                    message = "このデータを評価データとして読み込めませんでした。Edax互換ファイルか確認してください。"
                    messageIsError = true
                }
            busy = false
        }
    }
    fun downloadOfficialEvaluation() {
        busy = true
        busyMessage = "公式データの準備を開始中…"
        message = null
        scope.launch {
            runCatching {
                manager.downloadOfficialEvaluationData { phase ->
                    withContext(Dispatchers.Main.immediate) { busyMessage = phase }
                }
            }
                .onSuccess { message = "Edax公式 v4.4 の評価データを設定しました"; messageIsError = false; refresh() }
                .onFailure { failure ->
                    message = "公式評価データを設定できませんでした。通信状態を確認して、もう一度お試しください。(${failure.message ?: "原因不明"})"
                    messageIsError = true
                }
            busy = false
        }
    }
    fun importBook(uri: android.net.Uri) {
        busy = true
        scope.launch {
            runCatching { manager.importOpeningBook(uri) }
                .onSuccess { message = "オープニングブックをインポートしました"; refresh() }
                .onFailure {
                    message = "このデータをオープニングブックとして読み込めませんでした。Edax互換ファイルか確認してください。"
                }
            busy = false
        }
    }

    val evaluationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importEvaluation)
    }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importBook)
    }

    BackHandler(enabled = busy) { }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        SettingsHeader("AI解析設定", onBack, enabled = !busy)
        Text("解析エンジン: Edax ${EdaxReleaseConstants.ENGINE_VERSION}")
        Text("Edax状態: ${if (status.nativeAvailable) "利用可能" else "利用不可"}")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Edax解析 ON/OFF")
            Switch(
                checked = status.enabled,
                onCheckedChange = { manager.setEnabled(it); refresh() },
                enabled = status.nativeAvailable,
            )
        }
        Text("解析level: ${status.level}")
        Slider(
            value = status.level.toFloat(),
            onValueChange = { status = status.copy(level = it.toInt().coerceIn(1, 18)) },
            onValueChangeFinished = { manager.setLevel(status.level); onDataChanged() },
            valueRange = 1f..18f,
            steps = 16,
            enabled = !busy,
        )
        AnalysisFileStatus("評価データ", status.evaluationData, required = true)
        Button(
            onClick = ::downloadOfficialEvaluation,
            enabled = status.nativeAvailable && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("公式の評価データを自動設定（推奨）") }
        Button(
            onClick = { evaluationPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = status.nativeAvailable && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("手元の eval.dat を選ぶ") }
        OutlinedButton(
            onClick = {
                runCatching { uriHandler.openUri(EDAX_GUIDE_URL) }
                    .onFailure { message = "Edax説明ページを開けませんでした。ブラウザを確認してください。"; messageIsError = true }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("詳しい説明を見る") }
        OutlinedButton(
            onClick = { manager.deleteEvaluationData(); message = "評価データを削除しました"; refresh() },
            enabled = status.evaluationData != null && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("評価データを削除") }

        AnalysisFileStatus("オープニングブック", status.openingBook, required = false)
        Button(
            onClick = { bookPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = status.nativeAvailable && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("オープニングブックをインポート") }
        OutlinedButton(
            onClick = { manager.deleteOpeningBook(); message = "オープニングブックを削除しました"; refresh() },
            enabled = status.openingBook != null && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("オープニングブックを削除") }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(busyMessage)
            Text("処理が完了するまで、この画面を閉じずにお待ちください。", style = MaterialTheme.typography.bodySmall)
        }
        message?.let { Text(it, color = if (messageIsError) MaterialTheme.colorScheme.error else ChanrivaColors.accent) }
    }
}

@Composable
internal fun AboutScreen(onBack: () -> Unit, onLicenses: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsHeader("このアプリについて", onBack)
        Text("ちゃんりば")
        Text("ちゃんとリバーシ。軽く一局打っても、その一局がちゃんと残り、振り返りが次につながるリバーシアプリです。")
        Text("対局後レビューの解析エンジンとしてEdaxを使用します。Edax公式・公認アプリではありません。")
        Button(onClick = onLicenses, modifier = Modifier.fillMaxWidth()) { Text("オープンソースライセンス") }
        OutlinedButton(
            onClick = { uriHandler.openUri("https://chanriva.shinp-studio.com/privacy") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("プライバシーポリシー")
        }
    }
}

@Composable
internal fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader("オープンソースライセンス", onBack)
        Text("Edax 4.6", style = MaterialTheme.typography.titleMedium)
        Text("Copyright © 1998–2024 Richard Delorme; source headers also credit Toshihiko Okuhara.")
        Text("License: GNU General Public License version 3 (GPLv3)")
        Text("Upstream source: https://github.com/abulmo/edax-reversi")
        Text("このAndroidアプリの対応ソース: https://github.com/shinp-dev/othello")
        Text("固定upstream commit: 14f048c05ddfa385b6bf954a9c2905bbe677e9d3")
        Text("GPLv3全文はrepositoryのLICENSEと、対応ソース内のthird_party/edax/upstream/LICENSEにあります。")
        Text("本アプリはEdax projectまたは作者による公式・公認配布物ではありません。", color = ChanrivaColors.accent)
        Text("AndroidX / Kotlin / Ktor / Supabase SDK / WebRTCなど、その他の依存関係の表示はrepositoryのNOTICE.mdを参照してください。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AnalysisFileStatus(label: String, file: ImportedAnalysisFile?, required: Boolean) {
    var showDetails by remember(file?.sha256) { mutableStateOf(false) }
    Text("$label: ${if (file == null) "未設定" else "設定済み"}", style = MaterialTheme.typography.titleSmall)
    if (file == null) {
        Text(if (required) "解析に必要です" else "任意。未設定時は通常探索を使用します", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("取込日時: ${formatImportDate(file.importedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "詳細情報を閉じる" else "詳細情報")
        }
        if (showDetails) {
            Text("ファイル名: ${file.fileName}", style = MaterialTheme.typography.bodySmall)
            Text("サイズ: ${formatAnalysisFileSize(file.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
            Text("SHA-256: ${file.sha256}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = onBack, enabled = enabled) { Text("戻る") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 2.dp, height = 22.dp).background(ChanrivaColors.accent))
            Spacer(Modifier.size(ChanrivaSpacing.control))
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun formatImportDate(epochMillis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMillis))

internal fun formatAnalysisFileSize(sizeBytes: Long): String = when {
    sizeBytes < 1024L -> "$sizeBytes B（1 KiB未満）"
    sizeBytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KiB", sizeBytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", sizeBytes / (1024.0 * 1024.0))
}

private const val EDAX_GUIDE_URL = "https://chanriva.shinp-studio.com/edax"
