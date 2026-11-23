package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class KPlusScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = UserPreferencesRepository(applicationContext).preferencesFlow.first()
        val pipeline = KPlusScannerPipeline(applicationContext)
        return try {
            val summary = pipeline.scanConfiguredFolder(prefs.pHashSimilarityThreshold)
            val output = workDataOf(
                KEY_MESSAGE to summary.statusMessage,
                KEY_NEWLY_IMPORTED to summary.newlyImported,
                KEY_PERMISSION_REVOKED to summary.permissionRevoked
            )
            Result.success(output)
        } catch (_: SecurityException) {
            Result.failure(workDataOf(KEY_MESSAGE to "ไม่ได้รับสิทธิ์เข้าถึงโฟลเดอร์ K PLUS"))
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_MESSAGE to (t.message ?: "การสแกนล้มเหลว")))
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "kplus_periodic_scan"
        const val ONE_TIME_WORK_NAME = "kplus_manual_scan"
        const val KEY_MESSAGE = "scan_message"
        const val KEY_NEWLY_IMPORTED = "newly_imported"
        const val KEY_PERMISSION_REVOKED = "permission_revoked"

        fun schedulePeriodic(context: Context, intervalMinutes: Int) {
            val safeMinutes = intervalMinutes.coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<KPlusScanWorker>(safeMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }

        fun enqueueImmediateScan(context: Context) {
            val request = OneTimeWorkRequestBuilder<KPlusScanWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
