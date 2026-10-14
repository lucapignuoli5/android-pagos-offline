package com.example.prototipopagosoffline.utils

import android.content.Context
import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.network.SimulatedTopupRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SimulatedTopupService(context: Context, private val database: AppDatabase, private val api: ApiService) {
    private val session = TokenManager(context)

    suspend fun addDemoCredit() = mutex.withLock {
        val walletId = session.getWalletId() ?: error("Inicie sesión como cliente")
        val requestId = session.pendingRequestId("topup100000", walletId)
        val response = api.simulatedTopup(SimulatedTopupRequest(requestId, walletId, 100000L))
        require(response.isSuccessful) { "Recarga pendiente; reintente con Internet" }
        val result = requireNotNull(response.body())
        require(result.request_id == requestId && result.wallet_id == walletId && result.amount_cents == 100000L)
        database.withTransaction {
            check(database.walletDao().updateConfirmedBalance(walletId, result.confirmed_balance_cents) == 1)
        }
        session.completeRequest("topup100000", walletId)
    }

    companion object { private val mutex = Mutex() }
}
