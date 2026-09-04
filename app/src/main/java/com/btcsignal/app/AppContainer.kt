package com.btcsignal.app

import android.content.Context
import com.btcsignal.app.ai.AiInferenceEngine
import com.btcsignal.app.backtest.BacktestEngine
import com.btcsignal.app.data.binance.BinanceRestClient
import com.btcsignal.app.data.local.AppDatabase
import com.btcsignal.app.data.model.StrategyDatabase
import com.btcsignal.app.data.repository.SettingsRepository
import com.btcsignal.app.data.repository.SignalRepository
import com.btcsignal.app.engine.StrategyRegistry
import com.btcsignal.app.notifications.NotificationHelper

/**
 * Simple, explicit dependency wiring (spec section: Repository pattern, clean
 * separation of UI and business logic) — deliberately not a DI framework, to keep the
 * project buildable without an extra Gradle plugin/annotation-processor chain.
 */
object AppContainer {
    @Volatile private var strategyDb: StrategyDatabase? = null
    @Volatile private var aiEngine: AiInferenceEngine? = null

    fun strategyDatabase(context: Context): StrategyDatabase =
        strategyDb ?: synchronized(this) {
            strategyDb ?: StrategyRegistry.load(context.applicationContext).also { strategyDb = it }
        }

    fun signalRepository(context: Context): SignalRepository =
        SignalRepository(AppDatabase.get(context.applicationContext).signalDao())

    fun settingsRepository(context: Context): SettingsRepository =
        SettingsRepository(context.applicationContext)

    fun notificationHelper(context: Context): NotificationHelper =
        NotificationHelper(context.applicationContext)

    fun backtestEngine(context: Context): BacktestEngine =
        BacktestEngine(BinanceRestClient(), signalRepository(context))

    /** One ONNX session for the whole app (Live service + Backtest + AI settings screen all
     *  share it), so the model is loaded into memory at most once. Loading/closing happens
     *  from the AI Engine settings screen and on app start if a model is already on disk. */
    fun aiInferenceEngine(context: Context): AiInferenceEngine =
        aiEngine ?: synchronized(this) {
            aiEngine ?: AiInferenceEngine().also { aiEngine = it }
        }
}
