package com.example.prototipopagosoffline.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.network.RetrofitClient
import com.example.prototipopagosoffline.utils.TokenManager
import com.example.prototipopagosoffline.utils.OfflineProvisioner
import com.example.prototipopagosoffline.network.LoginResponse
import java.time.Instant
import android.util.Log
import androidx.room.withTransaction
import androidx.work.workDataOf
import com.example.prototipopagosoffline.database.OfflineWallet
import com.example.prototipopagosoffline.network.ApiService
import kotlinx.coroutines.CancellationException

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val role = inputData.getString("role") ?: return Result.success()
        val accountId = inputData.getString("account_id") ?: return Result.success()
        if (role !in setOf("cliente", "comercio") || accountId.isBlank()) return Result.success()
        return AccountOperationLocks.withLock(role, accountId) {
            syncAccount(role, accountId)
        }
    }

    private suspend fun syncAccount(role: String, accountId: String): Result {
        val tokenManager = TokenManager(applicationContext)
        val token = tokenManager.getToken() ?: return Result.success()
        return try {
            val database = AppDatabase.getInstance(applicationContext)
            val currentAccountId = if (role == "cliente") tokenManager.getWalletId() else tokenManager.getTerminalId()
            if (tokenManager.getRole() != role || currentAccountId != accountId) return Result.success()
            tokenManager.syncPauseReason()?.let { reason ->
                return Result.success(workDataOf("sync_status" to reason))
            }
            val api = RetrofitClient.authenticatedApi(token)
            if (inputData.getBoolean("retry_evidence_attention", false)) {
                if (role == "cliente") database.nfcExchangeDao()
                    .retryAttentionResolutionRecovery(accountId)
                else database.mobilePosOperationDao()
                    .retryAttentionResolutionRecovery(accountId)
            }
            Log.i("PaymentSync", "STARTED role=$role")
            val run = when (role) {
                "cliente" -> {
                    val result = V2SyncRepository(database, api).syncDue(accountId)
                    val userId = tokenManager.getUserId() ?: return Result.success()
                    OfflineProvisioner(database, api, requestStore = tokenManager).maintainAfterSync(
                        LoginResponse(token, "bearer", user_id = userId, wallet_id = accountId),
                        synchronizeClosable = false
                    )
                    result
                }
                "comercio" -> {
                    MobilePosSyncRepository(database, api).syncDue(accountId)
                }
                else -> return Result.success()
            }
            run.nextSyncAtEpochSeconds?.let { next ->
                val delay = (next - Instant.now().epochSecond).coerceAtLeast(0)
                SyncManager.scheduleWake(applicationContext, role, accountId, delay)
            }
            if (role == "comercio") {
                MerchantBalanceRepository(database, api, tokenManager).refresh(accountId)
            } else {
                refreshClientBalance(database, api, accountId)
            }
            Log.i("PaymentSync", "COMPLETED role=$role resolved=${run.processed} pending=${run.nextSyncAtEpochSeconds != null}")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (pause: SyncPauseRequired) {
            tokenManager.markSyncPaused(token, pause.reason)
            Log.w("PaymentSync", "${pause.reason} role=$role")
            Result.success(workDataOf("sync_status" to pause.reason))
        } catch (_: Exception) {
            val attempt = (inputData.getInt("retry_attempt", 0) + 1).coerceAtMost(20)
            val delay = minOf(15L * (1L shl minOf(attempt - 1, 8)), 3600L)
            SyncManager.scheduleWake(applicationContext, role, accountId, delay, attempt)
            Log.w("PaymentSync", "RETRY_SCHEDULED role=$role seconds=$delay")
            // Do not leave the immediate chain blocked behind WorkManager backoff.
            Result.success(workDataOf("sync_status" to "RETRY_SCHEDULED"))
        }
    }

    private suspend fun refreshClientBalance(database: AppDatabase, api: ApiService, walletId: String) {
        val response = syncHttp { api.getWalletBalance(walletId) }
            ?: throw java.io.IOException("Saldo temporalmente no disponible")
        check(response.isSuccessful)
        val balance = requireNotNull(response.body())
        require(balance.wallet_id == walletId && balance.currency == "ARS" && balance.confirmed_balance_cents >= 0)
        database.withTransaction {
            database.walletDao().insertWallet(OfflineWallet(walletId, balance.confirmed_balance_cents))
            database.walletDao().updateConfirmedBalance(walletId, balance.confirmed_balance_cents, balance.currency)
        }
    }
}
