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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.othello.analysis.edax.AiMatchSettings
import com.example.othello.analysis.edax.EdaxDataManager
import com.example.othello.analysis.edax.EdaxSettingsStore
import com.example.othello.analysis.edax.ImportedAnalysisFile
import com.example.othello.analysis.edax.ReviewAnalysisSettings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.RadioButton
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaNavigationRow
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(
    onMatchSettings: () -> Unit,
    onReviewSettings: () -> Unit,
    onCommonSettings: () -> Unit,
    onResearch: () -> Unit,
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    val selectedLanguage = AppLanguage.fromTag(AppCompatDelegate.getApplicationLocales().toLanguageTags())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        ChanrivaScreenHeader(appString(R.string.settings))
        ChanrivaNavigationRow(appString(R.string.match_settings), onMatchSettings)
        ChanrivaNavigationRow(appString(R.string.review_settings), onReviewSettings)
        ChanrivaNavigationRow(appString(R.string.common_settings), onCommonSettings)
        ChanrivaNavigationRow(appString(R.string.research_participation), onResearch)
        ChanrivaNavigationRow(
            title = appString(R.string.language_setting),
            supportingText = when (selectedLanguage) {
                AppLanguage.SYSTEM_DEFAULT -> appString(R.string.language_system_default)
                AppLanguage.JAPANESE -> appString(R.string.language_japanese)
                AppLanguage.ENGLISH -> appString(R.string.language_english)
            },
            onClick = { showLanguageDialog = true },
        )
    }
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            selectedLanguage = selectedLanguage,
            onSelect = { language ->
                applyAppLanguage(language)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
}

