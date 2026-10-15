package com.example.prototipopagosoffline.sync

import androidx.room.withTransaction
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.OfflineWallet
import com.example.prototipopagosoffline.network.ApiService
import com.example.prototipopagosoffline.utils.TokenManager

class MerchantBalanceRepository(
    private val database: AppDatabase,
    private val api: ApiService,
    private val session: TokenManager
) {
    suspend fun refresh(terminalId: String) {
        val terminal = database.mobileTerminalDao().getById(terminalId) ?: return
        val token = session.getToken() ?: return
        fun sameSession() = session.getRole() == "comercio" &&
            session.getTerminalId() == terminalId && session.getUserId() == terminal.userId &&
            session.getToken() == token
        if (!sameSession()) return
        val walletId = "wallet-${terminal.userId}"
        val response = api.getWalletBalance(walletId)
        check(response.isSuccessful) { "No se pudo actualizar el saldo comercial" }
        val balance = requireNotNull(response.body())
        require(balance.wallet_id == walletId && balance.currency == "ARS" && balance.confirmed_balance_cents >= 0)
        if (!sameSession()) return
        database.withTransaction {
            database.walletDao().insertWallet(OfflineWallet(
                id = walletId, confirmedBalanceCents = balance.confirmed_balance_cents,
                currency = balance.currency
            ))
            // Refresh confirmed funds only; never replace a row or its reservations.
            database.walletDao().updateConfirmedBalance(walletId, balance.confirmed_balance_cents, balance.currency)
        }
    }
}
