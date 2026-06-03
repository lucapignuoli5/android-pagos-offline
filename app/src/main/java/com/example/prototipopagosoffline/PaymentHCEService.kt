package com.example.prototipopagosoffline

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

class PaymentHCEService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val hexCommand = commandApdu?.toHexString() ?: "NULL"
        Log.d(TAG, "Recibido APDU: $hexCommand")

        return try {
            if (commandApdu == null) {
                return STATUS_FAILED
            }

            if (!commandApdu.isSelectAidCommand(PAYMENT_AID)) {
                Log.w(TAG, "AID no reconocido o comando no soportado")
                return STATUS_AID_NOT_FOUND
            }

            val amount = PaymentState.currentPaymentAmount
            val id = "TX-${System.currentTimeMillis()}"
            val paymentContractJson = """{"monto":$amount,"id_transaccion":"$id"}"""
            val signature = CryptoManager.signText(paymentContractJson)
            val payload = "$paymentContractJson\n$signature".toByteArray(Charsets.UTF_8)

            Log.i(TAG, "Enviando contrato firmado para monto: $amount")
            payload + STATUS_SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Error en procesamiento HCE", e)
            STATUS_FAILED // 6F 00
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Servicio HCE desactivado. Razon: $reason")
    }

    private fun ByteArray.isSelectAidCommand(expectedAid: ByteArray): Boolean {
        if (size < (SELECT_APDU_HEADER.size + 1)) {
            return false
        }

        val headerMatches = copyOfRange(0, SELECT_APDU_HEADER.size)
            .contentEquals(SELECT_APDU_HEADER)
        if (!headerMatches) {
            return false
        }

        val aidLength = this[SELECT_APDU_HEADER.size].toInt() and 0xFF
        val aidStartIndex = SELECT_APDU_HEADER.size + 1
        val aidEndIndex = aidStartIndex + aidLength

        if (aidEndIndex > size || aidLength != expectedAid.size) {
            return false
        }

        return copyOfRange(aidStartIndex, aidEndIndex).contentEquals(expectedAid)
    }

    private companion object {
        private const val TAG = "NFC_HCE"
        private val PAYMENT_AID = "F222222222".hexToByteArray()
        private val SELECT_APDU_HEADER = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00)
        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00)
        private val STATUS_AID_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00)

        private fun String.hexToByteArray(): ByteArray {
            require(length % 2 == 0) { "Hex string must have an even length." }

            return chunked(2)
                .map { hexPair -> hexPair.toInt(radix = 16).toByte() }
                .toByteArray()
        }

        private fun ByteArray.toHexString(): String = joinToString(separator = " ") { byte ->
            "%02X".format(byte)
        }
    }
}
