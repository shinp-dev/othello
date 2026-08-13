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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.othello.analysis.edax.EdaxDataManager
import com.example.othello.analysis.edax.ImportedAnalysisFile
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    fun refresh() { status = manager.status(); onDataChanged() }
    fun importEvaluation(uri: android.net.Uri) {
        busy = true
        scope.launch {
            runCatching { manager.importEvaluationData(uri) }
                .onSuccess { message = "評価データをインポートしました"; refresh() }
                .onFailure { message = "評価データを読み込めません: ${it.message}" }
            busy = false
        }
    }
    fun importBook(uri: android.net.Uri) {
        busy = true
        scope.launch {
            runCatching { manager.importOpeningBook(uri) }
                .onSuccess { message = "Opening Bookをインポートしました"; refresh() }
                .onFailure { message = "Opening Bookを読み込めません: ${it.message}" }
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
        Text("解析エンジン: Edax 4.6")
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

        AnalysisFileStatus("Opening Book", status.openingBook, required = false)
        Button(
            onClick = { bookPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = status.nativeAvailable && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Opening Bookをインポート") }
        OutlinedButton(
            onClick = { manager.deleteOpeningBook(); message = "Opening Bookを削除しました"; refresh() },
            enabled = status.openingBook != null && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Opening Bookを削除") }

        Text("ファイルはStorage Access Frameworkから選び、アプリprivate storageへコピーします。広範なストレージ権限は使用しません。")
        Text("eval.datと第三者Bookはアプリに同梱されていません。正当に取得・所有しているEdax互換ファイルだけを選択してください。", style = MaterialTheme.typography.bodySmall)
        if (busy) Text("検証・コピー中…")
        message?.let { Text(it, color = if (it.contains("ません")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun AboutScreen(onBack: () -> Unit, onLicenses: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsHeader("このアプリについて", onBack)
        Text("ちゃんりば")
        Text("ちゃんとリバーシ。軽く一局打っても、その一局がちゃんと残り、振り返りが次につながるリバーシアプリです。")
        Text("対局後レビューの解析エンジンとしてEdaxを使用します。Edax公式・公認アプリではありません。")
        Button(onClick = onLicenses, modifier = Modifier.fillMaxWidth()) { Text("オープンソースライセンス") }
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
        Text("本アプリはEdax projectまたは作者による公式・公認配布物ではありません。", color = MaterialTheme.colorScheme.primary)
        Text("AndroidX / Kotlin / Ktor / Supabase SDK / WebRTCなど、その他の依存関係の表示はrepositoryのNOTICE.mdを参照してください。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AnalysisFileStatus(label: String, file: ImportedAnalysisFile?, required: Boolean) {
    Text("$label: ${if (file == null) "未設定" else "読込済み"}", style = MaterialTheme.typography.titleSmall)
    if (file == null) {
        Text(if (required) "解析に必要です" else "任意。未設定時は通常探索を使用します", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(file.fileName)
        Text("${file.sizeBytes / 1024} KiB / SHA-256 ${file.sha256.take(12)}…", style = MaterialTheme.typography.bodySmall)
        Text("取込日時: ${formatImportDate(file.importedAtEpochMillis)}", style = MaterialTheme.typography.bodySmall)
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
