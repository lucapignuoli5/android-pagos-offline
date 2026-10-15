package com.example.prototipopagosoffline.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import com.example.prototipopagosoffline.utils.TokenManager
import java.util.concurrent.TimeUnit

object SyncManager {
    fun enqueueSync(context: Context, delaySeconds: Long = 0) {
        val session = TokenManager(context.applicationContext)
        val role = session.getRole() ?: return
        val accountId = when (role) {
            "cliente" -> session.getWalletId()
            "comercio" -> session.getTerminalId()
            else -> null
        } ?: return
        enqueueAccount(context, role, accountId, delaySeconds)
    }

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    internal fun enqueueAccount(context: Context, role: String, accountId: String,
                                delaySeconds: Long = 0, retryAttempt: Int = 0) {
        if (delaySeconds > 0) {
            scheduleWake(context, role, accountId, delaySeconds, retryAttempt)
            return
        }

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setInputData(workDataOf("role" to role, "account_id" to accountId, "retry_attempt" to retryAttempt))
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                "offline_payments_sync_${role}_${accountId}_immediate",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                syncRequest
            )
    }

    internal fun scheduleWake(context: Context, role: String, accountId: String,
                              delaySeconds: Long, retryAttempt: Int = 0) {
        // The wake-up only dispatches work: replacing it never cancels a payment submission.
        val wake = OneTimeWorkRequestBuilder<SyncWakeWorker>()
            .setConstraints(constraints())
            .setInitialDelay(delaySeconds.coerceAtLeast(1), TimeUnit.SECONDS)
            .setInputData(workDataOf("role" to role, "account_id" to accountId, "retry_attempt" to retryAttempt))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "offline_payments_wake_${role}_${accountId}", ExistingWorkPolicy.REPLACE, wake)
    }

    fun ensureRecovery(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncRecoveryWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES).setConstraints(constraints()).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "offline_payments_recovery", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
