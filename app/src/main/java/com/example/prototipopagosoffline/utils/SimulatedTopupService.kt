package com.example.prototipopagosoffline.utils

import android.content.Context
import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.OfflineWallet
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.SimulatedTopupRequest
import com.example.prototipopagosoffline.sync.AccountOperationLocks
import com.example.prototipopagosoffline.sync.SyncManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SimulatedTopupService(context: Context, private val database: AppDatabase, private val api: ApiService) {
    private val applicationContext = context.applicationContext
    private val session = TokenManager(context)

    suspend fun addDemoCredit() = mutex.withLock {
        val walletId = session.getWalletId() ?: error("Inicie sesión como cliente")
        require(session.getRole() == "cliente")
        val capturedToken = session.getToken() ?: error("Inicie sesión como cliente")
        AccountOperationLocks.withLock("cliente", walletId) {
            require(session.getRole() == "cliente" && session.getWalletId() == walletId &&
                session.getToken() == capturedToken) { "La sesion cambio; volve a ingresar" }
            val requestId = session.pendingRequestId("topup100000", walletId)
            val response = api.simulatedTopup(SimulatedTopupRequest(requestId, walletId, 100000L))
            require(response.isSuccessful) { "Recarga pendiente; reintente con Internet" }
            val result = requireNotNull(response.body())
            require(result.request_id == requestId && result.wallet_id == walletId && result.amount_cents == 100000L)
            database.withTransaction {
                database.walletDao().insertWallet(OfflineWallet(walletId, result.confirmed_balance_cents))
                check(database.walletDao().updateConfirmedBalance(walletId, result.confirmed_balance_cents) == 1)
            }
            session.completeRequest("topup100000", walletId)
            // Queue uses the captured account, even if the visible session changes.
            // Its worker rechecks that session before authorizing any new budget.
            SyncManager.enqueueAccount(applicationContext, "cliente", walletId)
        }
    }

    companion object { private val mutex = Mutex() }
}
