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
            val walletId = TokenManager(applicationContext).getWalletId()
                ?: return Result.success()
            val run = V2SyncRepository(
                AppDatabase.getInstance(applicationContext),
                RetrofitClient.apiService
            ).syncDue(walletId)
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
