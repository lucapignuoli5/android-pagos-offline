package com.example.prototipopagosoffline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.prototipopagosoffline.database.TransactionHistory

object PaymentState {
    var userBalance by mutableStateOf(15400.50)
    var merchantBalance by mutableStateOf(0.0) // NUEVA CAJA DEL COMERCIO

    val recentTransactions = mutableStateListOf<TransactionHistory>()

    var currentPaymentAmount: Long = 0L

    fun addTransaction(transaction: TransactionHistory) {
        // 1. Agregamos la transacción a la lista
        recentTransactions.add(index = 0, element = transaction)

        // 2. Convertimos los centavos (Long) a pesos reales (Double)
        val montoPesos = transaction.monto.toDouble() / 100.0

        // 3. Sumamos o restamos dependiendo de si es un pago o un cobro
        if (transaction.isOutgoing) {
            userBalance -= montoPesos
        } else {
            userBalance += montoPesos
            merchantBalance += montoPesos // SUMAR A LA CAJA DEL COMERCIO
        }
    }

    fun subtractBalance(amount: Double) {
        userBalance -= amount
    }

    fun addBalance(amount: Double) {
        userBalance += amount
    }
}
