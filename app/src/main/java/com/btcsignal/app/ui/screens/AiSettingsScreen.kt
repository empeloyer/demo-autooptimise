package com.btcsignal.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.ai.*
import com.btcsignal.app.data.repository.AppSettings
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.AmberWarning
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiSettingsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val settingsRepo = remember { AppContainer.settingsRepository(context) }
    val aiEngine = remember { AppContainer.aiInferenceEngine(context) }
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settingsFlow.collectAsState(initial = AppSettings())

    var modelUrlField by remember { mutableStateOf(settings.aiModelUrl) }
    LaunchedEffect(settings.aiModelUrl) { modelUrlField = settings.aiModelUrl }

    var modelInfo by remember { mutableStateOf(AiModelManager.currentModelInfo(context)) }
    var downloadState by remember { mutableStateOf<AiDownloadProgress?>(null) }
    var validateMessage by remember { mutableStateOf<String?>(null) }
    var validateOk by remember { mutableStateOf<Boolean?>(null) }
    val isDownloading = downloadState is AiDownloadProgress.Downloading

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch { settingsRepo.setStoragePermissionRequested(true) }
        if (granted) {
            scope.launch {
                AiModelManager.download(context, modelUrlField) { p ->
                    downloadState = p
                    if (p is AiDownloadProgress.Done) modelInfo = p.info
                }
            }
        }
    }

    fun startDownload() {
        if (AiModelManager.needsRuntimeStoragePermission() && !AiModelManager.hasStoragePermission(context)) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        scope.launch {
            AiModelManager.download(context, modelUrlField) { p ->
                downloadState = p
                if (p is AiDownloadProgress.Done) modelInfo = p.info
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("AI Engine", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard("About") {
                    Text(
                        "This app's signals come entirely from the deterministic, backtested " +
                            "Strategy Database (see the Strategies and Backtest tabs) \u2014 that " +
                            "never changes because of anything on this page. AI Assist is an " +
                            "optional extra check on top: a person-supplied ONNX model you " +
                            "download here, run fully on-device, that can confirm a signal, " +
                            "silence it, or (Override mode only) flip its direction in the " +
                            "final two minutes of a candle.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This app does not ship a model and does not train one. Whatever " +
                            ".onnx file you point it at must accept " +
                            "${AiFeatureBuilder.FEATURE_NAMES.size} float32 inputs, in the exact " +
                            "order documented in ai/AiFeatureBuilder.kt (feature contract " +
                            "v${AiFeatureBuilder.FEATURE_VERSION}), and return 2 float32 outputs " +
                            "[prob_green, prob_red]. A mismatched model is rejected automatically " +
                            "at load time \u2014 it will not silently run on the wrong inputs.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            item {
                SectionCard("Model") {
                    OutlinedTextField(
                        value = modelUrlField,
                        onValueChange = { modelUrlField = it },
                        label = { Text("Model download URL (.onnx)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isDownloading
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "You provide this URL \u2014 host your own trained model wherever you " +
                            "like (your own server, cloud storage, etc). Nothing is fetched until you tap Download.",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(10.dp))

                    if (AiModelManager.needsRuntimeStoragePermission() && !AiModelManager.hasStoragePermission(context)) {
                        Text(
                            "Storage permission is required to save the model file on this Android version.",
                            color = AmberWarning, style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { modelUrlField.let { settingsRepo.setAiModelUrl(it) }; startDownload() } },
                            enabled = !isDownloading && modelUrlField.isNotBlank()
                        ) { Text(if (isDownloading) "Downloading\u2026" else "Download Model") }

                        if (modelInfo != null) {
                            OutlinedButton(onClick = {
                                AiModelManager.deleteModel(context)
                                aiEngine.close()
                                modelInfo = null
                                validateMessage = null
                                validateOk = null
                            }) { Text("Delete") }
                        }
                    }

                    (downloadState as? AiDownloadProgress.Downloading)?.let { d ->
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        val sizeText = if (d.totalBytes > 0) "${d.bytesRead / 1024 / 1024}MB / ${d.totalBytes / 1024 / 1024}MB" else "${d.bytesRead / 1024 / 1024}MB"
                        Text("$sizeText (${d.percent}%)", style = MaterialTheme.typography.labelSmall)
                    }
                    (downloadState as? AiDownloadProgress.Failed)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Download failed: ${it.message}", color = RedSignal, style = MaterialTheme.typography.bodyMedium)
                    }

                    modelInfo?.let { info ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "On device: ${info.sizeBytes / 1024 / 1024}MB, saved at ${info.file.absolutePath}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val loadResult = aiEngine.loadFromFile(info.file)
                                    var ok = loadResult.isSuccess
                                    var message = loadResult.exceptionOrNull()?.message
                                    if (loadResult.isSuccess) {
                                        val v = aiEngine.validate()
                                        ok = v.isSuccess
                                        message = v.exceptionOrNull()?.message ?: "Model loaded and validated successfully."
                                    }
                                    withContext(Dispatchers.Main) {
                                        validateOk = ok
                                        validateMessage = message
                                    }
                                }
                            }) { Text("Load & Validate") }

                            when (validateOk) {
                                true -> Text("\u2713 Valid", color = GreenSignal, style = MaterialTheme.typography.bodyMedium)
                                false -> Text("\u2717 Invalid", color = RedSignal, style = MaterialTheme.typography.bodyMedium)
                                null -> {}
                            }
                        }
                        validateMessage?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            item {
                SectionCard("AI Assist") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Enable AI Assist", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Only takes effect once a model is downloaded and validated above.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Switch(
                            checked = settings.aiAssistEnabled,
                            onCheckedChange = { scope.launch { settingsRepo.setAiAssistEnabled(it) } },
                            enabled = aiEngine.isLoaded
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    AiModeOption(
                        title = "Advisory Only", description = "Logs the AI's opinion for every checkpoint (visible in Debug/History). Never changes a signal. Recommended starting point.",
                        selected = settings.aiMode == AiMode.ADVISORY
                    ) { scope.launch { settingsRepo.setAiMode(AiMode.ADVISORY) } }
                    AiModeOption(
                        title = "Confirmation Filter (recommended once enabled)",
                        description = "A signal only fires if the AI agrees with the rule engine's direction at or above the confidence threshold below. Disagreement silently drops the signal.",
                        selected = settings.aiMode == AiMode.CONFIRM
                    ) { scope.launch { settingsRepo.setAiMode(AiMode.CONFIRM) } }
                    AiModeOption(
                        title = "Full Override (experimental)",
                        description = "Everything Confirmation Filter does, plus: if the AI strongly disagrees (\u2265 override threshold), the final signal flips to the AI's direction instead of the rule engine's. Validate this in the Backtest tab before trusting it live.",
                        selected = settings.aiMode == AiMode.OVERRIDE
                    ) { scope.launch { settingsRepo.setAiMode(AiMode.OVERRIDE) } }

                    Spacer(Modifier.height(16.dp))
                    ThresholdSlider(
                        label = "Confirmation confidence threshold",
                        value = settings.aiConfidenceThreshold,
                        valueRange = 0.5f..0.9f,
                        onChange = { scope.launch { settingsRepo.setAiConfidenceThreshold(it) } }
                    )
                    if (settings.aiMode == AiMode.OVERRIDE) {
                        Spacer(Modifier.height(8.dp))
                        ThresholdSlider(
                            label = "Override confidence threshold",
                            value = settings.aiOverrideThreshold,
                            valueRange = 0.6f..0.95f,
                            onChange = { scope.launch { settingsRepo.setAiOverrideThreshold(it) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiModeOption(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(description, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun ThresholdSlider(label: String, value: Double, valueRange: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Text("$label: ${"%.2f".format(sliderValue)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChange(sliderValue.toDouble()) },
            valueRange = valueRange
        )
    }
}