@Composable
private fun LanguageSelectionDialog(
    selectedLanguage: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appString(R.string.language_dialog_title)) },
        text = {
            Column {
                listOf(
                    AppLanguage.SYSTEM_DEFAULT to appString(R.string.language_system_default),
                    AppLanguage.JAPANESE to appString(R.string.language_japanese),
                    AppLanguage.ENGLISH to appString(R.string.language_english),
                ).forEach { (language, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = language == selectedLanguage, onClick = { onSelect(language) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
internal fun MatchSettingsScreen(
    onBack: () -> Unit,
    onAiSettings: () -> Unit,
    onCommonMatchSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        SettingsHeader(appString(R.string.match_settings), onBack)
        ChanrivaNavigationRow(appString(R.string.ai_match_settings), onAiSettings)
        ChanrivaNavigationRow(appString(R.string.match_common_settings), onCommonMatchSettings)
    }
}

@Composable
internal fun AiMatchSettingsScreen(
    onBack: () -> Unit,
    edaxSettings: EdaxSettingsStore,
) {
    var aiSettings by remember(edaxSettings) { mutableStateOf(edaxSettings.aiMatchSettings()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        SettingsHeader(appString(R.string.ai_match_settings), onBack)
        Text(appString(R.string.ai_level, aiSettings.level))
        Slider(
            value = aiSettings.level.toFloat(),
            onValueChange = {
                aiSettings = aiSettings.copy(level = it.roundToInt().coerceIn(AiMatchSettings.MIN_LEVEL, AiMatchSettings.MAX_LEVEL))
            },
            onValueChangeFinished = { edaxSettings.setAiMatchLevel(aiSettings.level) },
            valueRange = AiMatchSettings.MIN_LEVEL.toFloat()..AiMatchSettings.MAX_LEVEL.toFloat(),
            steps = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(appString(R.string.ai_move_time, aiSettings.moveTimeMs))
        Slider(
            value = aiSettings.moveTimeMs.toFloat(),
            onValueChange = { aiSettings = aiSettings.copy(moveTimeMs = snapAnalysisTime(it)) },
            onValueChangeFinished = { edaxSettings.setAiMatchMoveTimeMs(aiSettings.moveTimeMs) },
            valueRange = EdaxSettingsStore.MIN_ANALYSIS_TIME_MS.toFloat()..EdaxSettingsStore.MAX_ANALYSIS_TIME_MS.toFloat(),
            steps = analysisTimeSliderSteps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun MatchCommonSettingsScreen(
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
        SettingsHeader(appString(R.string.match_common_settings), onBack)
        Text(appString(R.string.match_audio), style = MaterialTheme.typography.titleMedium)
        SettingSwitchRow(
            title = appString(R.string.time_warning_sound),
            checked = timeWarningEnabled,
            onCheckedChange = {
                timeWarningEnabled = it
                audioSettings.timeWarningEnabled = it
            },
        )
        SettingSwitchRow(
            title = appString(R.string.focus_sound),
            checked = focusSoundEnabled,
            onCheckedChange = {
                focusSoundEnabled = it
                audioSettings.focusSoundEnabled = it
            },
        )
        Text(appString(R.string.focus_volume), style = MaterialTheme.typography.bodyMedium)
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
            appString(R.string.volume_with_device, kotlin.math.round(focusSoundVolume * 100).toInt()),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(appString(R.string.preview), style = MaterialTheme.typography.bodyMedium)
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
            Text(appString(R.string.audio_playback_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun ReviewSettingsScreen(
    settingsStore: EdaxSettingsStore,
    onBack: () -> Unit,
) {
    var settings by remember(settingsStore) { mutableStateOf(settingsStore.reviewAnalysisSettings()) }
    var showHighLoadWarning by remember { mutableStateOf(false) }
    val maximumLevel = if (settings.highLoadAnalysisEnabled) {
        ReviewAnalysisSettings.HIGH_LOAD_MAX_LEVEL
    } else {
        ReviewAnalysisSettings.NORMAL_MAX_LEVEL
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        SettingsHeader(appString(R.string.review_settings), onBack)
        SettingSwitchRow(
            title = appString(R.string.high_load_analysis),
            checked = settings.highLoadAnalysisEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    showHighLoadWarning = true
                } else {
                    settingsStore.setHighLoadAnalysisEnabled(false)
                    settings = settingsStore.reviewAnalysisSettings()
                }
            },
        )
        if (settings.highLoadAnalysisEnabled) {
            Text(appString(R.string.high_load_warning), color = ChanrivaColors.accent, style = MaterialTheme.typography.bodySmall)
        }
        Text(appString(R.string.analysis_level, settings.level))
        Slider(
            value = settings.level.toFloat(),
            onValueChange = {
                settings = settings.copy(
                    level = it.roundToInt().coerceIn(
                        ReviewAnalysisSettings.MIN_LEVEL,
                        maximumLevel,
                    ),
                )
            },
            onValueChangeFinished = { settingsStore.setReviewAnalysisLevel(settings.level) },
            valueRange = ReviewAnalysisSettings.MIN_LEVEL.toFloat()..maximumLevel.toFloat(),
            steps = maximumLevel - ReviewAnalysisSettings.MIN_LEVEL - 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(appString(R.string.analysis_time, settings.timePerCandidateMs))
        Slider(
            value = settings.timePerCandidateMs.toFloat(),
            onValueChange = { settings = settings.copy(timePerCandidateMs = snapAnalysisTime(it)) },
            onValueChangeFinished = {
                settingsStore.setReviewAnalysisTimePerCandidateMs(settings.timePerCandidateMs)
            },
            valueRange = EdaxSettingsStore.MIN_ANALYSIS_TIME_MS.toFloat()..EdaxSettingsStore.MAX_ANALYSIS_TIME_MS.toFloat(),
            steps = analysisTimeSliderSteps,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showHighLoadWarning) {
        AlertDialog(
            onDismissRequest = { showHighLoadWarning = false },
            title = { Text(appString(R.string.high_load_analysis_confirm)) },
            text = { Text(appString(R.string.high_load_warning)) },
            confirmButton = {
                Button(onClick = {
                    settingsStore.setHighLoadAnalysisEnabled(true)
                    settings = settingsStore.reviewAnalysisSettings()
                    showHighLoadWarning = false
                }) { Text(appString(R.string.enable)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showHighLoadWarning = false }) { Text(appString(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun AudioPreviewButton(
    preview: AudioPreview,
    activePreview: AudioPreview?,
    onClick: (AudioPreview) -> Unit,
) {
    val label = when (preview) {
        AudioPreview.PINK_NOISE -> appString(R.string.pink_noise_preview)
        AudioPreview.ONE_MINUTE_WARNING -> appString(R.string.one_minute_preview)
        AudioPreview.THIRTY_SECONDS_WARNING -> appString(R.string.thirty_seconds_preview)
    }
    OutlinedButton(onClick = { onClick(preview) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (activePreview == preview) appString(R.string.preview_stop, label) else label)
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
internal fun CommonSettingsScreen(
    manager: EdaxDataManager,
    onDataChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(manager) { mutableStateOf(manager.commonDataStatus()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf(context.getString(R.string.processing_copy)) }
    var deleteEvaluationConfirmation by remember { mutableStateOf(false) }
    var deleteBookConfirmation by remember { mutableStateOf(false) }
    fun refresh() { status = manager.commonDataStatus(); onDataChanged() }
    fun importEvaluation(uri: android.net.Uri) {
        busy = true
        busyMessage = context.getString(R.string.processing_copy)
        scope.launch {
            runCatching { manager.importEvaluationData(uri) }
                .onSuccess { message = context.getString(R.string.eval_imported); messageIsError = false; refresh() }
                .onFailure {
                    message = context.getString(R.string.eval_import_failed)
                    messageIsError = true
                }
            busy = false
        }
    }
    fun downloadOfficialEvaluation() {
        busy = true
        busyMessage = context.getString(R.string.official_data_starting)
        message = null
        scope.launch {
            runCatching {
                manager.downloadOfficialEvaluationData { phase ->
                    withContext(Dispatchers.Main.immediate) { busyMessage = phase }
                }
            }
                .onSuccess { message = context.getString(R.string.official_eval_set); messageIsError = false; refresh() }
                .onFailure { failure ->
                    message = context.getString(
                        R.string.official_eval_failed,
                        localizeUserMessage(context, failure.message) ?: context.getString(R.string.unknown_reason),
                    )
                    messageIsError = true
                }
            busy = false
        }
    }
    fun importBook(uri: android.net.Uri) {
        busy = true
        scope.launch {
            runCatching { manager.importOpeningBook(uri) }
                .onSuccess { message = context.getString(R.string.book_imported); refresh() }
                .onFailure {
                    message = context.getString(R.string.book_import_failed)
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
        SettingsHeader(appString(R.string.common_settings), onBack, enabled = !busy)
        AnalysisFileStatus(appString(R.string.analysis_data), status.evaluationData, required = true)
        ChanrivaNavigationRow(
            title = appString(R.string.auto_setup_eval),
            supportingText = appString(R.string.analysis_required),
            onClick = if (status.nativeAvailable && !busy) ::downloadOfficialEvaluation else null,
            emphasized = true,
        )
        ChanrivaNavigationRow(
            title = appString(R.string.choose_eval),
            onClick = if (status.nativeAvailable && !busy) {
                { evaluationPicker.launch(arrayOf("application/octet-stream", "*/*")) }
            } else null,
        )
        if (status.evaluationData != null) {
            ChanrivaNavigationRow(
                title = appString(R.string.delete_eval),
                onClick = if (!busy) ({ deleteEvaluationConfirmation = true }) else null,
            )
        }

        AnalysisFileStatus(appString(R.string.opening_book), status.openingBook, required = false)
        ChanrivaNavigationRow(
            title = appString(R.string.choose_book),
            onClick = if (status.nativeAvailable && !busy) {
                { bookPicker.launch(arrayOf("application/octet-stream", "*/*")) }
            } else null,
        )
        if (status.openingBook != null) {
            ChanrivaNavigationRow(
                title = appString(R.string.delete_book),
                onClick = if (!busy) ({ deleteBookConfirmation = true }) else null,
            )
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(busyMessage)
            Text(appString(R.string.wait_processing), style = MaterialTheme.typography.bodySmall)
        }
        message?.let { Text(it, color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
    }
    if (deleteEvaluationConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteEvaluationConfirmation = false },
            title = { Text(appString(R.string.delete_eval_confirm_title)) },
            text = { Text(appString(R.string.delete_eval_confirm_text)) },
            confirmButton = {
                Button(onClick = {
                    deleteEvaluationConfirmation = false
                    manager.deleteEvaluationData()
                    message = context.getString(R.string.eval_deleted)
                    messageIsError = false
                    refresh()
                }) { Text(appString(R.string.delete)) }
            },
            dismissButton = { OutlinedButton(onClick = { deleteEvaluationConfirmation = false }) { Text(appString(R.string.cancel)) } },
        )
    }
    if (deleteBookConfirmation) {
        AlertDialog(
            onDismissRequest = { deleteBookConfirmation = false },
            title = { Text(appString(R.string.delete_book_confirm_title)) },
            text = { Text(appString(R.string.delete_book_confirm_text)) },
            confirmButton = {
                Button(onClick = {
                    deleteBookConfirmation = false
                    manager.deleteOpeningBook()
                    message = context.getString(R.string.book_deleted)
                    messageIsError = false
                    refresh()
                }) { Text(appString(R.string.delete)) }
            },
            dismissButton = { OutlinedButton(onClick = { deleteBookConfirmation = false }) { Text(appString(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun AboutScreen(
    onBack: () -> Unit,
    onPrivacy: () -> Unit,
    onLicenses: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.compact),
    ) {
        SettingsHeader(appString(R.string.about_app), onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .background(ChanrivaColors.background)
                .padding(vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(appString(R.string.about_tagline), style = MaterialTheme.typography.displaySmall, color = ChanrivaColors.accent)
            Spacer(Modifier.size(4.dp))
            Text(appString(R.string.about_copy), style = MaterialTheme.typography.bodyLarge, color = ChanrivaColors.textPrimary)
        }
        ChanrivaNavigationRow(appString(R.string.official_site), onClick = { uriHandler.openUri(CHANRIVA_SITE_URL) }, trailingLabel = "Web")
        ChanrivaNavigationRow(appString(R.string.source_code), onClick = { uriHandler.openUri(CHANRIVA_SOURCE_URL) }, trailingLabel = "Web")
        ChanrivaNavigationRow(appString(R.string.privacy_policy), onPrivacy, trailingLabel = "Web")
        ChanrivaNavigationRow(appString(R.string.edax_about), onClick = { uriHandler.openUri(EDAX_GUIDE_URL) }, trailingLabel = "Web")
        Text(
            appString(R.string.edax_disclaimer),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = ChanrivaColors.textSecondary,
        )
        ChanrivaNavigationRow(appString(R.string.oss_licenses), onLicenses)
        BuildIdentityText()
    }
}

@Composable
internal fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
    onEdax: () -> Unit,
    onOtherOss: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(appString(R.string.oss_licenses), onBack)
        Text(appString(R.string.app_name), style = MaterialTheme.typography.titleMedium)
        Text("GNU GPLv3")
        Text("Edax 4.6", style = MaterialTheme.typography.titleMedium)
        Text("GNU GPLv3")
        ChanrivaNavigationRow("Edax 4.6 details", onEdax)
        ChanrivaNavigationRow(appString(R.string.other_oss_licenses), onOtherOss)
    }
}

@Composable
internal fun EdaxLicenseScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader("Edax 4.6 details", onBack)
        Text(appString(R.string.copyright_edax))
        Text(appString(R.string.license))
        Text(appString(R.string.upstream_source))
        Text(appString(R.string.chanriva_source))
        Text(appString(R.string.upstream_commit))
        Text(appString(R.string.gpl_location))
        Text(appString(R.string.edax_not_official), color = ChanrivaColors.accent)
    }
}

@Composable
internal fun OtherOssLicensesScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsHeader(appString(R.string.other_oss_licenses), onBack)
        Text(appString(R.string.android_licenses))
        Text(appString(R.string.kotlin_licenses))
        Text(appString(R.string.ktor_license))
        Text(appString(R.string.supabase_license))
        Text(appString(R.string.webrtc_license))
        Text(appString(R.string.other_dependencies))
        Text(appString(R.string.commons_compress_license))
        Text(appString(R.string.xz_license))
        Text(appString(R.string.notice_location))
    }
}

@Composable
private fun AnalysisFileStatus(label: String, file: ImportedAnalysisFile?, required: Boolean) {
    var showDetails by remember(file?.sha256) { mutableStateOf(false) }
    Text(appString(R.string.file_status, label, appString(if (file == null) R.string.not_configured else R.string.configured)), style = MaterialTheme.typography.titleSmall)
    if (file == null) {
        Text(appString(if (required) R.string.analysis_required else R.string.optional_search), style = MaterialTheme.typography.bodySmall)
    } else {
        Text(appString(R.string.imported_at, formatImportDate(file.importedAtEpochMillis)), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(appString(if (showDetails) R.string.close_details else R.string.details))
        }
        if (showDetails) {
            Text(appString(R.string.file_name, file.fileName), style = MaterialTheme.typography.bodySmall)
            Text(appString(R.string.file_size, localizedAnalysisFileSize(file.sizeBytes)), style = MaterialTheme.typography.bodySmall)
            Text("SHA-256: ${file.sha256}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit, enabled: Boolean = true) {
    ChanrivaScreenHeader(title, onBack, enabled, backLabel = appString(R.string.back))
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
private const val CHANRIVA_SITE_URL = "https://chanriva.shinp-studio.com/"
private const val CHANRIVA_SOURCE_URL = "https://github.com/shinp-dev/othello"
private val analysisTimeSliderSteps =
    (EdaxSettingsStore.MAX_ANALYSIS_TIME_MS - EdaxSettingsStore.MIN_ANALYSIS_TIME_MS) /
        EdaxSettingsStore.ANALYSIS_TIME_STEP_MS - 1

private fun snapAnalysisTime(value: Float): Int {
    val offsetSteps = ((value - EdaxSettingsStore.MIN_ANALYSIS_TIME_MS) /
        EdaxSettingsStore.ANALYSIS_TIME_STEP_MS).roundToInt()
    return (EdaxSettingsStore.MIN_ANALYSIS_TIME_MS + offsetSteps * EdaxSettingsStore.ANALYSIS_TIME_STEP_MS)
        .coerceIn(EdaxSettingsStore.MIN_ANALYSIS_TIME_MS, EdaxSettingsStore.MAX_ANALYSIS_TIME_MS)
}

@Composable
private fun localizedAnalysisFileSize(sizeBytes: Long): String = if (sizeBytes < 1024L) {
    appString(R.string.file_size_under_kib, sizeBytes)
} else {
    formatAnalysisFileSize(sizeBytes)
}
