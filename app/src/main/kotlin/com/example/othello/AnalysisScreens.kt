package com.example.othello

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
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

@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onAnalysis: () -> Unit,
    onResearch: (() -> Unit)?,
    onAbout: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsHeader("設定", onBack)
        Button(onClick = onAnalysis, modifier = Modifier.fillMaxWidth()) { Text("解析") }
        OutlinedButton(
            onClick = { onResearch?.invoke() },
            enabled = onResearch != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("研究参加") }
        if (onResearch == null) {
            Text("研究参加の設定にはログインが必要です", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("このアプリについて") }
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
    var showSetupGuide by remember { mutableStateOf(false) }
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
        busyMessage = "公式データをダウンロード・展開・検証中…"
        message = null
        scope.launch {
            runCatching { manager.downloadOfficialEvaluationData() }
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        SettingsHeader("解析", onBack)
        Text("解析エンジン: Edax ${EdaxReleaseConstants.ENGINE_VERSION}")
        Text("Edax状態: ${if (status.nativeAvailable) "利用可能" else "利用不可"}")
        status.nativeVersion?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Edax解析 ON/OFF")
            Switch(
                checked = status.enabled,
                onCheckedChange = { manager.setEnabled(it); refresh() },
                enabled = status.nativeAvailable,
            )
        }
        Text("解析強度: level ${status.level}（標準 8）")
        Slider(
            value = status.level.toFloat(),
            onValueChange = { status = status.copy(level = it.toInt().coerceIn(1, 18)) },
            onValueChangeFinished = { manager.setLevel(status.level); onDataChanged() },
            valueRange = 1f..18f,
            steps = 16,
            enabled = !busy,
        )
        Text("スマホ向けの既定値はレベル8です。解析は棋譜レビュー画面で明示的に開始した時だけ動作します。", style = MaterialTheme.typography.bodySmall)

        Text("解析データについて", style = MaterialTheme.typography.titleMedium)
        Text(
            "Edax解析には評価データを使用します。初めて設定する場合は、取得・設定方法をご確認ください。対応するデータをお持ちの場合は、そのままインポートして利用できます。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { showSetupGuide = !showSetupGuide },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (showSetupGuide) "取得・設定方法を閉じる" else "取得・設定方法を見る") }
        if (showSetupGuide) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(ChanrivaSpacing.card),
                    verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
                ) {
                    Text("評価データ", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Edaxが中盤局面を評価するために必要なデータです。公式データを自動設定するか、手元のeval.datを選択してインポートできます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = ::downloadOfficialEvaluation,
                        enabled = status.nativeAvailable && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("公式の評価データ v${EdaxReleaseConstants.EVALUATION_DATA_VERSION}を自動設定（推奨）") }
                    OutlinedButton(
                        onClick = {
                            runCatching { uriHandler.openUri(EDAX_RELEASES_URL) }
                                .onFailure { message = "Edax公式リリースを開けませんでした。ブラウザを確認してください。" }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Edax公式リリースを開く") }
                    OutlinedButton(
                        onClick = {
                            runCatching { uriHandler.openUri(EDAX_GUIDE_URL) }
                                .onFailure { message = "Edax説明ページを開けませんでした。ブラウザを確認してください。"; messageIsError = true }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Edax評価データの説明を見る") }
                    Text("オープニングブック（任意）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "序盤の候補手を利用するためのEdax互換データです。未設定でも通常探索で解析できます。利用する場合は、配布条件を確認できる入手元から取得したファイル、またはご自身が所有する対応ファイルを「オープニングブックをインポート」から選択してください。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "再度設定・インポートすると、検証済みの新しいデータへ置き換えます。ダウンロード・展開・検証に失敗した場合、現在のデータは保持されます。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        AnalysisFileStatus("評価データ", status.evaluationData, required = true)
        Button(
            onClick = { evaluationPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = status.nativeAvailable && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("評価データをインポート") }
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

        Text("ファイルはStorage Access Frameworkから選び、アプリprivate storageへコピーします。広範なストレージ権限は使用しません。")
        Text("評価データと第三者オープニングブックはアプリに同梱されていません。正当に取得・所有しているEdax互換ファイルだけを選択してください。", style = MaterialTheme.typography.bodySmall)
        if (busy) Text(busyMessage)
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
    Text("$label: ${if (file == null) "未設定" else "読込済み"}", style = MaterialTheme.typography.titleSmall)
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
internal fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = onBack) { Text("戻る") }
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

private const val EDAX_RELEASES_URL = "https://github.com/abulmo/edax-reversi/releases"
private const val EDAX_GUIDE_URL = "https://chanriva.shinp-studio.com/edax"
