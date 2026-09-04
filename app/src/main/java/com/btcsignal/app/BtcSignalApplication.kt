package com.btcsignal.app

import android.app.Application
import com.btcsignal.app.ai.AiModelManager
import com.btcsignal.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BtcSignalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper(this).ensureChannels(soundEnabled = true, vibrationEnabled = true)

        // If a model was already downloaded in a previous session, load it back into memory
        // now rather than lazily on the AI Engine settings screen, so Live/Backtest can use
        // AI Assist immediately if the person left it enabled. Off the main thread: loading
        // an ONNX session does file + native work that must not block app cold-start.
        // Failure here just means AI Assist stays unavailable until the person reopens AI
        // Engine settings -- never fatal.
        CoroutineScope(Dispatchers.IO).launch {
            AiModelManager.currentModelInfo(this@BtcSignalApplication)?.let { info ->
                AppContainer.aiInferenceEngine(this@BtcSignalApplication).loadFromFile(info.file)
            }
        }
    }
}
