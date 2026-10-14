package com.example.prototipopagosoffline.protocol

import java.util.UUID

fun interface ApduTransceiver {
    suspend fun transceive(command: ByteArray): ByteArray
}

sealed interface MobileNfcExchangeResult {
    data class WaitingForConfirmation(val operationId: String) : MobileNfcExchangeResult
    data class Accepted(val operationId: String) : MobileNfcExchangeResult
}

class MobileNfcReader(
    private val transceiver: ApduTransceiver,
    private val acceptReceipt: suspend (operationId: String, receipt: ByteArray) -> ByteArray
) {
    suspend fun exchange(operationId: String, requestEnvelope: ByteArray): MobileNfcExchangeResult {
        requireCanonicalOperationId(operationId)
        val request = ProtocolV2.parseSignedEnvelope(requestEnvelope)
        require(request.payload["operation_id"].asString == operationId) { "Solicitud de otra operacion" }

        val capabilities = requireSuccess(transceiver.transceive(ProtocolV2.SELECT_APDU))
        require(capabilities.contentEquals(NfcProtocolEngine.SELECT_CAPABILITIES)) {
            "Capacidades NFC incompatibles"
        }
        sendMessage(ProtocolV2.INS_PUT_REQUEST, requestEnvelope)

        val state = readStatus(operationId)
        if (state == ExchangeState.REQUEST_RECEIVED) {
            return MobileNfcExchangeResult.WaitingForConfirmation(operationId)
        }
        require(state in RECEIPT_STATES) { "Estado NFC no conciliable: $state" }

        val receipt = readReceipt(operationId)
            ?: return MobileNfcExchangeResult.WaitingForConfirmation(operationId)
        val ack = acceptReceipt(operationId, receipt)
        val ackState = parseStatus(sendMessage(ProtocolV2.INS_PUT_ACK, ack), operationId)
        require(ackState == ExchangeState.LOCALLY_ACCEPTED || ackState == ExchangeState.SYNCED) {
            "El telefono cliente no confirmo el acuse"
        }
        return MobileNfcExchangeResult.Accepted(operationId)
    }

    private suspend fun readStatus(operationId: String): ExchangeState {
        val command = ProtocolV2.buildApdu(
            ProtocolV2.INS_GET_STATUS,
            sequence = 0,
            isLast = true,
            data = operationId.toByteArray(Charsets.US_ASCII)
        )
        return parseStatus(requireSuccess(transceiver.transceive(command)), operationId)
    }

    private suspend fun readReceipt(operationId: String): ByteArray? {
        val chunks = mutableListOf<ByteArray>()
        for (sequence in 0..255) {
            val command = ProtocolV2.buildApdu(
                ProtocolV2.INS_GET_RECEIPT,
                sequence,
                isLast = true,
                data = operationId.toByteArray(Charsets.US_ASCII)
            )
            val response = transceiver.transceive(command)
            if (response.contentEquals(ProtocolV2.SW_CONDITIONS_NOT_SATISFIED)) return null
            val data = requireSuccess(response)
            require(data.isNotEmpty()) { "Fragmento de comprobante vacio" }
            val isLast = when (data[0].toInt()) {
                0 -> false
                1 -> true
                else -> throw IllegalArgumentException("Indicador de fragmento invalido")
            }
            chunks += data.copyOfRange(1, data.size)
            require(chunks.sumOf { it.size } <= ProtocolV2.MAX_MESSAGE_BYTES) {
                "Comprobante NFC demasiado grande"
            }
            if (isLast) return ProtocolV2.assembleChunks(chunks)
        }
        throw IllegalArgumentException("Demasiados fragmentos NFC")
    }

    private suspend fun sendMessage(instruction: Int, message: ByteArray): ByteArray {
        val chunks = ProtocolV2.chunkMessage(message)
        var finalData = byteArrayOf()
        chunks.forEachIndexed { index, chunk ->
            finalData = requireSuccess(
                transceiver.transceive(
                    ProtocolV2.buildApdu(instruction, index, index == chunks.lastIndex, chunk)
                )
            )
        }
        return finalData
    }

    private fun parseStatus(data: ByteArray, expectedOperationId: String): ExchangeState {
        val parts = data.toString(Charsets.US_ASCII).split('|')
        require(parts.size == 2 && parts[0] == expectedOperationId) { "Estado de otra operacion" }
        return ExchangeState.entries.singleOrNull { it.name == parts[1] }
            ?: throw IllegalArgumentException("Estado NFC desconocido")
    }

    private fun requireSuccess(response: ByteArray): ByteArray {
        require(response.size >= 2) { "Respuesta APDU truncada" }
        val status = response.copyOfRange(response.size - 2, response.size)
        require(status.contentEquals(ProtocolV2.SW_OK)) {
            "Respuesta APDU rechazada (${status.joinToString("") { "%02X".format(it) }})"
        }
        return response.copyOfRange(0, response.size - 2)
    }

    private fun requireCanonicalOperationId(operationId: String) {
        val uuid = UUID.fromString(operationId)
        require(uuid.toString() == operationId && uuid.version() == 4) { "operation_id invalido" }
    }

    companion object {
        private val RECEIPT_STATES = setOf(
            ExchangeState.RESERVED,
            ExchangeState.DELIVERY_UNCERTAIN,
            ExchangeState.LOCALLY_ACCEPTED,
            ExchangeState.SYNCED
        )
    }
}
