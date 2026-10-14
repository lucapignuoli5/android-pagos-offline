package com.example.prototipopagosoffline.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.network.RetrofitClient
import com.example.prototipopagosoffline.utils.TokenManager
import java.time.Instant

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            RetrofitClient.init(applicationContext)
            val tokenManager = TokenManager(applicationContext)
            val database = AppDatabase.getInstance(applicationContext)
            val role = inputData.getString("role") ?: return Result.success()
            val accountId = inputData.getString("account_id") ?: return Result.success()
            val currentAccountId = if (role == "cliente") tokenManager.getWalletId() else tokenManager.getTerminalId()
            if (tokenManager.getRole() != role || currentAccountId != accountId) return Result.success()
            val run = when (role) {
                "cliente" -> {
                    V2SyncRepository(database, RetrofitClient.apiService).syncDue(accountId)
                }
                "comercio" -> {
                    MobilePosSyncRepository(database, RetrofitClient.apiService).syncDue(accountId)
                }
                else -> return Result.success()
            }
            run.nextSyncAtEpochSeconds?.let { next ->
                val delay = (next - Instant.now().epochSecond).coerceAtLeast(0)
                SyncManager.enqueueSync(applicationContext, delay)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
