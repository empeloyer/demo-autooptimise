package com.btcsignal.app.backtest

import android.content.Context
import com.btcsignal.app.AppContainer
import com.btcsignal.app.engine.AutoOptimizeEngine
import com.btcsignal.app.engine.AutoOptimizeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Backs the Settings screen's "Auto-Optimize" button. Reuses [BacktestEngine] to fetch +
 * replay [AutoOptimizeEngine.DEFAULT_WINDOW_DAYS] days of real Binance history through the
 * exact same Core Signal Engine as Live/Backtest (persist = false: this is a statistics
 * pass, not a Backtest-tab run, so it never touches the Backtest history table), then hands
 * the resulting signals to [AutoOptimizeEngine] for the block/unblock decision and applies
 * it to [com.btcsignal.app.data.repository.SettingsRepository] in one write.
 *
 * Process-scoped like [BacktestState] for the same reason: fetching 180 days of 1-minute
 * klines takes a while, and switching tabs shouldn't cancel it.
 */
object AutoOptimizeState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isRunning = MutableStateFlow(false)
    val progress = MutableStateFlow<BacktestProgress?>(null)
    val result = MutableStateFlow<AutoOptimizeResult?>(null)
    val error = MutableStateFlow<String?>(null)

    fun start(context: Context) {
        if (isRunning.value) return
        val appContext = context.applicationContext

        isRunning.value = true
        error.value = null
        progress.value = BacktestProgress(BacktestPhase.FETCHING, 0, "Starting Auto-Optimize\u2026")

        job = scope.launch {
            try {
                val db = AppContainer.strategyDatabase(appContext)
                val engine = AppContainer.backtestEngine(appContext)
                val settingsRepo = AppContainer.settingsRepository(appContext)
                val windowDays = AutoOptimizeEngine.DEFAULT_WINDOW_DAYS

                val summary = engine.run(
                    database = db,
                    periodDays = windowDays,
                    persist = false,
                    onProgress = { p -> progress.value = p }
                )

                val currentlyBlocked = settingsRepo.settingsFlow.first().blockedStrategyIds
                val end = System.currentTimeMillis()
                val start = end - windowDays * 24L * 60 * 60 * 1000
                val evalResult = AutoOptimizeEngine.evaluate(
                    database = db,
                    signals = summary.allSignals,
                    currentlyBlocked = currentlyBlocked,
                    windowStartMillis = start,
                    windowEndMillis = end
                )

                val toBlock = evalResult.decisions.filter { it.changed && it.newBlockedState }.map { it.strategyId }.toSet()
                val toUnblock = evalResult.decisions.filter { it.changed && !it.newBlockedState }.map { it.strategyId }.toSet()
                settingsRepo.applyAutoOptimizeResult(toBlock, toUnblock, evalResult.evaluatedAtMillis)

                result.value = evalResult
            } catch (e: Exception) {
                error.value = e.message ?: "Auto-Optimize failed"
            } finally {
                isRunning.value = false
            }
        }
    }
}
