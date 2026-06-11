package com.example.prototipopagosoffline.utils

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.example.prototipopagosoffline.PaymentState
import com.example.prototipopagosoffline.database.AppDatabase
import com.example.prototipopagosoffline.database.SyncState
import com.example.prototipopagosoffline.database.TransactionHistory
import com.example.prototipopagosoffline.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PaymentHCEService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val hexCommand = commandApdu?.joinToString("") { "%02X".format(it) } ?: ""
        Log.d("HCE_SERVICE", "APDU recibido: $hexCommand")

        // Si es el comando SELECT APDU buscando nuestro AID F222222222
        if (hexCommand.startsWith("00A4040005F222222222")) {
            val amount = PaymentState.currentPaymentAmount
            if (amount <= 0L) return byteArrayOf(0x6A.toByte(), 0x82.toByte())

            // VALIDACIÓN DE SALDO REAL
            val saldoEnCentavos = (PaymentState.userBalance * 100).toLong()
            if (saldoEnCentavos < amount) {
                Log.e("HCE_SERVICE", "Saldo insuficiente: $saldoEnCentavos < $amount")
                val errorPayload = "ERROR|SALDO_INSUFICIENTE".toByteArray(Charsets.UTF_8)
                val errorStatusWord = byteArrayOf(0x6A.toByte(), 0x83.toByte())
                return errorPayload + errorStatusWord
            }

            val txId = "TX-NFC-${System.currentTimeMillis()}"
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val amountFormatted = String.format(Locale.US, "%.1f", amount.toDouble())
            val tokenId = "TOKEN-USER-QR"

            val payloadToSign = "$txId|$amountFormatted|$timestamp"

            // PURGA ESTRICTA DE SALTOS DE LÍNEA EN FIRMA Y CLAVE
            val signatureBase64 = SecurityUtils.signPayload(payloadToSign).replace("\n", "").trim()
            val rawPubKey = SecurityUtils.getPublicKey()
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\n", "")
                .trim()

            // Orden estricto para no superar los 252 bytes: PubKey | Firma | TokenID | Monto | Timestamp
            val compactPayload = "$rawPubKey|$signatureBase64|$tokenId|$amount|$timestamp"

            val payloadBytes = compactPayload.toByteArray(Charsets.UTF_8)
            val successStatusWord = byteArrayOf(0x90.toByte(), 0x00.toByte())

            // CREAR Y PERSISTIR LA TRANSACCIÓN DEL CLIENTE
            val transaction = TransactionHistory(
                idTransaccion = txId,
                monto = amount,
                timestamp = timestamp,
                comercioId = 1,
                tokenId = tokenId,
                firma = signatureBase64,
                payloadOriginal = payloadToSign,
                clavePublica = SecurityUtils.getPublicKey(),
                isOutgoing = true,
                estadoSincronizacion = SyncState.PENDING
            )

            val serviceContext = this
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getInstance(serviceContext)
                db.transactionDao().insertTransaction(transaction)
                SyncManager.enqueueSync(serviceContext)
                withContext(Dispatchers.Main) {
                    PaymentState.addTransaction(transaction)
                }
            }

            PaymentState.currentPaymentAmount = 0L
            
            // Retornar la concatenación para que el lector valide el éxito
            return payloadBytes + successStatusWord
        }

        // Comando no reconocido
        return byteArrayOf(0x00, 0x00)
    }

    override fun onDeactivated(reason: Int) {
        Log.d("HCE_SERVICE", "Servicio HCE desactivado. Razón: $reason")
    }
}