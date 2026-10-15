package com.example.prototipopagosoffline.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

// Dispatchers never submit evidence or read another account's payment queue.
class SyncWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val role = inputData.getString("role") ?: return Result.success()
        val account = inputData.getString("account_id") ?: return Result.success()
        SyncManager.enqueueAccount(applicationContext, role, account,
            retryAttempt = inputData.getInt("retry_attempt", 0))
        return Result.success()
    }
}

class SyncRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SyncManager.enqueueSync(applicationContext)
        return Result.success()
    }
}
