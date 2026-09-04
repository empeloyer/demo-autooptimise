package com.btcsignal.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.btcsignal.app.ai.AiMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "btc_signal_settings")

data class AppSettings(
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val blockedStrategyIds: Set<String> = emptySet(),
    val lastAutoOptimizeAtMillis: Long? = null,
    // --- AI Engine (ONNX) settings ---
    val aiModelUrl: String = "",
    val aiAssistEnabled: Boolean = false,
    val aiMode: AiMode = AiMode.CONFIRM,
    val aiConfidenceThreshold: Double = 0.55,
    val aiOverrideThreshold: Double = 0.75,
    val storagePermissionRequested: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val BLOCKED_STRATEGY_IDS = stringSetPreferencesKey("blocked_strategy_ids")
        val LAST_AUTO_OPTIMIZE_AT = longPreferencesKey("last_auto_optimize_at_millis")
        val AI_MODEL_URL = stringPreferencesKey("ai_model_url")
        val AI_ASSIST_ENABLED = booleanPreferencesKey("ai_assist_enabled")
        val AI_MODE = stringPreferencesKey("ai_mode")
        val AI_CONFIDENCE_THRESHOLD = doublePreferencesKey("ai_confidence_threshold")
        val AI_OVERRIDE_THRESHOLD = doublePreferencesKey("ai_override_threshold")
        val STORAGE_PERMISSION_REQUESTED = booleanPreferencesKey("storage_permission_requested")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            soundEnabled = prefs[Keys.SOUND] ?: true,
            vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
            blockedStrategyIds = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: emptySet(),
            lastAutoOptimizeAtMillis = prefs[Keys.LAST_AUTO_OPTIMIZE_AT],
            aiModelUrl = prefs[Keys.AI_MODEL_URL] ?: "",
            aiAssistEnabled = prefs[Keys.AI_ASSIST_ENABLED] ?: false,
            aiMode = prefs[Keys.AI_MODE]?.let { runCatching { AiMode.valueOf(it) }.getOrNull() } ?: AiMode.CONFIRM,
            aiConfidenceThreshold = prefs[Keys.AI_CONFIDENCE_THRESHOLD] ?: 0.55,
            aiOverrideThreshold = prefs[Keys.AI_OVERRIDE_THRESHOLD] ?: 0.75,
            storagePermissionRequested = prefs[Keys.STORAGE_PERMISSION_REQUESTED] ?: false
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND] = enabled }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATION] = enabled }
    }

    /** Toggles a strategy's temporary block state (Strategies screen "Block" button).
     * A blocked strategy is skipped by CoreSignalEngine on the Live path only — it
     * cannot emit new signals until unblocked again. */
    suspend fun setStrategyBlocked(strategyId: String, blocked: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: emptySet()
            prefs[Keys.BLOCKED_STRATEGY_IDS] = if (blocked) current + strategyId else current - strategyId
        }
    }

    /** Applies every changed decision from an [com.btcsignal.app.engine.AutoOptimizeResult]
     *  in one write, and stamps the run time. Strategies with an unchanged/insufficient-data
     *  verdict are left untouched -- only ids that actually flip are added to/removed from
     *  the blocked set. */
    suspend fun applyAutoOptimizeResult(toBlock: Set<String>, toUnblock: Set<String>, evaluatedAtMillis: Long) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_STRATEGY_IDS] ?: emptySet()
            prefs[Keys.BLOCKED_STRATEGY_IDS] = (current + toBlock) - toUnblock
            prefs[Keys.LAST_AUTO_OPTIMIZE_AT] = evaluatedAtMillis
        }
    }

    suspend fun setAiModelUrl(url: String) {
        context.dataStore.edit { it[Keys.AI_MODEL_URL] = url }
    }

    suspend fun setAiAssistEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AI_ASSIST_ENABLED] = enabled }
    }

    suspend fun setAiMode(mode: AiMode) {
        context.dataStore.edit { it[Keys.AI_MODE] = mode.name }
    }

    suspend fun setAiConfidenceThreshold(value: Double) {
        context.dataStore.edit { it[Keys.AI_CONFIDENCE_THRESHOLD] = value }
    }

    suspend fun setAiOverrideThreshold(value: Double) {
        context.dataStore.edit { it[Keys.AI_OVERRIDE_THRESHOLD] = value }
    }

    suspend fun setStoragePermissionRequested(requested: Boolean) {
        context.dataStore.edit { it[Keys.STORAGE_PERMISSION_REQUESTED] = requested }
    }
}
