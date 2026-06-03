package com.example.prototipopagosoffline.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.SyncState
import com.example.prototipopagosoffline.network.RetrofitClient
import com.example.prototipopagosoffline.network.SyncItem
import java.io.IOException

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val database = AppDatabase.getInstance(applicationContext)
        val transactionDao = database.transactionDao()
        val pendingTransactions = transactionDao.getTransactionsBySyncState(SyncState.PENDING)

        if (pendingTransactions.isEmpty()) {
            return Result.success()
        }

        return try {
            val payments = pendingTransactions.map { transaction ->
                SyncItem(
                    id_transaccion = transaction.idTransaccion,
                    monto = transaction.monto.toInt(),
                    timestamp = transaction.timestamp,
                    comercio_id = transaction.comercioId,
                    token_id = transaction.tokenId,
                    firma = transaction.firma,
                    payload_original = transaction.payloadOriginal,
                    clave_publica = transaction.clavePublica
                )
            }

            val response = RetrofitClient.apiService.syncPayments(payments)

            if (response.isSuccessful) {
                val syncedIds = pendingTransactions.map { it.idTransaccion }
                transactionDao.updateSyncState(syncedIds, SyncState.SYNCED)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (exception: IOException) {
            Result.retry()
        }
    }
}
