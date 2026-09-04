package com.btcsignal.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btcsignal.app.AppContainer
import com.btcsignal.app.backtest.AutoOptimizeState
import com.btcsignal.app.data.repository.AppSettings
import com.btcsignal.app.engine.AutoOptimizeVerdict
import com.btcsignal.app.ui.components.SectionCard
import com.btcsignal.app.ui.theme.AmberWarning
import com.btcsignal.app.ui.theme.GreenSignal
import com.btcsignal.app.ui.theme.RedSignal
import com.btcsignal.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(onOpenAiSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val settingsRepo = remember { AppContainer.settingsRepository(context) }
    val notificationHelper = remember { AppContainer.notificationHelper(context) }
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settingsFlow.collectAsState(initial = AppSettings())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard("Notifications") {
                    SettingRow("Notifications", settings.notificationsEnabled) {
                        scope.launch { settingsRepo.setNotificationsEnabled(it) }
                    }
                    SettingRow("Sound", settings.soundEnabled) {
                        scope.launch { settingsRepo.setSoundEnabled(it) }
                    }
                    SettingRow("Vibration", settings.vibrationEnabled) {
                        scope.launch { settingsRepo.setVibrationEnabled(it) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            notificationHelper.sendTestNotification(settings.soundEnabled, settings.vibrationEnabled)
                        }) { Text("Test Notification") }
                        OutlinedButton(onClick = {
                            notificationHelper.sendTestNotification(soundEnabled = true, vibrationEnabled = settings.vibrationEnabled)
                        }) { Text("Test Sound") }
                    }
                }
            }

            item { AutoOptimizeSection() }

            item {
                SectionCard("AI Engine") {
                    Text(
                        "Optional on-device AI model that can confirm, silence, or (in " +
                            "Override mode) flip a signal in its final two minutes. You " +
                            "supply the ONNX model; nothing is bundled or downloaded automatically.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val statusText = if (settings.aiAssistEnabled) "AI Assist: ON (${settings.aiMode})" else "AI Assist: OFF"
                        val statusColor = if (settings.aiAssistEnabled) GreenSignal else TextSecondary
                        Text(statusText, color = statusColor, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenAiSettings) { Text("Open AI Engine \u2192") }
                }
            }
        }
    }
}

@Composable
private fun AutoOptimizeSection() {
    val context = LocalContext.current
    val settingsRepo = remember { AppContainer.settingsRepository(context) }
    val settings by settingsRepo.settingsFlow.collectAsState(initial = AppSettings())
    val isRunning by AutoOptimizeState.isRunning.collectAsState()
    val progress by AutoOptimizeState.progress.collectAsState()
    val result by AutoOptimizeState.result.collectAsState()
    val error by AutoOptimizeState.error.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    SectionCard("Auto-Optimize") {
        Text(
            "Re-checks every strategy against the last ${com.btcsignal.app.engine.AutoOptimizeEngine.DEFAULT_WINDOW_DAYS} " +
                "days of real market data (walk-forward, in 30-day blocks) and automatically blocks any " +
                "strategy that no longer clears the same statistical bar the Research Report used to " +
                "accept it \u2014 min sample size, win rate vs breakeven (z-score \u2265 1.0), no single bad " +
                "block, no signal concentrated in one block. Strategies with too little recent data are " +
                "left exactly as they are, never guessed at.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))

        settings.lastAutoOptimizeAtMillis?.let {
            Text(
                "Last run: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))}",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(6.dp))
        }

        Button(
            onClick = { AutoOptimizeState.start(context) },
            enabled = !isRunning
        ) {
            Text(if (isRunning) "Optimizing\u2026" else "Auto-Optimize Now")
        }

        if (isRunning) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (progress?.percent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(progress?.message ?: "", style = MaterialTheme.typography.labelSmall)
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text("Auto-Optimize failed: $it", color = RedSignal, style = MaterialTheme.typography.bodyMedium)
        }

        result?.let { r ->
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${r.unblockedCount} active", color = GreenSignal, style = MaterialTheme.typography.bodyMedium)
                Text("${r.blockedCount} blocked", color = RedSignal, style = MaterialTheme.typography.bodyMedium)
                Text("${r.insufficientCount} unchanged", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${r.changedCount} strateg${if (r.changedCount == 1) "y" else "ies"} flipped this run.",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show per-strategy details")
            }
            if (expanded) {
                r.decisions.sortedByDescending { it.stat?.zScoreVsBreakeven ?: Double.NEGATIVE_INFINITY }.forEach { d ->
                    val color = when (d.verdict) {
                        AutoOptimizeVerdict.UNBLOCK -> GreenSignal
                        AutoOptimizeVerdict.BLOCK -> RedSignal
                        AutoOptimizeVerdict.INSUFFICIENT_DATA -> AmberWarning
                    }
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.strategyId, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(d.verdict.name, color = color, style = MaterialTheme.typography.bodyMedium)
                            if (d.changed) Text("(changed)", color = AmberWarning, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(d.reason, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
