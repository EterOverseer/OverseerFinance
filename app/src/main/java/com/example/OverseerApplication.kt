package com.example

import android.app.Application
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.FinanceRepository
import com.example.notifications.ReminderScheduler
import com.example.worker.KPlusScanWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OverseerApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val repository: FinanceRepository by lazy { FinanceRepository(this) }
    val preferencesRepository: UserPreferencesRepository by lazy { UserPreferencesRepository(this) }

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.ensureChannel(this)
        appScope.launch {
            runCatching {
                repository.ensureInitialized()
                val prefs = preferencesRepository.preferencesFlow.first()
                if (prefs.autoScanEnabled) {
                    KPlusScanWorker.schedulePeriodic(this@OverseerApplication, prefs.scanIntervalMinutes)
                    KPlusScanWorker.enqueueImmediateScan(this@OverseerApplication)
                }
            }
        }
    }
}
