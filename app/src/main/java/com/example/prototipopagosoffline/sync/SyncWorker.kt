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
            val run = when (tokenManager.getRole()) {
                "cliente" -> {
                    val walletId = tokenManager.getWalletId() ?: return Result.success()
                    V2SyncRepository(database, RetrofitClient.apiService).syncDue(walletId)
                }
                "comercio" -> {
                    val terminalId = tokenManager.getTerminalId() ?: return Result.success()
                    MobilePosSyncRepository(database, RetrofitClient.apiService).syncDue(terminalId)
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
