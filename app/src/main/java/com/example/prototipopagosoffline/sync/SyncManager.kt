package com.example.prototipopagosoffline.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
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
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delaySeconds.coerceAtLeast(0), TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setInputData(workDataOf("role" to role, "account_id" to accountId))
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                "offline_payments_sync_${role}_${accountId}",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                syncRequest
            )
    }
}
