package com.example.prototipopagosoffline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.prototipopagosoffline.database.TransactionHistory

object PaymentState {
    var userBalance by mutableStateOf(15400.50)
    val recentTransactions = mutableStateListOf<TransactionHistory>()
    
    var currentPaymentAmount: Long = 0L

    fun addTransaction(transaction: TransactionHistory) {
        recentTransactions.add(0, transaction)
        // Ajustar saldo: si es saliente (pago) restamos, si es entrante (cobro) sumamos
        if (transaction.isOutgoing) {
            userBalance -= transaction.monto.toDouble()
        } else {
            userBalance += transaction.monto.toDouble()
        }
    }
    
    fun subtractBalance(amount: Double) {
        userBalance -= amount
    }
    
    fun addBalance(amount: Double) {
        userBalance += amount
    }
}
